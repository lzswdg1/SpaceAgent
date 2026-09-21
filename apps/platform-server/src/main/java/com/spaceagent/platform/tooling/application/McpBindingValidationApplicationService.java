package com.spaceagent.platform.tooling.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.spaceagent.platform.tooling.api.McpBindingValidationApplicationApi;
import com.spaceagent.platform.tooling.domain.*;
import com.spaceagent.shared.exception.BusinessException;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class McpBindingValidationApplicationService implements McpBindingValidationApplicationApi {
    private final McpMarketplaceRepository marketplace;private final McpConnectionQualificationRepository qualifications;private final ObjectMapper json;
    public McpBindingValidationApplicationService(McpMarketplaceRepository marketplace,McpConnectionQualificationRepository qualifications,ObjectMapper json){this.marketplace=marketplace;this.qualifications=qualifications;this.json=json;}
    public ValidatedBinding validate(ValidateCommand c){
        var installation=marketplace.findInstallation(c.installationId()).filter(v->v.tenantId().equals(c.tenantId())).filter(v->v.state()==McpInstallationState.INSTALLED).orElseThrow(McpBindingValidationApplicationService::invalid);
        if(installation.scope()==McpInstallationScope.USER&&!installation.subjectId().equals(c.ownerUserId()))throw invalid();
        if(!installation.serverVersionId().equals(c.serverVersionId()))throw invalid();
        var version=marketplace.findVersion(c.serverVersionId()).filter(v->v.entryId().equals(installation.entryId())).filter(v->v.lifecycleState()==McpServerVersionState.APPROVED).orElseThrow(McpBindingValidationApplicationService::invalid);
        var connection=marketplace.findConnection(c.connectionId()).filter(v->v.tenantId().equals(c.tenantId())).filter(v->v.installationId().equals(installation.id())).filter(v->v.state()==McpConnectionState.ACTIVE).filter(v->v.revision()==c.connectionRevision()).orElseThrow(McpBindingValidationApplicationService::invalid);
        var snapshot=qualifications.findCurrentSnapshot(connection.id()).filter(v->v.id().equals(c.capabilitySnapshotId())).filter(v->v.connectionRevision()==c.connectionRevision()).filter(v->("sha256:"+v.snapshotSha256()).equals(c.snapshotSha256())).orElseThrow(McpBindingValidationApplicationService::invalid);
        Set<String> advertised=toolNames(snapshot.toolsJson());List<String> requested=c.allowedToolNames()==null?List.of():c.allowedToolNames().stream().distinct().sorted().toList();
        if(requested.isEmpty()||!advertised.containsAll(requested))throw invalid();
        return new ValidatedBinding(installation.id(),connection.id(),version.id(),snapshot.id(),connection.revision(),c.snapshotSha256(),requested);
    }
    private Set<String> toolNames(String value){try{Set<String> names=new HashSet<>();json.readTree(value).forEach(node->{String name=node.path("name").asText();if(!name.isBlank())names.add(name);});return names;}catch(Exception e){throw invalid();}}
    private static BusinessException invalid(){return new BusinessException("MCP binding is not currently qualified",HttpStatus.CONFLICT,"AGENT_MCP_BINDING_INVALID");}
}
