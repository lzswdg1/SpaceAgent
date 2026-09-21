package com.spaceagent.platform.inference;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.spaceagent.platform.inference.domain.*;
import com.spaceagent.platform.inference.infrastructure.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

class OpenAiCompatibleEmbeddingBatchExecutorTest {
    HttpServer server; int status; String body; AtomicInteger requests; String authorization; String submitted;
    ModelProvider provider; OpenAiCompatibleEmbeddingBatchExecutor executor;
    @BeforeEach void setup() throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0); requests=new AtomicInteger(); status=200;
        server.createContext("/v1/embeddings",exchange->{
            requests.incrementAndGet(); authorization=exchange.getRequestHeaders().getFirst("Authorization");
            submitted=new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8);
            if(status==302) exchange.getResponseHeaders().set("Location","http://127.0.0.1:"+server.getAddress().getPort()+"/v1/embeddings");
            byte[] bytes=body.getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(status,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
        }); server.start();
        var properties=new InferenceProperties(); properties.setAllowLocalProviderHosts(true);
        executor=new OpenAiCompatibleEmbeddingBatchExecutor(EmbeddingBatchApplicationServiceTest.CIPHER,new ObjectMapper(),new AllowlistedModelProviderEndpointPolicy(properties));
        provider=new ModelProvider("provider","org","owner","fixture","openai-compatible","http://127.0.0.1:"+server.getAddress().getPort()+"/v1",
                EmbeddingBatchApplicationServiceTest.CIPHER.encrypt("fixture-secret"),"bearer",true,false,Instant.now(),Instant.now());
    }
    @AfterEach void close() {server.stop(0);}
    @Test void responseIndicesNotArrayOrderDetermineInputAlignment() {
        body="{\"model\":\"embedding\",\"data\":[{\"index\":1,\"embedding\":[0,1]},{\"index\":0,\"embedding\":[1,0]}],\"usage\":{\"prompt_tokens\":7}}";
        var response=executor.execute(provider,"embedding",2,List.of("first","second"));
        assertThat(response.status()).isEqualTo(EmbeddingBatchExecutor.Status.SUCCEEDED);
        assertThat(response.vectors()).containsExactly(List.of(1.0,0.0),List.of(0.0,1.0));
        assertThat(response.inputTokens()).isEqualTo(7);
        assertThat(authorization).isEqualTo("Bearer fixture-secret"); assertThat(submitted).doesNotContain("fixture-secret");
    }

    @Test void supportedDimensionsAreSentAndModelBatchLimitRejectsBeforePost() throws Exception {
        body="{\"model\":\"text-embedding-v4\",\"data\":[{\"index\":0,\"embedding\":[1,0]}],\"usage\":{\"prompt_tokens\":1}}";
        assertThat(executor.execute(provider,"text-embedding-v4",2,List.of("first")).status()).isEqualTo(EmbeddingBatchExecutor.Status.SUCCEEDED);
        assertThat(new ObjectMapper().readTree(submitted).path("dimensions").asInt()).isEqualTo(2);
        int before=requests.get();
        assertThat(executor.execute(provider,"text-embedding-v4",2,java.util.Collections.nCopies(11,"item")).status()).isEqualTo(EmbeddingBatchExecutor.Status.REJECTED);
        assertThat(requests.get()).isEqualTo(before);
    }
    @Test void duplicateIndicesWrongDimensionsAndNonNumericValuesAreUnknown() {
        for(String data:List.of("[{\"index\":0,\"embedding\":[1,0]},{\"index\":0,\"embedding\":[0,1]}]",
                "[{\"index\":0,\"embedding\":[1]},{\"index\":1,\"embedding\":[0,1]}]",
                "[{\"index\":0,\"embedding\":[\"not numeric\",0]},{\"index\":1,\"embedding\":[0,1]}]")) {
            body="{\"data\":"+data+"}";
            assertThat(executor.execute(provider,"embedding",2,List.of("first","second")).status()).isEqualTo(EmbeddingBatchExecutor.Status.UNKNOWN);
        }
    }
    @Test void rejectsExplicitFailuresButNeverFollowsRedirectsOrRetriesUnknownErrors() {
        body="provider-private-error-body";
        for(int code:List.of(401,429,500,302)) {
            status=code; int before=requests.get(); var r=executor.execute(provider,"embedding",2,List.of("first"));
            assertThat(r.status()).isEqualTo(code==401 || code==429?EmbeddingBatchExecutor.Status.REJECTED:EmbeddingBatchExecutor.Status.UNKNOWN);
            assertThat(requests.get()).isEqualTo(before+1); assertThat(r.safeCode()).doesNotContain(body);
        }
    }
}
