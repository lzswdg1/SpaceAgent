package com.spaceagent.platform.runtime.infrastructure;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.spaceagent.platform.runtime.domain.MultiAgentOrchestrationPort;
import com.spaceagent.platform.runtime.domain.GraphV2OrchestrationPort;
import com.spaceagent.platform.runtime.domain.multiagent.GraphV2Contract;
import com.spaceagent.platform.runtime.domain.MultiAgentOrchestrationUnavailableException;
import com.spaceagent.platform.runtime.domain.multiagent.MultiAgentOrchestrationRequest;
import com.spaceagent.platform.runtime.domain.multiagent.MultiAgentOrchestrationResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.beans.factory.annotation.Autowired;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Context;

/** Opt-in JSON/HTTP adapter for services/multi-agent-orchestrator. */
@Component
@ConditionalOnProperty(
        prefix = "platform.multi-agent-orchestrator",
        name = "mode",
        havingValue = "http")
public class HttpMultiAgentOrchestrationClient implements MultiAgentOrchestrationPort, GraphV2OrchestrationPort {

    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public HttpMultiAgentOrchestrationClient(
            RestClient.Builder restClientBuilder,
            ObjectMapper objectMapper,
            @Value("${platform.multi-agent-orchestrator.base-url:http://127.0.0.1:9300}")
            String baseUrl) {
        this(restClientBuilder, objectMapper, baseUrl,
                "test-internal-token-0123456789-abcdef");
    }

    @Autowired
    public HttpMultiAgentOrchestrationClient(
            RestClient.Builder restClientBuilder,
            ObjectMapper objectMapper,
            @Value("${platform.multi-agent-orchestrator.base-url:http://127.0.0.1:9300}")
            String baseUrl,
            @Value("${platform.multi-agent-orchestrator.internal-token:${platform.security.internal-token}}")
            String internalToken) {
        if (internalToken == null || internalToken.length() < 32) {
            throw new IllegalStateException("Multi-Agent orchestrator internal token is required");
        }
        this.restClient = restClientBuilder.baseUrl(baseUrl)
                .defaultHeader("Authorization", "Bearer " + internalToken).build();
        this.objectMapper = objectMapper.copy()
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }

    @Override
    public MultiAgentOrchestrationResponse orchestrate(
            MultiAgentOrchestrationRequest request) {
        try {
            byte[] requestBody = objectMapper.writeValueAsBytes(request);
            byte[] responseBody = restClient.post()
                    .uri("/v1/orchestrate")
                    .contentType(MediaType.APPLICATION_JSON)
                    .headers(headers -> W3CTraceContextPropagator.getInstance().inject(
                            Context.current(), headers, (carrier, key, value) -> carrier.set(key, value)))
                    .body(requestBody)
                    .retrieve()
                    .body(byte[].class);
            if (responseBody == null || responseBody.length == 0) {
                throw new MultiAgentOrchestrationUnavailableException(
                        "Multi-Agent orchestrator returned an empty response");
            }
            return objectMapper.readValue(
                    responseBody, MultiAgentOrchestrationResponse.class);
        } catch (MultiAgentOrchestrationUnavailableException error) {
            throw error;
        } catch (RestClientException error) {
            throw new MultiAgentOrchestrationUnavailableException(
                    "Multi-Agent orchestrator unavailable", error);
        } catch (Exception error) {
            throw new MultiAgentOrchestrationUnavailableException(
                    "Unable to encode or decode Multi-Agent orchestration contract", error);
        }
    }

    @Override
    public GraphV2Contract.Result transition(GraphV2Contract.Request request) {
        return post("/v1/graph-v2", request, GraphV2Contract.Result.class);
    }

    private <T> T post(String path,Object request,Class<T> responseType){try{byte[] requestBody=objectMapper.writeValueAsBytes(request);byte[] responseBody=restClient.post().uri(path).contentType(MediaType.APPLICATION_JSON).headers(headers->W3CTraceContextPropagator.getInstance().inject(Context.current(),headers,(carrier,key,value)->carrier.set(key,value))).body(requestBody).retrieve().body(byte[].class);if(responseBody==null||responseBody.length==0)throw new MultiAgentOrchestrationUnavailableException("Multi-Agent orchestrator returned an empty response");return objectMapper.readValue(responseBody,responseType);}catch(MultiAgentOrchestrationUnavailableException error){throw error;}catch(RestClientException error){throw new MultiAgentOrchestrationUnavailableException("Multi-Agent orchestrator unavailable",error);}catch(Exception error){throw new MultiAgentOrchestrationUnavailableException("Unable to encode or decode Multi-Agent orchestration contract",error);}}
}
