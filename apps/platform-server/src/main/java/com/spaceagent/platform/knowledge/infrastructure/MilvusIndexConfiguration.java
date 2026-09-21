package com.spaceagent.platform.knowledge.infrastructure;

import com.spaceagent.platform.knowledge.domain.VectorIndexGateway;
import io.milvus.v2.client.ConnectConfig;
import io.milvus.v2.client.MilvusClientV2;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.net.URI;

@Configuration(proxyBeanMethods=false)
@org.springframework.context.annotation.DependsOn("knowledgeVectorBackendSelection")
@ConditionalOnProperty(prefix="platform.knowledge.vector-store",name="mode",havingValue="milvus")
public class MilvusIndexConfiguration {
    @Bean(destroyMethod="close")
    public MilvusClientV2 knowledgeMilvusClient(
            @Value("${platform.knowledge.milvus.uri}") String uri,
            @Value("${platform.knowledge.milvus.token}") String token,
            @Value("${platform.knowledge.milvus.database:default}") String database,
            @Value("${platform.knowledge.milvus.allow-insecure-local:false}") boolean insecureLocal) {
        URI endpoint=URI.create(uri);
        if(endpoint.getHost()==null || endpoint.getUserInfo()!=null || endpoint.getRawQuery()!=null
                || endpoint.getFragment()!=null || endpoint.getRawPath()!=null && !endpoint.getRawPath().isEmpty()
                || !("https".equals(endpoint.getScheme()) || insecureLocal && "http".equals(endpoint.getScheme())
                    && java.util.Set.of("localhost","127.0.0.1","[::1]","rag-milvus","host.docker.internal").contains(endpoint.getHost())))
            throw new IllegalArgumentException("Milvus requires a configured HTTPS endpoint or explicit private development override");
        if(token==null || token.isBlank() || token.equals("root:Milvus") || !insecureLocal && token.startsWith("root:"))
            throw new IllegalArgumentException("Dedicated Milvus credentials are required");
        return new MilvusClientV2(ConnectConfig.builder().uri(uri).token(token).dbName(database)
                .connectTimeoutMs(5000).rpcDeadlineMs(30000).build());
    }
    @Bean
    public VectorIndexGateway knowledgeVectorIndex(MilvusClientV2 knowledgeMilvusClient,
            @Value("${platform.knowledge.milvus.collection-prefix:spaceagent}") String prefix) {
        return new MilvusVectorIndexGateway(knowledgeMilvusClient,prefix);
    }
}
