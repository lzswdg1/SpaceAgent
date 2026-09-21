package com.spaceagent.platform.integration.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.spaceagent.platform.project.domain.RepositoryBranchVisibility;

public final class PublicRecoveryProjection {
    private PublicRecoveryProjection() { }
    public static JsonNode redact(JsonNode internal) {
        JsonNode copy=internal.deepCopy();
        JsonNode workspace=copy.path("context").path("workspace");
        if(workspace instanceof ObjectNode object){
            object.remove("branchName");
            if(object.path("baseRef").isTextual())object.put("baseRef",RepositoryBranchVisibility.publicRef(object.path("baseRef").asText()));
        }
        if(copy instanceof ObjectNode object)object.put("projection","USER_REDACTED");
        return copy;
    }
}
