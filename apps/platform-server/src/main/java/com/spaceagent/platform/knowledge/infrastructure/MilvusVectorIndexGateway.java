package com.spaceagent.platform.knowledge.infrastructure;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.spaceagent.platform.knowledge.domain.VectorIndexGateway;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.common.*;
import io.milvus.v2.service.collection.request.*;
import io.milvus.v2.service.vector.request.*;
import io.milvus.v2.service.vector.request.data.FloatVec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Milvus SDK is confined to this adapter. It does not grant access or publish a generation. */
public class MilvusVectorIndexGateway implements VectorIndexGateway {
    private static final List<String> OUTPUT = List.of("document_id","generation_id","chunk_id","content_hash");
    private final MilvusClientV2 client;
    private final String prefix;

    public MilvusVectorIndexGateway(MilvusClientV2 client,String prefix) {
        if(prefix==null || !prefix.matches("[a-z][a-z0-9_]{0,31}"))throw new IllegalArgumentException("Invalid Milvus collection prefix");
        this.client=client;this.prefix=prefix;
    }
    public String collectionName(Space space) {return prefix+"_"+space.schema()+"_"+hash(space.id());}
    private static String description(Space space){return "spaceagent-"+space.schema().replace('_','-')+":"+space.fingerprint();}

    @Override public void ensureSpace(Space space) {
        String collection=collectionName(space);
        if(!client.hasCollection(HasCollectionReq.builder().collectionName(collection).build())) {
            var schema=CreateCollectionReq.CollectionSchema.builder().build();
            schema.setEnableDynamicField(false);
            schema.addField(AddFieldReq.builder().fieldName("id").dataType(DataType.VarChar).maxLength(64)
                    .isPrimaryKey(true).autoID(false).build());
            for(String field:List.of("scope_key","base_id","document_id","generation_id","chunk_id","content_hash","payload_hash")) {
                schema.addField(AddFieldReq.builder().fieldName(field).dataType(DataType.VarChar).maxLength(96).build());
            }
            schema.addField(AddFieldReq.builder().fieldName("vector").dataType(DataType.FloatVector).dimension(space.dimensions()).build());
            List<IndexParam> indexes=new ArrayList<>(List.of(IndexParam.builder().fieldName("vector").indexType(IndexParam.IndexType.HNSW)
                    .metricType(IndexParam.MetricType.COSINE).extraParams(Map.of("M",16,"efConstruction",128)).build()));
            if(space.schema().equals("hybrid_v2")){
                schema.addField(AddFieldReq.builder().fieldName("text").dataType(DataType.VarChar).maxLength(65535).enableAnalyzer(true)
                        .analyzerParams(Map.of("type","chinese")).enableMatch(true).build());
                schema.addField(AddFieldReq.builder().fieldName("sparse").dataType(DataType.SparseFloatVector).build());
                schema.addFunction(CreateCollectionReq.Function.builder().name("text_bm25").functionType(io.milvus.common.clientenum.FunctionType.BM25)
                        .inputFieldNames(List.of("text")).outputFieldNames(List.of("sparse")).build());
                indexes.add(IndexParam.builder().fieldName("sparse").indexType(IndexParam.IndexType.SPARSE_INVERTED_INDEX).metricType(IndexParam.MetricType.BM25).build());
            }
            try {
                client.createCollection(CreateCollectionReq.builder().collectionName(collection)
                        .description(description(space)).collectionSchema(schema)
                        .consistencyLevel(ConsistencyLevel.STRONG)
                        .indexParams(indexes)
                        .build());
            } catch(RuntimeException creationFailure) {
                if(!client.hasCollection(HasCollectionReq.builder().collectionName(collection).build()))throw creationFailure;
                // A concurrent creator may have won; verify its exact schema below.
            }
        }
        var actual=client.describeCollection(DescribeCollectionReq.builder().collectionName(collection).build());
        if(actual.getCollectionSchema()==null)throw new IllegalStateException("MILVUS_SPACE_SCHEMA_MISMATCH");
        var vector=actual.getCollectionSchema().getField("vector");
        if(vector==null || vector.getDataType()!=DataType.FloatVector || !Objects.equals(vector.getDimension(),space.dimensions())
                || !Objects.equals(actual.getDescription(),description(space))
                || Boolean.TRUE.equals(actual.getAutoID()) || Boolean.TRUE.equals(actual.getEnableDynamicField())) {
            throw new IllegalStateException("MILVUS_SPACE_SCHEMA_MISMATCH");
        }
        var primary=actual.getCollectionSchema().getField("id");
        if(primary==null || primary.getDataType()!=DataType.VarChar || !Boolean.TRUE.equals(primary.getIsPrimaryKey())
                || primary.getMaxLength()==null || primary.getMaxLength()<64)throw new IllegalStateException("MILVUS_SPACE_SCHEMA_MISMATCH");
        for(String field:List.of("scope_key","base_id","document_id","generation_id","chunk_id","content_hash","payload_hash")) {
            var value=actual.getCollectionSchema().getField(field);
            if(value==null || value.getDataType()!=DataType.VarChar || value.getMaxLength()==null || value.getMaxLength()<96)
                throw new IllegalStateException("MILVUS_SPACE_SCHEMA_MISMATCH");
        }
        if(space.schema().equals("hybrid_v2")){
            var text=actual.getCollectionSchema().getField("text");var sparse=actual.getCollectionSchema().getField("sparse");
            if(text==null || text.getDataType()!=DataType.VarChar || !Boolean.TRUE.equals(text.getEnableAnalyzer())
                || text.getAnalyzerParams()==null || !"chinese".equals(text.getAnalyzerParams().get("type"))
                || sparse==null || sparse.getDataType()!=DataType.SparseFloatVector
                || actual.getCollectionSchema().getFunctionList().stream().noneMatch(f->f.getFunctionType()==io.milvus.common.clientenum.FunctionType.BM25
                    && List.of("text").equals(f.getInputFieldNames()) && List.of("sparse").equals(f.getOutputFieldNames())))
                throw new IllegalStateException("MILVUS_HYBRID_SCHEMA_MISMATCH");
        }
        client.loadCollection(LoadCollectionReq.builder().collectionName(collection).build());
    }

    @Override public void upsertBatch(Space space,List<Entry> entries) {
        List<JsonObject> rows=rows(space,entries);
        var existing=existing(space,rows);
        for(JsonObject row:rows) {
            String oldHash=existing.get(row.get("id").getAsString());
            if(oldHash!=null && !oldHash.equals(row.get("payload_hash").getAsString()))
                throw new IllegalStateException("MILVUS_IMMUTABLE_ENTRY_CONFLICT");
        }
        // The indexing job must fence immutable generation payloads across workers. This check is not a distributed CAS.
        client.upsert(UpsertReq.builder().collectionName(collectionName(space)).data(rows).build());
    }
    @Override public boolean verifyBatch(Space space,List<Entry> entries) {
        List<JsonObject> rows=rows(space,entries);
        Map<String,String> existing=existing(space,rows);
        return rows.stream().allMatch(row->row.get("payload_hash").getAsString().equals(existing.get(row.get("id").getAsString())));
    }
    @Override public List<Match> search(Space space,Scope scope,List<Double> queryVector,int topK) {
        if(topK<1 || topK>100)throw new IllegalArgumentException("Invalid vector topK");
        List<Float> vector=vector(space,queryVector);
        var response=client.search(SearchReq.builder().collectionName(collectionName(space)).annsField("vector")
                .metricType(IndexParam.MetricType.COSINE)
                .data(List.of(new FloatVec(vector))).topK(topK).filter(filter(scope))
                .consistencyLevel(ConsistencyLevel.STRONG).outputFields(OUTPUT)
                .searchParams(Map.of("ef",Math.max(64,topK))).build());
        if(response.getSearchResults().size()!=1)throw new IllegalStateException("MILVUS_SEARCH_RESPONSE_INVALID");
        return response.getSearchResults().get(0).stream().map(hit->{
            Map<String,Object> fields=hit.getEntity();
            if(hit.getScore()==null || !Float.isFinite(hit.getScore()))throw new IllegalStateException("MILVUS_SCORE_INVALID");
            return new Match(field(fields,"document_id"),field(fields,"generation_id"),field(fields,"chunk_id"),
                    field(fields,"content_hash"),hit.getScore());
        }).toList();
    }
    @Override public void deleteGeneration(Space space,Scope scope,String generationId) {
        requireGeneration(scope,generationId);
        client.delete(DeleteReq.builder().collectionName(collectionName(space))
                .filter(filter(scope)+" and generation_id == \""+generationId+"\"").build());
    }
    @Override public List<Match> lexicalSearch(Space space,Scope scope,String query,int topK){
        if(!space.schema().equals("hybrid_v2"))return List.of();
        if(query==null || query.isBlank() || query.length()>4000 || topK<1 || topK>100)throw new IllegalArgumentException("Invalid lexical query");
        var result=client.search(SearchReq.builder().collectionName(collectionName(space)).annsField("sparse").metricType(IndexParam.MetricType.BM25)
                .data(List.of(new io.milvus.v2.service.vector.request.data.EmbeddedText(query))).topK(topK).filter(filter(scope))
                .consistencyLevel(ConsistencyLevel.STRONG).outputFields(OUTPUT).build());
        if(result.getSearchResults().size()!=1)throw new IllegalStateException("MILVUS_LEXICAL_RESPONSE_INVALID");
        return result.getSearchResults().getFirst().stream().map(h->{
            if(h.getScore()==null || !Float.isFinite(h.getScore()))throw new IllegalStateException("MILVUS_SCORE_INVALID");
            return new Match(field(h.getEntity(),"document_id"),field(h.getEntity(),"generation_id"),
                field(h.getEntity(),"chunk_id"),field(h.getEntity(),"content_hash"),h.getScore());}).toList();
    }
    @Override public boolean spaceExists(Space space) {
        return client.hasCollection(io.milvus.v2.service.collection.request.HasCollectionReq.builder().collectionName(collectionName(space)).build());
    }
    @Override public boolean generationDeleted(Space space,Scope scope,String generationId) {
        requireGeneration(scope,generationId);
        return client.query(QueryReq.builder().collectionName(collectionName(space))
                .filter(filter(scope)+" and generation_id == \""+generationId+"\"")
                .consistencyLevel(ConsistencyLevel.STRONG).outputFields(List.of("id")).limit(1).build()).getQueryResults().isEmpty();
    }
    private Map<String,String> existing(Space space,List<JsonObject> rows) {
        var ids=rows.stream().map(row->(Object)row.get("id").getAsString()).toList();
        var response=client.query(QueryReq.builder().collectionName(collectionName(space)).ids(ids)
                .consistencyLevel(ConsistencyLevel.STRONG).outputFields(List.of("id","payload_hash")).limit(rows.size()).build());
        Map<String,String> values=new HashMap<>();
        response.getQueryResults().forEach(row->values.put(field(row.getEntity(),"id"),field(row.getEntity(),"payload_hash")));
        return values;
    }
    private List<JsonObject> rows(Space space,List<Entry> entries) {
        if(entries==null || entries.isEmpty() || entries.size()>64 || (long)entries.size()*space.dimensions()>262144)
            throw new IllegalArgumentException("Vector batch exceeds bounds");
        List<JsonObject> rows=new ArrayList<>(); Set<String> unique=new HashSet<>();
        for(Entry entry:entries) {
            JsonObject row=new JsonObject();
            row.addProperty("scope_key",entry.scopeKey()); row.addProperty("base_id",entry.baseId());
            row.addProperty("document_id",entry.documentId());row.addProperty("generation_id",entry.generationId());
            row.addProperty("chunk_id",entry.chunkId());row.addProperty("content_hash",entry.contentHash());
            JsonArray vector=new JsonArray();vector(space,entry.vector()).forEach(vector::add);row.add("vector",vector);
            if(space.schema().equals("hybrid_v2")){
                if(entry.text()==null || entry.text().getBytes(StandardCharsets.UTF_8).length>65535 || !hash(entry.text()).equals(entry.contentHash()))throw new IllegalArgumentException("Hybrid text must match authoritative chunk hash");
                row.addProperty("text",entry.text());
            }
            String payload=hash(row.toString());
            String id=hash(String.join("\n",entry.scopeKey(),entry.baseId(),entry.generationId(),entry.chunkId()));
            if(!unique.add(id))throw new IllegalArgumentException("Duplicate vector entry");
            row.addProperty("id",id); row.addProperty("payload_hash",payload);rows.add(row);
        }
        return List.copyOf(rows);
    }
    private static List<Float> vector(Space space,List<Double> values) {
        if(values==null || values.size()!=space.dimensions())throw new IllegalArgumentException("Vector dimensions differ from space");
        List<Float> result=new ArrayList<>();
        for(Double value:values) {
            if(value==null || !Double.isFinite(value) || !Float.isFinite(value.floatValue()))throw new IllegalArgumentException("Vector contains invalid component");
            result.add(value.floatValue());
        }
        if(result.stream().allMatch(v->v==0.0f))throw new IllegalArgumentException("Cosine vector cannot be zero");
        return result;
    }
    private static String filter(Scope scope) {
        return "scope_key == \""+scope.key()+"\" and base_id == \""+scope.baseId()+"\" and generation_id in ["
                +scope.generationIds().stream().map(id->"\""+id+"\"").collect(java.util.stream.Collectors.joining(","))+"]";
    }
    private static void requireGeneration(Scope scope,String generation){
        if(!scope.generationIds().contains(generation))throw new IllegalArgumentException("Generation is outside the allowed scope");
    }
    private static String field(Map<String,Object> fields,String key) {
        Object value=fields.get(key);if(!(value instanceof String text) || text.isBlank())throw new IllegalStateException("MILVUS_METADATA_INVALID");return text;
    }
    private static String hash(String text) {
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));}
        catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }
}
