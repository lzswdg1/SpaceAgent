package com.spaceagent.platform.knowledge.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.spaceagent.platform.inference.api.InferenceApplicationApi;
import com.spaceagent.platform.knowledge.api.*;
import com.spaceagent.platform.knowledge.domain.*;
import com.spaceagent.shared.exception.BusinessException;
import com.spaceagent.shared.id.IdGenerator;
import com.spaceagent.shared.time.TimeProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import static com.spaceagent.platform.knowledge.domain.KnowledgeBase.Permission.*;

@Service
@Transactional
public class KnowledgeIndexCatalogApplicationService implements KnowledgeIndexCatalogApplicationApi {
    private final KnowledgeIndexCatalogRepository catalog;
    private final KnowledgeBaseRepository bases;
    private final KnowledgeAccessApplicationApi access;
    private final InferenceApplicationApi inference;
    private final KnowledgeDocumentRepository documents;
    private final IdGenerator ids;
    private final TimeProvider time;
    private final ObjectMapper json;

    public KnowledgeIndexCatalogApplicationService(KnowledgeIndexCatalogRepository catalog, KnowledgeBaseRepository bases,
            KnowledgeAccessApplicationApi access, InferenceApplicationApi inference, KnowledgeDocumentRepository documents,
            IdGenerator ids, TimeProvider time, ObjectMapper json) {
        this.catalog=catalog; this.bases=bases; this.access=access; this.inference=inference;
        this.documents=documents; this.ids=ids; this.time=time; this.json=json;
    }

    @Override public SpaceView registerSpace(RegisterSpaceCommand command) {
        access.authorize(command.actor(), command.baseId(), MANAGE);
        text(command.providerId(), 100); text(command.modelId(), 160); text(command.modelRevision(), 160);
        hash(command.preprocessingHash());
        if(command.dimensions()<1 || command.dimensions()>32768) throw invalid("Invalid vector dimensions");
        var provider=inference.findProvider(command.providerId())
                .filter(v -> v.enabled() && v.hasSecret() && v.ownerId().equals(command.actor().userId())
                        && v.tenantId().equals(command.actor().organizationId()))
                .orElseThrow(() -> new BusinessException("Embedding provider is unavailable to caller", HttpStatus.NOT_FOUND,
                        "KNOWLEDGE_EMBEDDING_PROVIDER_NOT_FOUND"));
        if(inference.listModels(provider.id()).stream().noneMatch(v -> v.modelId().equals(command.modelId())))
            throw invalid("Embedding model must be registered with the provider");
        String providerHash=digest(List.of(provider.id(), provider.providerType(), provider.baseUrl(), provider.authType()));
        String fingerprint=digest(List.of(providerHash, command.modelId(), command.modelRevision(), command.dimensions(),
                "COSINE", command.preprocessingHash()));
        var space = new KnowledgeEmbeddingSpace(ids.nextId(), command.baseId(), provider.id(), command.modelId(),
                command.modelRevision(), providerHash, command.dimensions(), command.preprocessingHash(), fingerprint,
                command.actor().userId(), time.now());
        return spaceView(catalog.insertSpaceIfAbsent(space));
    }
    @Override @Transactional(readOnly=true)
    public List<SpaceView> spaces(KnowledgeBaseApplicationApi.Actor actor, String baseId, int offset, int limit) {
        access.authorize(actor,baseId,READ); page(offset,limit);
        return catalog.listSpaces(baseId,offset,limit).stream().map(KnowledgeIndexCatalogApplicationService::spaceView).toList();
    }
    @Override @Transactional(readOnly=true)
    public List<KnowledgeDocumentScope> documents(KnowledgeBaseApplicationApi.Actor actor,String baseId,int offset,int limit) {
        access.authorize(actor,baseId,READ); page(offset,limit);
        return catalog.listDocuments(baseId,offset,limit);
    }
    @Override @Transactional(readOnly=true)
    public List<GenerationView> generations(KnowledgeBaseApplicationApi.Actor actor,String baseId,String documentId,int offset,int limit) {
        access.authorize(actor,baseId,READ); page(offset,limit); scopedDocument(baseId,documentId);
        return catalog.listGenerations(baseId,documentId,offset,limit).stream().map(KnowledgeIndexCatalogApplicationService::generationView).toList();
    }
    @Override public GenerationView stage(StageGenerationCommand command) {
        access.authorize(command.actor(),command.baseId(),WRITE);
        scopedDocument(command.baseId(),command.documentId());
        var space=catalog.findSpace(command.baseId(),command.spaceId()).orElseThrow(() -> invalid("Unknown model space"));
        hash(command.contentHash()); hash(command.parserFingerprint()); hash(command.chunkingFingerprint());
        if(command.documentRevision()<0 || command.documentRevision()>1_000_000_000L) throw invalid("Invalid document revision");
        List<Object> fingerprintParts=new ArrayList<>(List.of(command.documentId(),command.contentHash(),space.id(),space.fingerprint(),
                command.parserFingerprint(),command.chunkingFingerprint()));
        if(command.documentRevision()>0) fingerprintParts.add(command.documentRevision()); // Preserve legacy descriptor identity.
        String fingerprint=digest(fingerprintParts);
        return generationView(catalog.insertGenerationIfAbsent(new KnowledgeIndexGeneration(ids.nextId(),command.baseId(),
                command.documentId(),space.id(),command.contentHash(),command.parserFingerprint(),command.chunkingFingerprint(),
                fingerprint,command.actor().userId(),time.now())));
    }

    /** Called only after the owning Knowledge application creates an owner-private legacy document. */
    public void registerLegacy(KnowledgeDocument document) {
        var now=time.now();
        String raw=hex("MD5",("knowledge-personal:"+document.ownerId()).getBytes(StandardCharsets.UTF_8));
        String id=raw.substring(0,8)+"-"+raw.substring(8,12)+"-"+raw.substring(12,16)+"-"+raw.substring(16,20)+"-"+raw.substring(20);
        KnowledgeBase base=bases.insertIfAbsent(new KnowledgeBase(id,KnowledgeBase.Scope.PERSONAL,null,document.ownerId(),
                "Personal documents","Private compatibility collection for legacy documents",KnowledgeBase.State.ACTIVE,1,now,now));
        if(base.scope()!=KnowledgeBase.Scope.PERSONAL || !document.ownerId().equals(base.ownerId()))
            throw new IllegalStateException("Legacy personal collection identity conflict");
        access.authorize(new KnowledgeBaseApplicationApi.Actor(document.ownerId(),null),id,WRITE);
        var assigned=catalog.insertScopeIfAbsent(new KnowledgeDocumentScope(document.id(),id,document.ownerId(),"LEGACY_PRIVATE",now));
        if(!assigned.baseId().equals(id)) throw new IllegalStateException("Legacy document already has a different scope");
    }

    /** Legacy APIs cannot become a backdoor into organization-scoped or archived collections. */
    @Transactional(readOnly=true)
    public boolean legacyDocumentVisible(String documentId,String actorId) {
        return documents.findById(documentId).filter(document -> legacyVisible(document,actorId)).isPresent();
    }

    @Transactional(readOnly=true)
    public boolean legacyVisible(KnowledgeDocument document,String actorId) {
        if(!Objects.equals(document.ownerId(),actorId)) return false;
        var scope=catalog.findDocumentScope(document.id());
        if(scope.isEmpty() || !"LEGACY_PRIVATE".equals(scope.get().provenance())) return false;
        return access.findAccessible(new KnowledgeBaseApplicationApi.Actor(actorId,null),scope.get().baseId(),READ)
                .filter(view -> view.base().scope()==KnowledgeBase.Scope.PERSONAL).isPresent();
    }
    private void scopedDocument(String base,String document) {
        if(documents.findById(document).isEmpty() || catalog.findDocumentScope(document).filter(v -> v.baseId().equals(base)).isEmpty())
            throw new BusinessException("Knowledge document not found in collection",HttpStatus.NOT_FOUND,"KNOWLEDGE_DOCUMENT_NOT_FOUND");
    }
    private String digest(Object value) {
        try { return hex("SHA-256",json.writeValueAsBytes(value)); }
        catch(java.io.IOException error){throw new IllegalStateException("Unable to hash index metadata",error);}
    }
    private static String hex(String algorithm,byte[] value) {
        try{return HexFormat.of().formatHex(MessageDigest.getInstance(algorithm).digest(value));}
        catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }
    private static SpaceView spaceView(KnowledgeEmbeddingSpace space) {return new SpaceView(space,"REGISTERED_UNVERIFIED","COSINE");}
    private static GenerationView generationView(KnowledgeIndexGeneration generation) {return new GenerationView(generation,"STAGED",false);}
    private static void page(int offset,int limit){if(offset<0 || offset>100000 || limit<1 || limit>100)throw invalid("Invalid page bounds");}
    private static void text(String value,int max){if(value==null || value.isBlank() || value.length()>max)throw invalid("Invalid model metadata");}
    private static void hash(String value){if(value==null || !value.matches("[0-9a-f]{64}"))throw invalid("Invalid metadata hash");}
    private static BusinessException invalid(String message){return new BusinessException(message,HttpStatus.BAD_REQUEST,"KNOWLEDGE_INDEX_METADATA_INVALID");}
}
