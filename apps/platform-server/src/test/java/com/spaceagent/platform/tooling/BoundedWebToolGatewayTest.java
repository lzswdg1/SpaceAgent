package com.spaceagent.platform.tooling;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.spaceagent.platform.tooling.domain.McpRemoteEndpointPolicy;
import com.spaceagent.platform.tooling.infrastructure.BoundedHttpFetchGateway;
import com.spaceagent.platform.tooling.infrastructure.BoundedPublicHttpClient;
import com.spaceagent.platform.tooling.infrastructure.SearxngWebSearchGateway;
import com.spaceagent.platform.tooling.infrastructure.WebSearchProperties;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class BoundedWebToolGatewayTest {

    @Test
    void fetchesBoundedHtmlAndRejectsBinaryContent() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        var http = new BoundedPublicHttpClient(URI::create, builder.build());
        var fetch = new BoundedHttpFetchGateway(http);

        server.expect(request -> assertThat(request.getURI().toString())
                        .isEqualTo("https://docs.example.test/page"))
                .andRespond(withSuccess(
                        "<html><head><title>Docs</title></head>"
                                + "<body><script>ignored()</script><p>Hello Agent</p></body></html>",
                        MediaType.TEXT_HTML));

        var result = fetch.fetch("https://docs.example.test/page", 5_000);
        assertThat(result.status()).isEqualTo(200);
        assertThat(result.title()).isEqualTo("Docs");
        assertThat(result.content()).contains("Hello Agent").doesNotContain("ignored()");
        assertThat(result.truncated()).isFalse();
        server.verify();

        RestClient.Builder binaryBuilder = RestClient.builder();
        MockRestServiceServer binaryServer = MockRestServiceServer
                .bindTo(binaryBuilder).build();
        var binaryFetch = new BoundedHttpFetchGateway(
                new BoundedPublicHttpClient(URI::create, binaryBuilder.build()));
        binaryServer.expect(request -> { })
                .andRespond(withSuccess(new byte[]{1, 2, 3},
                        MediaType.APPLICATION_OCTET_STREAM));
        assertThatThrownBy(() -> binaryFetch.fetch(
                "https://files.example.test/archive.bin", 5_000))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("content type");
        binaryServer.verify();
    }

    @Test
    void mapsSearxngJsonAndFiltersUnsafeResultUrls() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        var http = new BoundedPublicHttpClient(policy(), builder.build());
        WebSearchProperties properties = new WebSearchProperties();
        properties.setMode("searxng");
        properties.setBaseUrl("https://search.example.test");
        var search = new SearxngWebSearchGateway(
                properties, http, policy(), new ObjectMapper());

        server.expect(request -> {
                    assertThat(request.getURI().getPath()).isEqualTo("/search");
                    assertThat(request.getURI().getQuery())
                            .contains("q=agent runtime", "format=json", "safesearch=1");
                })
                .andRespond(withSuccess("""
                        {"results":[
                          {"title":"<b>SpaceAgent</b>","url":"https://example.com/repo",
                           "content":"<p>Runtime tools</p>","engine":"docs","score":1.0},
                          {"title":"private","url":"https://127.0.0.1/secret",
                           "content":"must be filtered","engine":"bad","score":0.5}
                        ]}
                        """, MediaType.APPLICATION_JSON));

        var result = search.search(new com.spaceagent.platform.tooling.domain.WebSearchGateway
                .SearchRequest("agent runtime", 5, null, null, null));
        assertThat(result.results()).hasSize(1);
        assertThat(result.results().getFirst().url())
                .isEqualTo("https://example.com/repo");
        assertThat(result.results().getFirst().snippet()).isEqualTo("Runtime tools");
        server.verify();
    }

    private static McpRemoteEndpointPolicy policy() {
        return value -> {
            URI uri = URI.create(value);
            if ("127.0.0.1".equals(uri.getHost())) {
                throw new IllegalArgumentException("private endpoint");
            }
            return uri;
        };
    }
}
