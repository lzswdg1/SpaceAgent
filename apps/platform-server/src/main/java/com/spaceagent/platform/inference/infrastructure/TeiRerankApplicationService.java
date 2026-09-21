package com.spaceagent.platform.inference.infrastructure;

import com.fasterxml.jackson.databind.*;
import com.spaceagent.platform.inference.api.RerankApplicationApi;
import com.spaceagent.platform.inference.domain.InferenceActorAccessPort;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/** Adapter to self-hosted Hugging Face TEI 1.9.0. Not a paid Provider endpoint or a model implementation. */
@Service @ConditionalOnProperty(name="platform.inference.reranker.mode",havingValue="tei")
public class TeiRerankApplicationService implements RerankApplicationApi {
    private final RestClient http;private final ObjectMapper json;private final String model,revision;
    private final InferenceActorAccessPort actors;private final MeterRegistry metrics;
    private final Semaphore permits=new Semaphore(4);
    public TeiRerankApplicationService(ObjectMapper json,InferenceActorAccessPort actors,MeterRegistry metrics,
            @Value("${platform.inference.reranker.uri:}") String endpoint,@Value("${platform.inference.reranker.token:}") String token,
            @Value("${platform.inference.reranker.model:}") String model,@Value("${platform.inference.reranker.revision:}") String revision,
            @Value("${platform.inference.reranker.allow-private-http:false}") boolean allowHttp){
        this.json=json;this.actors=actors;this.metrics=metrics;this.model=model;this.revision=revision;
        URI uri=URI.create(endpoint);
        if(uri.getHost()==null || uri.getUserInfo()!=null || uri.getQuery()!=null || uri.getFragment()!=null || !(uri.getPath().isEmpty()||uri.getPath().equals("/"))
            || !("https".equals(uri.getScheme()) || allowHttp && "http".equals(uri.getScheme()) && Set.of("localhost","127.0.0.1","rag-reranker").contains(uri.getHost()))
            || model.isBlank() || model.length()>256 || !revision.matches("[a-f0-9]{40,64}"))throw new IllegalArgumentException("Reranker requires a pinned self-hosted endpoint and model revision");
        var factory=new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).followRedirects(HttpClient.Redirect.NEVER).build());factory.setReadTimeout(Duration.ofSeconds(8));
        var builder=RestClient.builder().baseUrl(endpoint.replaceAll("/+$","")).requestFactory(factory);
        if(!token.isBlank())builder.defaultHeader("Authorization","Bearer "+token);this.http=builder.build();
    }
    public Result rank(Request r){
        if(r==null || r.tenantId()==null || r.actorId()==null || r.query()==null || r.query().isBlank() || r.query().length()>4000
            || r.texts().isEmpty() || r.texts().size()>32 || r.texts().stream().anyMatch(t->t.isBlank()||t.length()>8192)
            || r.texts().stream().mapToInt(String::length).sum()>128000)return fail("RERANK_INPUT_INVALID");
        if(!actors.active(r.tenantId(),r.actorId()))return fail("RERANK_ACTOR_UNAVAILABLE");
        if(!permits.tryAcquire())return fail("RERANK_CAPACITY_LIMIT");
        long start=System.nanoTime();String outcome="failed";
        try{
            var info=read(http.get().uri("/info"));
            if(!"1.9.0".equals(info.path("version").asText()) || !model.equals(info.path("model_id").asText()) || !revision.equals(info.path("model_sha").asText()))return fail("RERANK_MODEL_CHANGED");
            var response=read(http.post().uri("/rerank").contentType(MediaType.APPLICATION_JSON).body(Map.of("query",r.query(),"texts",r.texts(),"raw_scores",false,"return_text",false,"truncate",false)));
            if(!response.isArray() || response.size()!=r.texts().size())return fail("RERANK_RESPONSE_INVALID");
            List<Score> scores=new ArrayList<>();Set<Integer> seen=new HashSet<>();
            for(var item:response){int index=item.path("index").asInt(-1);double score=item.path("score").asDouble(Double.NaN);
                if(!item.path("index").isIntegralNumber() || !item.path("score").isNumber() || index<0 || index>=r.texts().size() || !seen.add(index) || !Double.isFinite(score) || score<0 || score>1)return fail("RERANK_RESPONSE_INVALID");
                scores.add(new Score(index,score));}
            scores.sort(Comparator.comparingDouble(Score::score).reversed().thenComparingInt(Score::index));outcome="succeeded";
            return new Result(true,scores,model,revision,null);
        }catch(Exception e){return fail("RERANK_UNAVAILABLE");}
        finally{permits.release();metrics.timer("spaceagent.rag.rerank.duration","outcome",outcome).record(System.nanoTime()-start,TimeUnit.NANOSECONDS);}
    }
    private JsonNode read(RestClient.RequestHeadersSpec<?> request){return request.exchange((req,res)->{
        if(!res.getStatusCode().is2xxSuccessful())throw new IllegalStateException("Reranker rejected request");
        byte[] body=res.getBody().readNBytes(65537);if(body.length>65536)throw new IllegalStateException("Reranker output bound");return json.readTree(body);
    });}
    private Result fail(String code){return new Result(false,List.of(),model,revision,code);}
}
