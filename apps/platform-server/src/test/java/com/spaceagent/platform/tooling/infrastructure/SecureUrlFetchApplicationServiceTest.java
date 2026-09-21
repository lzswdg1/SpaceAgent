package com.spaceagent.platform.tooling.infrastructure;

import com.spaceagent.platform.tooling.api.SecureUrlFetchApplicationApi;
import com.spaceagent.platform.tooling.application.SecureUrlFetchApplicationService;
import com.spaceagent.platform.tooling.domain.McpRemoteEndpointPolicy;
import com.spaceagent.shared.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.http.*;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.net.*;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class SecureUrlFetchApplicationServiceTest {

    @Test void sendsBoundedConditionalHeadersAndAcceptsEmpty304(){RestClient.Builder builder=RestClient.builder();MockRestServiceServer server=MockRestServiceServer.bindTo(builder).build();var service=service(builder);server.expect(once(),requestTo("https://docs.example.test/page")).andExpect(header(HttpHeaders.IF_NONE_MATCH,"\"v1\"")).andExpect(header(HttpHeaders.IF_MODIFIED_SINCE,"Wed, 09 Sep 2026 00:00:00 GMT")).andRespond(withStatus(HttpStatus.NOT_MODIFIED).header(HttpHeaders.ETAG,"\"v1\""));var result=service.fetch(new SecureUrlFetchApplicationApi.FetchCommand("https://docs.example.test/page","\"v1\"","Wed, 09 Sep 2026 00:00:00 GMT",2,1024));assertThat(result.status()).isEqualTo(304);assertThat(result.body()).isEmpty();assertThat(result.etag()).isEqualTo("\"v1\"");server.verify();}

    @Test void revalidatesEachRedirectAndDoesNotForwardValidators(){RestClient.Builder builder=RestClient.builder();MockRestServiceServer server=MockRestServiceServer.bindTo(builder).build();server.expect(once(),requestTo("https://docs.example.test/start")).andRespond(withStatus(HttpStatus.FOUND).header(HttpHeaders.LOCATION,"https://cdn.example.test/final"));server.expect(once(),requestTo("https://cdn.example.test/final")).andExpect(request->{assertThat(request.getHeaders()).doesNotContainKeys(HttpHeaders.IF_NONE_MATCH,HttpHeaders.IF_MODIFIED_SINCE);}).andRespond(withSuccess("hello",MediaType.TEXT_PLAIN));var result=service(builder).fetch(new SecureUrlFetchApplicationApi.FetchCommand("https://docs.example.test/start","etag","date",2,1024));assertThat(result.finalUrl()).isEqualTo("https://cdn.example.test/final");assertThat(result.redirectCount()).isEqualTo(1);assertThat(new String(result.body(),StandardCharsets.UTF_8)).isEqualTo("hello");byte[] copy=result.body();copy[0]='X';assertThat(new String(result.body(),StandardCharsets.UTF_8)).isEqualTo("hello");server.verify();}

    @Test void privateRedirectAndRedirectLimitFailClosedWithStableCodes(){RestClient.Builder privateBuilder=RestClient.builder();MockRestServiceServer privateServer=MockRestServiceServer.bindTo(privateBuilder).build();privateServer.expect(requestTo("https://docs.example.test/start")).andRespond(withStatus(HttpStatus.FOUND).header(HttpHeaders.LOCATION,"https://127.0.0.1/secret"));assertCode(()->service(privateBuilder).fetch(new SecureUrlFetchApplicationApi.FetchCommand("https://docs.example.test/start",null,null,2,1024)),"KNOWLEDGE_URL_FETCH_FAILED");privateServer.verify();RestClient.Builder limitBuilder=RestClient.builder();MockRestServiceServer limitServer=MockRestServiceServer.bindTo(limitBuilder).build();limitServer.expect(requestTo("https://docs.example.test/start")).andRespond(withStatus(HttpStatus.FOUND).header(HttpHeaders.LOCATION,"/again"));assertCode(()->service(limitBuilder).fetch(new SecureUrlFetchApplicationApi.FetchCommand("https://docs.example.test/start",null,null,0,1024)),"KNOWLEDGE_URL_REDIRECT_LIMIT");limitServer.verify();}

    @Test void bodyMimeCharsetHeaderAndAddressBoundsAreEnforced()throws Exception{RestClient.Builder oversized=RestClient.builder();MockRestServiceServer oversizedServer=MockRestServiceServer.bindTo(oversized).build();oversizedServer.expect(requestTo("https://docs.example.test/large")).andRespond(withSuccess(new byte[6],MediaType.TEXT_PLAIN));assertCode(()->service(oversized).fetch(new SecureUrlFetchApplicationApi.FetchCommand("https://docs.example.test/large",null,null,0,5)),"KNOWLEDGE_URL_FETCH_FAILED");oversizedServer.verify();RestClient.Builder binary=RestClient.builder();MockRestServiceServer binaryServer=MockRestServiceServer.bindTo(binary).build();binaryServer.expect(requestTo("https://docs.example.test/bin")).andRespond(withSuccess(new byte[]{1},MediaType.APPLICATION_OCTET_STREAM));assertCode(()->service(binary).fetch(new SecureUrlFetchApplicationApi.FetchCommand("https://docs.example.test/bin",null,null,0,10)),"KNOWLEDGE_URL_MIME_UNSUPPORTED");binaryServer.verify();assertCode(()->service(RestClient.builder()).fetch(new SecureUrlFetchApplicationApi.FetchCommand("https://docs.example.test/","bad\r\nheader",null,0,10)),"KNOWLEDGE_URL_FETCH_INPUT_INVALID");assertThat(PublicEndpointResolver.blocked(InetAddress.getByName("127.0.0.1"))).isTrue();assertThat(PublicEndpointResolver.blocked(InetAddress.getByName("10.0.0.1"))).isTrue();}

    private static SecureUrlFetchApplicationService service(RestClient.Builder builder){McpRemoteEndpointPolicy policy=value->{URI uri=URI.create(value);if(uri.getHost()==null||uri.getHost().equals("127.0.0.1")||uri.getHost().equals("localhost"))throw new IllegalArgumentException("private");return uri;};return new SecureUrlFetchApplicationService(new BoundedPublicHttpClient(policy,builder.build()));}
    private static void assertCode(Runnable action,String code){assertThatThrownBy(action::run).isInstanceOfSatisfying(BusinessException.class,e->assertThat(e.getCode()).isEqualTo(code)).hasMessageNotContaining("127.0.0.1").hasMessageNotContaining("secret");}
}
