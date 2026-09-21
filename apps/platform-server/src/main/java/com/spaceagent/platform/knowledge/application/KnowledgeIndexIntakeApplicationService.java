package com.spaceagent.platform.knowledge.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.spaceagent.platform.identity.api.*;
import com.spaceagent.platform.inference.api.InferenceApplicationApi;
import com.spaceagent.platform.knowledge.api.*;
import com.spaceagent.platform.knowledge.domain.*;
import com.spaceagent.shared.exception.BusinessException;
import com.spaceagent.shared.time.TimeProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static com.spaceagent.platform.knowledge.domain.KnowledgeBase.Permission.*;

@Service
@ConditionalOnProperty(prefix="platform",name="persistence",havingValue="postgres")
public class KnowledgeIndexIntakeApplicationService implements KnowledgeIndexIntakeApplicationApi {
    private final KnowledgeAccessApplicationApi access;
    private final IdentityApplicationApi identity;
    private final IdentityActivityApplicationApi activity;
    private final InferenceApplicationApi inference;
    private final KnowledgeBaseRepository bases;
    private final KnowledgeDocumentRepository documents;
    private final KnowledgeIndexCatalogRepository catalog;
    private final KnowledgeIndexCatalogApplicationApi descriptors;
    private final KnowledgeIndexPublicationRepository publications;
    private final KnowledgeIndexIntakeRepository requests;
    private final KnowledgeIndexJobApplicationApi jobs;
    private final KnowledgeIndexObjectStore objects;
    private final KnowledgeUrlRepository urls;
    private final KnowledgeUrlContentStore urlContent;
    private final KnowledgeUrlContentParser parser;
    private final ObjectMapper json;
    private final TimeProvider time;
    private final TransactionTemplate transaction;
    private final boolean enabled;
    private final KnowledgeObjectInventory inventory;
    private final KnowledgeIndexDeletionRepository deletions;
    private final KnowledgeDocumentParsingGateway documentParsing;
    private final KnowledgeProcessingPolicyRepository processingPolicies;
    @org.springframework.beans.factory.annotation.Autowired private KnowledgeOperationalRepository operations;
    @Value("${platform.knowledge.limits.documents-per-base:10000}") private int maxDocuments;
    @Value("${platform.knowledge.limits.pending-per-tenant:64}") private int maxPending;
    @Value("${platform.knowledge.limits.source-bytes-per-tenant:1073741824}") private long maxBytes;
    public KnowledgeIndexIntakeApplicationService(KnowledgeAccessApplicationApi access,IdentityApplicationApi identity,
            IdentityActivityApplicationApi activity,InferenceApplicationApi inference,KnowledgeBaseRepository bases,
            KnowledgeDocumentRepository documents,KnowledgeIndexCatalogRepository catalog,KnowledgeIndexCatalogApplicationApi descriptors,
            KnowledgeIndexPublicationRepository publications,KnowledgeIndexIntakeRepository requests,KnowledgeIndexJobApplicationApi jobs,
            KnowledgeIndexObjectStore objects,KnowledgeUrlRepository urls,KnowledgeUrlContentStore urlContent,KnowledgeUrlContentParser parser,
            ObjectMapper json,TimeProvider time,PlatformTransactionManager manager,
            @Value("${platform.knowledge.indexing.intake-enabled:false}") boolean enabled,
            KnowledgeObjectInventory inventory,KnowledgeIndexDeletionRepository deletions,KnowledgeDocumentParsingGateway documentParsing,KnowledgeProcessingPolicyRepository processingPolicies) {
        this.access=access;this.identity=identity;this.activity=activity;this.inference=inference;this.bases=bases;
        this.documents=documents;this.catalog=catalog;this.descriptors=descriptors;this.publications=publications;
        this.requests=requests;this.jobs=jobs;this.objects=objects;this.urls=urls;this.urlContent=urlContent;
        this.parser=parser;this.json=json;this.time=time;this.transaction=new TransactionTemplate(manager);this.enabled=enabled;
        this.inventory=inventory;this.deletions=deletions;
        this.documentParsing=documentParsing;
        this.processingPolicies=processingPolicies;
    }
    @Override @Transactional(propagation=Propagation.NOT_SUPPORTED)
    public IntakeView file(FileCommand c) {
        guard(c.actor(),c.baseId());
        if(c.mediaType()==null || !Set.of("text/plain","text/markdown","text/html","application/pdf","application/vnd.openxmlformats-officedocument.wordprocessingml.document").contains(c.mediaType())) throw invalid("KNOWLEDGE_FILE_TYPE_UNSUPPORTED");
        if(!documentParsing.supports(c.mediaType()))throw new BusinessException("Isolated parser is not configured",HttpStatus.SERVICE_UNAVAILABLE,"KNOWLEDGE_PARSER_UNAVAILABLE");
        byte[] raw=c.bytes();
        return submit(c.actor(),c.baseId(),c.documentId(),c.expectedRevision(),c.name(),c.spaceId(),c.idempotencyKey(),
                "FILE",null,c.mediaType(),raw,"UTF-8",null);
    }
    @Override @Transactional(propagation=Propagation.NOT_SUPPORTED)
    public IntakeView urlSnapshot(UrlCommand c) {
        guard(c.actor(),c.baseId());
        var sourceJob=urls.findJob(c.actor().organizationId(),c.actor().userId(),c.urlJobId()).orElseThrow(()->missing("KNOWLEDGE_URL_SOURCE_NOT_FOUND"));
        var sourceScope=catalog.findDocumentScope(sourceJob.knowledgeDocumentId()).orElseThrow(()->missing("KNOWLEDGE_URL_SOURCE_NOT_FOUND"));
        access.authorize(c.actor(),sourceScope.baseId(),READ);
        documents.findById(sourceJob.knowledgeDocumentId()).filter(d->d.status()!=KnowledgeDocumentStatus.DELETED)
                .orElseThrow(()->missing("KNOWLEDGE_URL_SOURCE_NOT_FOUND"));
        var version=urls.contentVersions(c.actor().organizationId(),c.urlJobId()).stream()
                .filter(v->v.id().equals(c.versionId()) && Set.of(KnowledgeUrlEvidence.ContentState.STAGED,
                        KnowledgeUrlEvidence.ContentState.ACTIVE,KnowledgeUrlEvidence.ContentState.SUPERSEDED).contains(v.state()))
                .findFirst().orElseThrow(()->missing("KNOWLEDGE_URL_SOURCE_NOT_FOUND"));
        if(version.byteSize()>8_000_000) throw new BusinessException("URL snapshot exceeds intake bound",HttpStatus.PAYLOAD_TOO_LARGE,"KNOWLEDGE_FILE_TOO_LARGE");
        byte[] raw;
        try {raw=urlContent.read(version.objectReference(),8_000_000);}
        catch(RuntimeException e){throw new BusinessException("URL snapshot bytes are unavailable",HttpStatus.SERVICE_UNAVAILABLE,"KNOWLEDGE_URL_SOURCE_UNAVAILABLE");}
        if(raw.length!=version.byteSize() || !version.contentSha256().equals("sha256:"+hash(raw))) throw invalid("KNOWLEDGE_URL_SOURCE_HASH_MISMATCH");
        return submit(c.actor(),c.baseId(),c.documentId(),c.expectedRevision(),c.name(),c.spaceId(),c.idempotencyKey(),
                "URL_SNAPSHOT",version.id(),version.mediaType(),raw,version.charset(),
                new SourceAuthority(sourceJob.id(),sourceJob.knowledgeDocumentId(),sourceScope.baseId(),version.id()));
    }
    private IntakeView submit(KnowledgeBaseApplicationApi.Actor actor,String baseId,String requestedDoc,long revision,String name,
            String spaceId,String key,String kind,String origin,String media,byte[] raw,String charset,SourceAuthority sourceAuthority) {
        validate(key,requestedDoc,revision,name);
        if(raw.length==0 || raw.length>8_000_000) throw invalid("KNOWLEDGE_FILE_SIZE_INVALID");
        var space=selection(actor,baseId,spaceId);
        String doc=requestedDoc==null?UUID.nameUUIDFromBytes((baseId+"\n"+actor.userId()+"\n"+key).getBytes(StandardCharsets.UTF_8)).toString():requestedDoc;
        if(deletions.reserved(baseId,doc)) throw new BusinessException("Deleted document identity cannot be reused",HttpStatus.GONE,"KNOWLEDGE_DOCUMENT_DELETED");
        if(requestedDoc!=null) explicitDocument(baseId,doc);
        boolean binary=Set.of("application/pdf","application/vnd.openxmlformats-officedocument.wordprocessingml.document").contains(media);
        byte[] normalized=binary?raw:parser.parse(raw,media,charset).getBytes(StandardCharsets.UTF_8);
        String originalHash=hash(raw),normalizedHash=hash(normalized);
        String normalization=binary?"RAW_BINARY_V1":"NORMALIZATION_V1";
        String requestHash=digest(Arrays.asList(normalization,doc,revision,name,spaceId,space.fingerprint(),kind,origin,media,charset,originalHash,normalizedHash));
        // Reject known stale/conflicting/over-quota input before it can fill the orphan-object grace window.
        // The transaction below repeats these checks to fence concurrent admissions.
        var priorRequest=requests.find(baseId,actor.userId(),key);
        if(priorRequest.isPresent()){
            if(!priorRequest.get().requestHash().equals(requestHash))throw conflict();
            return view(priorRequest.get(),actor);
        }
        if(requestedDoc!=null && publications.head(baseId,doc).filter(h->h.state().equals("ACTIVE") && h.revision()==revision).isEmpty())throw conflict();
        try{operations.admit(baseId,actor.organizationId(),requestedDoc==null,raw.length,maxDocuments,maxPending,maxBytes);}
        catch(IllegalArgumentException e){throw new BusinessException("Knowledge intake quota exceeded",HttpStatus.TOO_MANY_REQUESTS,"KNOWLEDGE_INTAKE_QUOTA_EXCEEDED");}
        // Do not write object bytes while holding database locks. Unreferenced failed-admission objects remain private
        // and are retained for the bounded orphan sweep; they never become an authoritative source or visible index.
        String originalRef,normalizedRef;
        try {originalRef=objects.put(doc,originalHash,raw);normalizedRef=objects.put(doc,normalizedHash,normalized);}
        catch(RuntimeException e){throw new BusinessException("Index source storage is unavailable",HttpStatus.SERVICE_UNAVAILABLE,"KNOWLEDGE_INDEX_STORAGE_UNAVAILABLE");}
        try {
            return transaction.execute(s->{
                bases.lock(baseId).orElseThrow(()->missing("KNOWLEDGE_BASE_NOT_FOUND"));
                var base=guard(actor,baseId);selection(actor,baseId,spaceId);
                inventory.lockOwner(doc);
                objects.read(originalRef,originalHash);objects.read(normalizedRef,normalizedHash);
                if(deletions.reserved(baseId,doc)) throw new BusinessException("Deleted document identity cannot be reused",HttpStatus.GONE,"KNOWLEDGE_DOCUMENT_DELETED");
                if(sourceAuthority!=null) recheckSource(actor,sourceAuthority,originalHash);
                var prior=requests.find(baseId,actor.userId(),key);
                if(prior.isPresent()) {
                    if(!prior.get().requestHash().equals(requestHash)) throw conflict();
                    return view(prior.get(),actor);
                }
                KnowledgeDocument previous=requestedDoc==null?null:explicitDocument(baseId,doc);
                if(previous==null && documents.findById(doc).isPresent()) throw conflict();
                try{operations.admit(baseId,actor.organizationId(),previous==null,raw.length,maxDocuments,maxPending,maxBytes);}
                catch(IllegalArgumentException e){throw new BusinessException("Knowledge intake quota exceeded",HttpStatus.TOO_MANY_REQUESTS,"KNOWLEDGE_INTAKE_QUOTA_EXCEEDED");}
                var now=time.now();
                if(previous==null) {
                    String owner=base.scope()==KnowledgeBase.Scope.PERSONAL?base.ownerId():null;
                    documents.save(new KnowledgeDocument(doc,owner,name,"text/plain","managed-index:"+doc,KnowledgeDocumentStatus.UPLOADED,now,now));
                    catalog.insertScopeIfAbsent(new KnowledgeDocumentScope(doc,baseId,owner,"EXPLICIT",now));
                } else {
                    documents.save(new KnowledgeDocument(doc,previous.ownerId(),name,previous.contentType(),previous.storageLocation(),previous.status(),previous.createdAt(),now));
                }
                var policy=processingPolicies.get(baseId).policy();
                var generation=descriptors.stage(new KnowledgeIndexCatalogApplicationApi.StageGenerationCommand(actor,baseId,doc,spaceId,
                        normalizedHash,binary?KnowledgeIndexBuildCoordinator.BINARY_PARSER_HASH:KnowledgeIndexBuildCoordinator.PARSER_HASH,policy.fingerprint(),revision+1)).generation();
                publications.registerSource(generation.id(),revision,normalizedRef,binary?media:"text/plain",binary?charset:"UTF-8",policy);
                var job=jobs.enqueue(actor,baseId,generation.id(),"intake:"+key);
                var receipt=new KnowledgeIndexIntakeRepository.Receipt(baseId,actor.userId(),key,requestHash,doc,generation.id(),job.id(),revision+1,
                        kind,origin,media,originalHash,originalRef,charset,normalization,raw.length);
                requests.insert(receipt);return view(receipt,actor);
            });
        } catch(IllegalStateException | org.springframework.dao.DataIntegrityViolationException e) {throw conflict();}
    }
    @Override public DocumentView document(KnowledgeBaseApplicationApi.Actor actor,String baseId,String documentId) {
        if(actor==null || !activity.isUserActive(actor.userId())) throw new BusinessException("Index actor is unavailable",HttpStatus.FORBIDDEN,"INDEX_ACTOR_UNAVAILABLE");
        var base=access.authorize(actor,baseId,READ).base();
        var doc=explicitDocument(baseId,documentId);var head=publications.head(baseId,documentId).orElseThrow(()->missing("KNOWLEDGE_DOCUMENT_NOT_FOUND"));
        return new DocumentView(doc.id(),doc.name(),base.scope().name(),doc.ownerId(),head.revision(),head.state(),head.activeGenerationId());
    }
    private KnowledgeDocument explicitDocument(String base,String doc) {
        catalog.findDocumentScope(doc).filter(s->s.baseId().equals(base) && "EXPLICIT".equals(s.provenance()))
                .orElseThrow(()->missing("KNOWLEDGE_INDEXED_DOCUMENT_NOT_FOUND"));
        return documents.findById(doc).filter(d->d.status()!=KnowledgeDocumentStatus.DELETED).orElseThrow(()->missing("KNOWLEDGE_DOCUMENT_NOT_FOUND"));
    }
    private record SourceAuthority(String job,String document,String base,String version) {}
    private void recheckSource(KnowledgeBaseApplicationApi.Actor actor,SourceAuthority source,String originalHash) {
        access.authorize(actor,source.base(),READ);
        catalog.findDocumentScope(source.document()).filter(s->s.baseId().equals(source.base())).orElseThrow(()->missing("KNOWLEDGE_URL_SOURCE_NOT_FOUND"));
        documents.findById(source.document()).filter(d->d.status()!=KnowledgeDocumentStatus.DELETED).orElseThrow(()->missing("KNOWLEDGE_URL_SOURCE_NOT_FOUND"));
        urls.findJob(actor.organizationId(),actor.userId(),source.job()).filter(j->j.knowledgeDocumentId().equals(source.document()))
                .orElseThrow(()->missing("KNOWLEDGE_URL_SOURCE_NOT_FOUND"));
        urls.contentVersions(actor.organizationId(),source.job()).stream().filter(v->v.id().equals(source.version())
                && v.contentSha256().equals("sha256:"+originalHash) && Set.of(KnowledgeUrlEvidence.ContentState.STAGED,
                KnowledgeUrlEvidence.ContentState.ACTIVE,KnowledgeUrlEvidence.ContentState.SUPERSEDED).contains(v.state()))
                .findFirst().orElseThrow(()->missing("KNOWLEDGE_URL_SOURCE_NOT_FOUND"));
    }
    private KnowledgeBase guard(KnowledgeBaseApplicationApi.Actor actor,String base) {
        if(!enabled) throw new BusinessException("Index intake is not enabled",HttpStatus.SERVICE_UNAVAILABLE,"KNOWLEDGE_INDEX_INTAKE_DISABLED");
        if(actor==null || !activity.isUserActive(actor.userId()) || actor.organizationId()==null
                || identity.findTenant(actor.organizationId()).filter(t->"ACTIVE".equals(t.status().name())).isEmpty()
                || identity.findTenantMembership(actor.organizationId(),actor.userId()).filter(m->"ACTIVE".equals(m.status().name())).isEmpty())
            throw new BusinessException("Index actor is unavailable",HttpStatus.FORBIDDEN,"INDEX_ACTOR_UNAVAILABLE");
        return access.authorize(actor,base,WRITE).base();
    }
    private KnowledgeEmbeddingSpace selection(KnowledgeBaseApplicationApi.Actor actor,String base,String spaceId) {
        var space=catalog.findSpace(base,spaceId).orElseThrow(()->missing("KNOWLEDGE_EMBEDDING_SPACE_NOT_FOUND"));
        var provider=inference.findProvider(space.providerId()).filter(p->p.enabled() && p.hasSecret() && p.ownerId().equals(actor.userId())
                && p.tenantId().equals(actor.organizationId())).orElseThrow(()->missing("EMBEDDING_PROVIDER_UNAVAILABLE"));
        if(!digest(List.of(provider.id(),provider.providerType(),provider.baseUrl(),provider.authType())).equals(space.providerFingerprint()))
            throw new BusinessException("Embedding space changed",HttpStatus.CONFLICT,"EMBEDDING_SPACE_CHANGED");
        if(inference.listModels(provider.id()).stream().noneMatch(m->m.modelId().equals(space.modelId()))) throw missing("EMBEDDING_MODEL_UNAVAILABLE");
        return space;
    }
    private IntakeView view(KnowledgeIndexIntakeRepository.Receipt r,KnowledgeBaseApplicationApi.Actor actor) {
        return new IntakeView(r.documentId(),r.documentRevision(),r.generationId(),r.jobId(),jobs.get(actor,r.jobId()).state().name());
    }
    private static void validate(String key,String doc,long revision,String name) {
        if(key==null || !key.matches("[A-Za-z0-9_.:-]{1,100}") || revision<0 || revision>=1_000_000_000L
                || doc==null && revision!=0 || name==null || name.isBlank() || name.length()>255) throw invalid("KNOWLEDGE_INDEX_INTAKE_INVALID");
        if(doc!=null) try {if(!UUID.fromString(doc).toString().equals(doc)) throw new IllegalArgumentException();}
        catch(IllegalArgumentException e) {throw invalid("KNOWLEDGE_INDEX_INTAKE_INVALID");}
    }
    private String digest(Object value) {try{return hash(json.writeValueAsBytes(value));}catch(Exception e){throw new IllegalStateException("Intake hash unavailable");}}
    private static String hash(byte[] value){return KnowledgeIndexBuildCoordinator.hash(value);}
    private static BusinessException invalid(String code){return new BusinessException("Invalid knowledge intake",HttpStatus.BAD_REQUEST,code);}
    private static BusinessException missing(String code){return new BusinessException("Knowledge intake resource not found",HttpStatus.NOT_FOUND,code);}
    private static BusinessException conflict(){return new BusinessException("Intake key or document revision conflicts",HttpStatus.CONFLICT,"KNOWLEDGE_INDEX_INTAKE_CONFLICT");}
}
