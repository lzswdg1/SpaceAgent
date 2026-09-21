package com.spaceagent.platform.inference.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.spaceagent.platform.inference.domain.*;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.*;

/** One bounded POST, no automatic application retry or redirects; secrets stay in this adapter. */
@Component
public class OpenAiCompatibleEmbeddingBatchExecutor implements EmbeddingBatchExecutor {
    private final ModelProviderSecretCipher cipher;
    private final ObjectMapper json;
    private final ModelProviderEndpointPolicy endpoints;
    public OpenAiCompatibleEmbeddingBatchExecutor(ModelProviderSecretCipher cipher,ObjectMapper json,ModelProviderEndpointPolicy endpoints) {
        this.cipher=cipher; this.json=json; this.endpoints=endpoints;
    }
    public Outcome execute(ModelProvider provider,String model,int dimensions,List<String> inputs) {
        var capabilities = EmbeddingModelCapabilities.forModel(model);
        if (inputs.size() > capabilities.maximumItems()) return failure(Status.REJECTED, "EMBEDDING_MODEL_BATCH_LIMIT_EXCEEDED");
        RestClient client;
        try {
            String base=endpoints.validateAndNormalize(provider.baseUrl());
            var factory=new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                    .followRedirects(HttpClient.Redirect.NEVER).build());
            factory.setReadTimeout(Duration.ofSeconds(30));
            var builder=RestClient.builder().baseUrl(base+"/").requestFactory(factory);
            String secret=cipher.decrypt(provider.encryptedApiKey());
            switch(provider.authType().toLowerCase(Locale.ROOT)) {
                case "bearer" -> builder.defaultHeader("Authorization","Bearer "+secret);
                case "x-api-key", "api-key" -> builder.defaultHeader("X-API-Key",secret);
                default -> { return failure(Status.REJECTED,"EMBEDDING_AUTH_UNSUPPORTED"); }
            }
            client=builder.build();
        } catch(RuntimeException e) { return failure(Status.REJECTED,"EMBEDDING_CLIENT_CONFIGURATION_INVALID"); }
        try {
            Map<String, Object> body = new LinkedHashMap<>(Map.of("model",model,"input",inputs,"encoding_format","float"));
            if (capabilities.dimensionsParameterSupported()) body.put("dimensions", dimensions);
            return client.post().uri("embeddings").body(body)
                    .exchange((request,response)->{
                        int status=response.getStatusCode().value();
                        if(status<200 || status>=300) {
                            // 408/5xx/redirects do not prove whether work was performed. Never persist remote error bodies.
                            boolean rejected=Set.of(400,401,403,404,413,415,422,429).contains(status);
                            return failure(rejected?Status.REJECTED:Status.UNKNOWN,rejected?"EMBEDDING_PROVIDER_REJECTED":"EMBEDDING_REMOTE_OUTCOME_UNKNOWN");
                        }
                        byte[] bytes=response.getBody().readNBytes(8_000_001);
                        if(bytes.length>8_000_000) return failure(Status.UNKNOWN,"EMBEDDING_RESPONSE_INVALID");
                        var root=json.readTree(bytes); var data=root.path("data");
                        if(!data.isArray() || data.size()!=inputs.size() || root.has("model") && !model.equals(root.path("model").asText()))
                            return failure(Status.UNKNOWN,"EMBEDDING_RESPONSE_INVALID");
                        var ordered=new ArrayList<List<Double>>(Collections.nCopies(inputs.size(),null));
                        for(var item:data) {
                            var index=item.path("index");
                            if(!index.isIntegralNumber() || !index.canConvertToInt() || index.intValue()<0 || index.intValue()>=inputs.size()
                                    || ordered.get(index.intValue())!=null || !item.path("embedding").isArray())
                                return failure(Status.UNKNOWN,"EMBEDDING_RESPONSE_INVALID");
                            List<Double> vector=new ArrayList<>();
                            for(var value:item.path("embedding")) {
                                if(!value.isNumber()) return failure(Status.UNKNOWN,"EMBEDDING_RESPONSE_INVALID");
                                vector.add(value.doubleValue());
                            }
                            ordered.set(index.intValue(),vector);
                        }
                        EmbeddingBounds.vectors(ordered,inputs.size(),dimensions);
                        var usage=root.path("usage").path("prompt_tokens");
                        Long tokens=usage.isMissingNode() || usage.isNull()?null:usage.isIntegralNumber() && usage.canConvertToLong()
                                && usage.longValue()>=0?usage.longValue():-1L;
                        if(tokens!=null && tokens<0) return failure(Status.UNKNOWN,"EMBEDDING_USAGE_INVALID");
                        return new Outcome(Status.SUCCEEDED,ordered,tokens,tokens==null?"EMBEDDING_USAGE_UNREPORTED":null);
                    });
        } catch(Exception e) { return failure(Status.UNKNOWN,"EMBEDDING_REMOTE_OUTCOME_UNKNOWN"); }
    }
    private static Outcome failure(Status state,String code) { return new Outcome(state,List.of(),null,code); }
}
