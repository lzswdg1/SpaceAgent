package com.spaceagent.platform.tooling.api;

import java.util.List;

public interface McpBindingValidationApplicationApi {
    ValidatedBinding validate(ValidateCommand command);
    record ValidateCommand(String tenantId,String ownerUserId,String installationId,String connectionId,
            String serverVersionId,String capabilitySnapshotId,long connectionRevision,String snapshotSha256,
            List<String> allowedToolNames){}
    record ValidatedBinding(String installationId,String connectionId,String serverVersionId,
            String capabilitySnapshotId,long connectionRevision,String snapshotSha256,List<String> allowedToolNames){}
}
