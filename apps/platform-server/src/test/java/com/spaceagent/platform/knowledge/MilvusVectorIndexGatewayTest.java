package com.spaceagent.platform.knowledge;

import com.spaceagent.platform.knowledge.domain.VectorIndexGateway.*;
import com.spaceagent.platform.knowledge.infrastructure.MilvusVectorIndexGateway;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.common.ConsistencyLevel;
import io.milvus.v2.service.vector.request.*;
import io.milvus.v2.service.vector.response.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;

class MilvusVectorIndexGatewayTest {
    private final Space space=new Space("space-one","a".repeat(64),2);
    private final Scope scope=new Scope("org:one","base-one",List.of("generation-one"));
    private final MilvusClientV2 client=mock(MilvusClientV2.class);
    private final MilvusVectorIndexGateway gateway=new MilvusVectorIndexGateway(client,"fixture");

    @Test void existingCollectionMustMatchFingerprintAndDimensions() {
        var request=new java.util.concurrent.atomic.AtomicReference<io.milvus.v2.service.collection.request.CreateCollectionReq>();
        when(client.hasCollection(any())).thenReturn(false,true);
        doAnswer(invocation->{request.set(invocation.getArgument(0));return null;}).when(client).createCollection(any());
        when(client.describeCollection(any())).thenAnswer(invocation->
                io.milvus.v2.service.collection.response.DescribeCollectionResp.builder()
                        .collectionSchema(request.get().getCollectionSchema()).description(request.get().getDescription())
                        .autoID(false).enableDynamicField(false).build());
        gateway.ensureSpace(space);
        assertThat(request.get().getConsistencyLevel()).isEqualTo(ConsistencyLevel.STRONG);
        assertThatThrownBy(()->gateway.ensureSpace(new Space(space.id(),"b".repeat(64),space.dimensions())))
                .isInstanceOf(IllegalStateException.class).hasMessage("MILVUS_SPACE_SCHEMA_MISMATCH");
    }

    @Test void invalidScopeOrVectorNeverReachesSdk() {
        assertThatThrownBy(()->new Scope("org\" or true","base",List.of("g"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new Scope("org","base",List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->gateway.search(space,scope,List.of(1.0),5)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->gateway.search(space,scope,List.of(Double.MAX_VALUE,1.0),5)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->gateway.search(space,scope,List.of(0.0,0.0),5)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->gateway.deleteGeneration(space,scope,"unrelated")).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(client);
    }

    @Test void storedPayloadEvidenceDetectsReplayAndChangedContent() {
        Map<String,Map<String,Object>> stored=new HashMap<>();
        when(client.query(any())).thenAnswer(invocation->{
            QueryReq request=invocation.getArgument(0);
            assertThat(request.getConsistencyLevel()).isEqualTo(ConsistencyLevel.STRONG);
            return QueryResp.builder().queryResults(request.getIds().stream().map(Object::toString)
                    .filter(stored::containsKey).map(id->QueryResp.QueryResult.builder().entity(stored.get(id)).build()).toList()).build();
        });
        when(client.upsert(any())).thenAnswer(invocation->{
            UpsertReq request=invocation.getArgument(0);
            request.getData().forEach(row->{String id=row.get("id").getAsString();stored.put(id,Map.of("id",id,"payload_hash",row.get("payload_hash").getAsString()));});
            return UpsertResp.builder().build();
        });
        Entry entry=entry("b");
        gateway.upsertBatch(space,List.of(entry));
        assertThat(gateway.verifyBatch(space,List.of(entry))).isTrue();
        gateway.upsertBatch(space,List.of(entry));
        assertThat(stored).hasSize(1);
        assertThat(gateway.verifyBatch(space,List.of(entry("c")))).isFalse();
        assertThatThrownBy(()->gateway.upsertBatch(space,List.of(entry("c"))))
                .isInstanceOf(IllegalStateException.class).hasMessage("MILVUS_IMMUTABLE_ENTRY_CONFLICT");
    }

    @Test void searchCarriesExplicitScopeAndGenerationFilterWithStrongConsistency() {
        when(client.search(any())).thenReturn(SearchResp.builder().searchResults(List.of(List.of(
                SearchResp.SearchResult.builder().id("pk").score(0.9f).entity(Map.of("document_id","doc","generation_id","generation-one",
                        "chunk_id","chunk","content_hash","b".repeat(64))).build()))).build());
        assertThat(gateway.search(space,scope,List.of(1.0,0.0),5)).singleElement()
                .satisfies(hit->assertThat(hit.documentId()).isEqualTo("doc"));
        verify(client).search(argThat(request->request.getFilter().contains("scope_key == \"org:one\"")
                && request.getFilter().contains("base_id == \"base-one\"")
                && request.getFilter().contains("generation_id in [\"generation-one\"]")
                && request.getConsistencyLevel()==ConsistencyLevel.STRONG && request.getTopK()==5));
    }

    @Test void deleteUsesFullScopeAndRequiresAnExplicitPostconditionCheck() {
        gateway.deleteGeneration(space,scope,"generation-one");
        verify(client).delete(argThat(request->request.getFilter().contains("scope_key == \"org:one\"")
                && request.getFilter().contains("generation_id == \"generation-one\"")));
        when(client.query(any())).thenReturn(QueryResp.builder().queryResults(List.of()).build());
        assertThat(gateway.generationDeleted(space,scope,"generation-one")).isTrue();
        verify(client).query(argThat(request->request.getLimit()==1 && request.getConsistencyLevel()==ConsistencyLevel.STRONG));
    }
    private Entry entry(String hash) {
        return new Entry("org:one","base-one","doc","generation-one","chunk",hash.repeat(64),List.of(1.0,0.0));
    }
}
