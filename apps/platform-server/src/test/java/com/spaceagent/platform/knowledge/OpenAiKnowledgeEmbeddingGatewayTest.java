package com.spaceagent.platform.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.spaceagent.platform.knowledge.infrastructure.KnowledgeProperties;
import com.spaceagent.platform.knowledge.infrastructure.OpenAiKnowledgeEmbeddingGateway;
import com.spaceagent.shared.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class OpenAiKnowledgeEmbeddingGatewayTest {
    @Test
    void reusesBoundedClientAndReturnsOnlySafeFailures() {
        KnowledgeProperties properties = new KnowledgeProperties();
        properties.setEmbeddingApiKey("secret-key");
        properties.setEmbeddingModel("embedding-model");
        RestClient.Builder builder = RestClient.builder().baseUrl("https://embedding.test/");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        var gateway = new OpenAiKnowledgeEmbeddingGateway(
                properties, new ObjectMapper(), builder.build());

        server.expect(request -> assertThat(request.getURI().getPath()).isEqualTo("/embeddings"))
                .andRespond(withSuccess("""
                        {"data":[{"embedding":[0.1,0.2]},{"embedding":[0.3,0.4]}]}
                        """, MediaType.APPLICATION_JSON));
        assertThat(gateway.embed(List.of("a", "b")).vectors()).hasSize(2);
        server.verify();

        RestClient.Builder failureBuilder = RestClient.builder().baseUrl("https://embedding.test/");
        MockRestServiceServer failureServer = MockRestServiceServer.bindTo(failureBuilder).build();
        var failing = new OpenAiKnowledgeEmbeddingGateway(
                properties, new ObjectMapper(), failureBuilder.build());
        failureServer.expect(request -> { }).andRespond(withServerError());
        assertThatThrownBy(() -> failing.embed(List.of("a")))
                .isInstanceOf(BusinessException.class)
                .hasMessageNotContaining("embedding.test");
    }
}
