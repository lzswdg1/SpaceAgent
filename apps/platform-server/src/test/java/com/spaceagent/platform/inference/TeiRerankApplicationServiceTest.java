package com.spaceagent.platform.inference;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.spaceagent.platform.identity.api.*;
import com.spaceagent.platform.identity.domain.*;
import com.spaceagent.platform.inference.api.RerankApplicationApi.*;
import com.spaceagent.platform.inference.infrastructure.TeiRerankApplicationService;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class TeiRerankApplicationServiceTest {
    HttpServer server;final AtomicInteger calls=new AtomicInteger();final AtomicReference<String> revision=new AtomicReference<>("a".repeat(40));
    final AtomicReference<String> response=new AtomicReference<>("[{\"index\":1,\"score\":0.9},{\"index\":0,\"score\":0.1}]");
    IdentityActivityApplicationApi activity;TeiRerankApplicationService api;
    @BeforeEach void setup() throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/info",x->{byte[] body=("{\"version\":\"1.9.0\",\"model_id\":\"fixture/reranker\",\"model_sha\":\""+revision.get()+"\"}").getBytes(StandardCharsets.UTF_8);x.sendResponseHeaders(200,body.length);x.getResponseBody().write(body);x.close();});
        server.createContext("/rerank",x->{calls.incrementAndGet();String input=new String(x.getRequestBody().readAllBytes(),StandardCharsets.UTF_8);
            assertThat(input).contains("\"truncate\":false","\"return_text\":false");assertThat(x.getRequestHeaders().getFirst("Authorization")).isEqualTo("Bearer fixture-token");
            byte[] body=response.get().getBytes(StandardCharsets.UTF_8);x.sendResponseHeaders(200,body.length);x.getResponseBody().write(body);x.close();});server.start();
        activity=mock(IdentityActivityApplicationApi.class);var identity=mock(IdentityApplicationApi.class);var now=Instant.now();
        when(activity.isUserActive("user")).thenReturn(true);when(identity.findTenant("org")).thenReturn(Optional.of(new TenantView("org","org","org",TenantStatus.ACTIVE,now)));
        when(identity.findTenantMembership("org","user")).thenReturn(Optional.of(new TenantMembershipView("org","user",TenantRole.MEMBER,TenantMembershipStatus.ACTIVE,now,now)));
        api=new TeiRerankApplicationService(new ObjectMapper(),new com.spaceagent.platform.integration.application.InferenceActorAccessAdapter(activity,identity),new SimpleMeterRegistry(),"http://127.0.0.1:"+server.getAddress().getPort(),"fixture-token","fixture/reranker","a".repeat(40),true);
    }
    @AfterEach void close(){server.stop(0);}
    Request request(){return new Request("org","user","question",List.of("first evidence","second evidence"));}
    @Test void pinnedTeiContractOrdersIndicesAndNeverReturnsProviderText(){var result=api.rank(request());assertThat(result.succeeded()).isTrue();assertThat(result.scores()).extracting(Score::index).containsExactly(1,0);assertThat(calls).hasValue(1);}
    @Test void changedModelAndInactiveActorNeverDispatchContent(){revision.set("b".repeat(40));assertThat(api.rank(request()).safeCode()).isEqualTo("RERANK_MODEL_CHANGED");when(activity.isUserActive("user")).thenReturn(false);assertThat(api.rank(request()).safeCode()).isEqualTo("RERANK_ACTOR_UNAVAILABLE");assertThat(calls).hasValue(0);}
    @Test void duplicateOrMalformedScoresFailClosedWithoutRetry(){response.set("[{\"index\":0,\"score\":0.9},{\"index\":0,\"score\":0.8}]");assertThat(api.rank(request()).safeCode()).isEqualTo("RERANK_RESPONSE_INVALID");assertThat(calls).hasValue(1);}
}
