package com.spaceagent.platform.tooling.application;

import com.spaceagent.platform.tooling.api.*;
import com.spaceagent.platform.tooling.domain.*;
import com.spaceagent.shared.exception.BusinessException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class McpResourceApplicationService implements McpResourceApplicationApi {
    private static final int MAX_BYTES=200_000;private static final Set<String>MIME=Set.of("text/plain","text/markdown","text/html","application/json","application/xml");
    private static final Pattern SECRET=Pattern.compile("(?i)(api[_-]?key|authorization|bearer|password|private[ _-]?key)\\s*[:=]");
    private final McpMarketplaceRepository marketplace;private final McpBindingValidationApplicationApi validator;private final McpConnectionAuthorizationService authorization;private final McpRemoteToolGateway gateway;
    public McpResourceApplicationService(McpMarketplaceRepository marketplace,McpBindingValidationApplicationApi validator,McpConnectionAuthorizationService authorization,McpRemoteToolGateway gateway){this.marketplace=marketplace;this.validator=validator;this.authorization=authorization;this.gateway=gateway;}
    public List<ResourceView> list(ListCommand c){var connection=authorized(c.tenantId(),c.ownerUserId(),c.binding());return gateway.listResources(connection.connection(),connection.authorization()).stream().map(r->{uri(r.uri());mime(r.mimeType());if(r.size()!=null&&(r.size()<0||r.size()>MAX_BYTES))throw invalid("MCP_RESOURCE_SIZE_INVALID");return new ResourceView(r.uri(),bounded(r.name(),200),bounded(r.title(),200),bounded(r.description(),1000),r.mimeType(),r.size());}).toList();}
    public ResourceContent read(ReadCommand c){uri(c.uri());var connection=authorized(c.tenantId(),c.ownerUserId(),c.binding());var value=gateway.readResource(connection.connection(),connection.authorization(),c.uri());if(value.blob()||!c.uri().equals(value.uri()))throw invalid("MCP_RESOURCE_CONTENT_INVALID");mime(value.mimeType());String text=value.text()==null?"":value.text();int bytes=text.getBytes(StandardCharsets.UTF_8).length;if(bytes>MAX_BYTES||SECRET.matcher(text).find())throw invalid("MCP_RESOURCE_CONTENT_INVALID");return new ResourceContent(value.uri(),value.mimeType(),text,bytes);}
    private McpConnectionAuthorizationService.AuthorizedConnection authorized(String tenant,String owner,Binding b){validator.validate(new McpBindingValidationApplicationApi.ValidateCommand(tenant,owner,b.installationId(),b.connectionId(),b.serverVersionId(),b.capabilitySnapshotId(),b.connectionRevision(),b.snapshotSha256(),b.allowedToolNames()));var connection=marketplace.findConnection(b.connectionId()).orElseThrow(()->invalid("MCP_RESOURCE_BINDING_INVALID"));return authorization.authorize(connection);}
    private static void uri(String value){try{URI uri=URI.create(value);String scheme=uri.getScheme();if(value.length()>2000||scheme==null||Set.of("file","data","javascript").contains(scheme.toLowerCase(Locale.ROOT)))throw new Exception();}catch(Exception e){throw invalid("MCP_RESOURCE_URI_INVALID");}}
    private static void mime(String value){if(value==null||!MIME.contains(value.toLowerCase(Locale.ROOT)))throw invalid("MCP_RESOURCE_MIME_INVALID");}
    private static String bounded(String value,int max){if(value==null)return null;if(value.length()>max)throw invalid("MCP_RESOURCE_METADATA_INVALID");return value;}
    private static BusinessException invalid(String code){return new BusinessException("MCP Resource is not safe for Context",HttpStatus.CONFLICT,code);}
}
