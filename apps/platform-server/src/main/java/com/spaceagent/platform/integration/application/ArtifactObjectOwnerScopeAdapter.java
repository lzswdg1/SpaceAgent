package com.spaceagent.platform.integration.application;

import com.spaceagent.platform.artifact.api.ArtifactApplicationApi;
import com.spaceagent.platform.artifact.domain.*;
import com.spaceagent.platform.knowledge.api.KnowledgeOwnershipPort;
import com.spaceagent.platform.project.api.*;
import org.springframework.stereotype.Component;

@Component
public class ArtifactObjectOwnerScopeAdapter implements ArtifactObjectOwnerScopeGateway {
    private final ProjectApplicationApi projects;private final KnowledgeOwnershipPort knowledge;private final ArtifactApplicationApi artifacts;
    public ArtifactObjectOwnerScopeAdapter(ProjectApplicationApi projects,KnowledgeOwnershipPort knowledge,ArtifactApplicationApi artifacts){this.projects=projects;this.knowledge=knowledge;this.artifacts=artifacts;}
    @Override public boolean canAttach(String tenant,String actor,ArtifactObjectReference.OwnerType type,String resource){try{return switch(type){case PROJECT->projects.getProject(new GetProjectQuery(tenant,actor,resource))!=null;case KNOWLEDGE->knowledge.canAccess(resource,actor);case ARTIFACT->artifacts.find(tenant,resource).map(value->projects.getProject(new GetProjectQuery(tenant,actor,value.projectId()))!=null).orElse(false);};}catch(RuntimeException denied){return false;}}
}
