package com.spaceagent.platform.knowledge.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.spaceagent.platform.knowledge.api.*;
import com.spaceagent.platform.knowledge.domain.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.charset.CodingErrorAction;
import java.security.MessageDigest;
import java.util.*;
import static com.spaceagent.platform.knowledge.domain.KnowledgeIndexJob.*;

/** Internal build path. Source import and scheduling must not bypass this fenced, verified pipeline. */
@Service
@ConditionalOnProperty(prefix="platform",name="persistence",havingValue="postgres")
public class KnowledgeIndexBuildCoordinator {
    public static final String PARSER_HASH=hash("UTF8_V1".getBytes(StandardCharsets.UTF_8));
    public static final String BINARY_PARSER_HASH=hash("TIKA_3.3.0_XHTML_V1".getBytes(StandardCharsets.UTF_8));
    public static final String CHUNKER_HASH=hash("FIXED_CHARACTER:800:100".getBytes(StandardCharsets.UTF_8));
    private final KnowledgeIndexJobRepository jobs;
    private final KnowledgeIndexCatalogRepository catalog;
    private final KnowledgeIndexPublicationRepository publications;
    private final KnowledgeIndexObjectStore objects;
    private final KnowledgeAccessApplicationApi access;
    private final KnowledgeEmbeddingStage embeddings;
    private final KnowledgeIndexActivationService activation;
    private final ObjectProvider<VectorIndexGateway> vectors;
    private final ObjectMapper json;
    private KnowledgeDocumentParsingGateway parsing;
    @org.springframework.beans.factory.annotation.Autowired public void parsing(KnowledgeDocumentParsingGateway parsing){this.parsing=parsing;}
    private KnowledgeDocumentChunker chunker;
    @org.springframework.beans.factory.annotation.Autowired public void chunker(KnowledgeDocumentChunker chunker){this.chunker=chunker;}
    public KnowledgeIndexBuildCoordinator(KnowledgeIndexJobRepository jobs,KnowledgeIndexCatalogRepository catalog,
            KnowledgeIndexPublicationRepository publications,KnowledgeIndexObjectStore objects,KnowledgeAccessApplicationApi access,
            KnowledgeEmbeddingStage embeddings,KnowledgeIndexActivationService activation,ObjectProvider<VectorIndexGateway> vectors,ObjectMapper json) {
        this.jobs=jobs;this.catalog=catalog;this.publications=publications;this.objects=objects;this.access=access;
        this.embeddings=embeddings;this.activation=activation;this.vectors=vectors;this.json=json;
    }
    @Transactional(propagation=Propagation.NOT_SUPPORTED)
    public KnowledgeIndexJob execute(Lease lease) {
        VectorIndexGateway gateway=Objects.requireNonNull(vectors.getIfAvailable(),"Vector index gateway is not configured");
        var job=jobs.find(lease.jobId()).orElseThrow();
        try {
            var source=publications.source(job.input().generationId()).orElseThrow();
            var generation=catalog.findGeneration(job.input().baseId(),job.input().generationId()).orElseThrow();
            var modelSpace=catalog.findSpace(job.input().baseId(),generation.spaceId()).orElseThrow();
            requireCurrent(job,source);
            gateway.validateSpace(new VectorIndexGateway.Space(modelSpace.id(),modelSpace.fingerprint(),modelSpace.dimensions(),job.input().storageSchema()));
            prepare(lease,job,source,generation);
            job=jobs.find(lease.jobId()).orElseThrow();
            if(job.progress().stage()==Stage.EMBEDDING) {
                job=embeddings.execute(lease,publications.chunks(generation.id()).stream()
                        .map(c->new KnowledgeEmbeddingStage.Chunk(c.id(),c.content())).toList());
                if(job.progress().state()!=State.RUNNING) return job;
            }
            var allowed=access.authorize(actor(job),job.input().baseId(),KnowledgeBase.Permission.WRITE).base();
            var space=new VectorIndexGateway.Space(modelSpace.id(),modelSpace.fingerprint(),modelSpace.dimensions(),job.input().storageSchema());
            var scope=new VectorIndexGateway.Scope(allowed.scope()==KnowledgeBase.Scope.PERSONAL?"user:"+allowed.ownerId():"org:"+allowed.organizationId(),allowed.id(),List.of(generation.id()));
            boolean resumedAtActivation=job.progress().stage()==Stage.READY_TO_ACTIVATE;
            gateway.ensureSpace(space);
            for(Stage stage:List.of(Stage.WRITING,Stage.VERIFYING)) {
                job=jobs.find(lease.jobId()).orElseThrow();
                if(job.progress().stage()!=stage) continue;
                var completed=jobs.batches(job.id(),Stage.EMBEDDING,0,512);
                if(completed.isEmpty() || completed.stream().anyMatch(b->b.state()!=BatchState.COMPLETED)) throw new IllegalStateException("Missing embedding manifest");
                jobs.planStage(lease,new Manifest(job.id(),stage,hash(bytes(completed.stream().map(Batch::outputHash).toList())),completed.size()));
                Set<String> seen=new HashSet<>(); var chunks=publications.chunks(generation.id());
                Map<String,KnowledgeIndexPublicationRepository.Chunk> authoritative=new HashMap<>();chunks.forEach(c->authoritative.put(c.id(),c));
                for(var embedded:completed) {
                    requireCurrent(job,source);
                    if(!jobs.renew(lease,90)) throw new IllegalStateException("Vector lease expired");
                    var output=json.readValue(objects.read(embedded.outputReference(),embedded.outputHash()),KnowledgeEmbeddedBatch.class);
                    if(!output.spaceId().equals(space.id()) || !output.spaceFingerprint().equals(space.fingerprint()) || output.chunks().size()!=embedded.itemCount())
                        throw new IllegalStateException("Embedding output is from another space");
                    List<VectorIndexGateway.Entry> entries=new ArrayList<>();
                    for(var chunk:output.chunks()) {
                        var expected=authoritative.get(chunk.chunkId());
                        if(expected==null || !expected.contentHash().equals(chunk.contentHash()) || !seen.add(chunk.chunkId()))
                            throw new IllegalStateException("Embedding output is not the authoritative chunk manifest");
                        modelSpace.validateVector(chunk.vector());
                        entries.add(new VectorIndexGateway.Entry(scope.key(),scope.baseId(),source.documentId(),generation.id(),chunk.chunkId(),chunk.contentHash(),chunk.vector(),expected.content()));
                    }
                    var receipt=jobs.beginBatch(lease,stage,embedded.ordinal(),embedded.outputHash(),embedded.itemCount());
                    if(receipt.state()==BatchState.COMPLETED) continue;
                    if(stage==Stage.WRITING) gateway.upsertBatch(space,entries);
                    if(!gateway.verifyBatch(space,entries)) throw new IllegalStateException("Vector batch verification failed");
                    if(stage==Stage.VERIFYING) {
                        var probe=gateway.search(space,scope,entries.getFirst().vector(),1);
                        if(probe.isEmpty() || probe.stream().anyMatch(m->!m.generationId().equals(generation.id()) || !m.documentId().equals(source.documentId())
                                || !authoritative.containsKey(m.chunkId()) || !authoritative.get(m.chunkId()).contentHash().equals(m.contentHash())))
                            throw new IllegalStateException("Strong scoped search visibility verification failed");
                    }
                    jobs.completeBatch(lease,new Batch(job.id(),stage,embedded.ordinal(),embedded.outputHash(),embedded.itemCount(),BatchState.COMPLETED,
                            embedded.outputReference(),embedded.outputHash()));
                }
                if(seen.size()!=chunks.size()) throw new IllegalStateException("Vector manifest does not cover every authoritative chunk");
                jobs.advance(lease,stage);
            }
            job=jobs.find(lease.jobId()).orElseThrow();
            if(job.progress().stage()==Stage.READY_TO_ACTIVATE) {
                requireCurrent(job,source);
                // After a restart, historical verification receipts are not fresh storage-health evidence.
                if(resumedAtActivation) verifyActivation(lease,gateway,space,scope,source);
                activation.activate(lease);
            }
            return jobs.find(lease.jobId()).orElseThrow();
        } catch(Exception e) {
            // Never replay an uncertain paid/model/vector effect. A lost fence cannot overwrite a newer cancellation.
            if(jobs.renew(lease,90)) return jobs.fail(lease,Failure.UNKNOWN,"INDEX_BUILD_RECONCILIATION_REQUIRED");
            return jobs.find(lease.jobId()).orElseThrow();
        }
    }
    private void verifyActivation(Lease lease,VectorIndexGateway gateway,VectorIndexGateway.Space space,
            VectorIndexGateway.Scope scope,KnowledgeIndexPublicationRepository.Source source) throws Exception {
        var chunks=publications.chunks(source.generationId()); Map<String,String> expected=new HashMap<>();chunks.forEach(c->expected.put(c.id(),c.contentHash()));
        Map<String,String> texts=new HashMap<>();chunks.forEach(c->texts.put(c.id(),c.content()));
        Set<String> seen=new HashSet<>();
        for(var batch:jobs.batches(lease.jobId(),Stage.EMBEDDING,0,512)) {
            if(batch.state()!=BatchState.COMPLETED || !jobs.renew(lease,90)) throw new IllegalStateException("Activation lease or checkpoint changed");
            var output=json.readValue(objects.read(batch.outputReference(),batch.outputHash()),KnowledgeEmbeddedBatch.class);
            if(!space.id().equals(output.spaceId()) || !space.fingerprint().equals(output.spaceFingerprint()) || batch.itemCount()!=output.chunks().size())
                throw new IllegalStateException("Activation checkpoint space changed");
            List<VectorIndexGateway.Entry> entries=new ArrayList<>();
            for(var c:output.chunks()) {
                if(!Objects.equals(expected.get(c.chunkId()),c.contentHash()) || !seen.add(c.chunkId())) throw new IllegalStateException("Activation manifest changed");
                entries.add(new VectorIndexGateway.Entry(scope.key(),scope.baseId(),source.documentId(),source.generationId(),c.chunkId(),c.contentHash(),c.vector(),texts.get(c.chunkId())));
            }
            var probe=gateway.search(space,scope,entries.getFirst().vector(),1);
            if(!gateway.verifyBatch(space,entries) || probe.isEmpty() || probe.stream().anyMatch(m->!source.generationId().equals(m.generationId())
                    || !source.documentId().equals(m.documentId()) || !Objects.equals(expected.get(m.chunkId()),m.contentHash())))
                throw new IllegalStateException("Activation storage proof is no longer valid");
        }
        if(seen.size()!=chunks.size() || seen.isEmpty()) throw new IllegalStateException("Incomplete activation manifest");
    }
    private void prepare(Lease lease,KnowledgeIndexJob job,KnowledgeIndexPublicationRepository.Source source,KnowledgeIndexGeneration generation) throws Exception {
        if(job.progress().stage()!=Stage.PARSING && job.progress().stage()!=Stage.CHUNKING) return;
        boolean binary=!source.mediaType().equals("text/plain");
        if(!generation.parserFingerprint().equals(binary?BINARY_PARSER_HASH:PARSER_HASH) || !generation.chunkingFingerprint().equals(source.policy().fingerprint()))
            throw new IllegalStateException("Source parser/chunker snapshot is unsupported");
        String text;
        if(job.progress().stage()==Stage.PARSING) {
            byte[] raw=objects.read(source.sourceReference(),source.contentHash());
            KnowledgeDocumentParsingGateway.Parsed parsed;
            if(binary)parsed=Objects.requireNonNull(parsing,"Isolated parser is not configured").parse(raw,source.mediaType(),source.charset());
            else {String plain=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(raw)).toString();
                parsed=new KnowledgeDocumentParsingGateway.Parsed(plain,List.of(new KnowledgeDocumentParsingGateway.Block(0,plain.length(),null,List.of())),"UTF8_V1",List.of());}
            text=parsed.text();
            jobs.planStage(lease,new Manifest(job.id(),Stage.PARSING,source.contentHash(),1));
            byte[] metadata=bytes(new TreeMap<>(Map.of("parser",parsed.parser(),"blocks",parsed.blocks(),"warnings",parsed.warnings())));
            String metadataHash=hash(metadata),metadataRef=objects.put(job.id(),metadataHash,metadata);
            publications.saveParsingMetadata(lease,metadataRef,metadataHash);
            checkpoint(lease,Stage.PARSING,0,source.contentHash(),1,text.getBytes(StandardCharsets.UTF_8));
            jobs.advance(lease,Stage.PARSING);
        } else {var checkpoint=jobs.batches(job.id(),Stage.PARSING,0,1).getFirst();text=new String(objects.read(checkpoint.outputReference(),checkpoint.outputHash()),StandardCharsets.UTF_8);}
        var currentSource=publications.source(generation.id()).orElseThrow();List<KnowledgeDocumentParsingGateway.Block> blocks=List.of();
        if(currentSource.parseMetadataReference()!=null){var evidence=json.readTree(objects.read(currentSource.parseMetadataReference(),currentSource.parseMetadataHash()));
            blocks=json.convertValue(evidence.path("blocks"),new com.fasterxml.jackson.core.type.TypeReference<>(){});}
        List<KnowledgeDocumentChunker.Segment> pieces;
        if(source.policy().equals(KnowledgeProcessingPolicy.legacy()) && !binary)pieces=new KnowledgeChunkingService(800,100).chunk(text).stream().map(p->new KnowledgeDocumentChunker.Segment(p,Map.of())).toList();
        else if(chunker!=null)pieces=chunker.split(text,blocks,source.policy());
        else throw new IllegalStateException("Chunking adapter is unavailable");
        List<KnowledgeIndexPublicationRepository.Chunk> chunks=new ArrayList<>();
        for(int i=0;i<pieces.size();i++) chunks.add(new KnowledgeIndexPublicationRepository.Chunk(
                UUID.nameUUIDFromBytes((generation.id()+":"+i).getBytes(StandardCharsets.UTF_8)).toString(),i,pieces.get(i).text(),hash(pieces.get(i).text().getBytes(StandardCharsets.UTF_8)),pieces.get(i).metadata()));
        publications.saveChunks(lease,chunks);
        jobs.planStage(lease,new Manifest(job.id(),Stage.CHUNKING,source.contentHash(),(chunks.size()+63)/64));
        for(int start=0;start<chunks.size();start+=64) {
            var batch=chunks.subList(start,Math.min(start+64,chunks.size()));byte[] value=chunkBytes(batch);
            checkpoint(lease,Stage.CHUNKING,start/64,hash(value),batch.size(),value);
        }
        jobs.advance(lease,Stage.CHUNKING);
    }
    private void checkpoint(Lease lease,Stage stage,int ordinal,String inputHash,int count,byte[] value) {
        if(!jobs.renew(lease,90)) throw new IllegalStateException("Index lease expired");
        var batch=jobs.beginBatch(lease,stage,ordinal,inputHash,count);
        if(batch.state()==BatchState.COMPLETED) {objects.read(batch.outputReference(),batch.outputHash());return;}
        String digest=hash(value),ref=objects.put(lease.jobId(),digest,value);
        jobs.completeBatch(lease,new Batch(lease.jobId(),stage,ordinal,inputHash,count,BatchState.COMPLETED,ref,digest));
    }
    private void requireCurrent(KnowledgeIndexJob job,KnowledgeIndexPublicationRepository.Source source) {
        access.authorize(actor(job),job.input().baseId(),KnowledgeBase.Permission.WRITE);
        var head=publications.head(source.baseId(),source.documentId()).orElseThrow();
        if(!source.baseId().equals(job.input().baseId()) || !head.state().equals("ACTIVE") || head.revision()!=source.documentRevision()
                || !head.contentHash().equals(source.contentHash())) throw new IllegalStateException("Source changed or was deleted");
    }
    private static KnowledgeBaseApplicationApi.Actor actor(KnowledgeIndexJob job) {return new KnowledgeBaseApplicationApi.Actor(job.input().requestedBy(),job.input().organizationId());}
    private byte[] bytes(Object value) {try{return json.writeValueAsBytes(value);}catch(Exception e){throw new IllegalStateException("Index checkpoint serialization failed");}}
    private byte[] chunkBytes(List<KnowledgeIndexPublicationRepository.Chunk> chunks){
        List<Map<String,Object>> values=new ArrayList<>();
        for(var c:chunks){Map<String,Object> item=new LinkedHashMap<>();item.put("id",c.id());item.put("ordinal",c.ordinal());item.put("content",c.content());item.put("contentHash",c.contentHash());
            if(!c.metadata().isEmpty())item.put("metadata",new TreeMap<>(c.metadata()));values.add(item);}
        return bytes(values);
    }
    public static String hash(byte[] value) {try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));}catch(Exception e){throw new IllegalStateException(e);}}
}
