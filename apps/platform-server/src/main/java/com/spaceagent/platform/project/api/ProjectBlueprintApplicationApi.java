package com.spaceagent.platform.project.api;
import com.spaceagent.platform.project.domain.*; import java.time.Instant; import java.util.List;
public interface ProjectBlueprintApplicationApi {
    BlueprintView create(CreateCommand command);
    BlueprintView confirm(ConfirmCommand command);
    BlueprintView get(Query query);
    List<BlueprintView> list(ListQuery query);
    record CreateCommand(String tenantId,String userId,String projectId,String sourceRepositoryId,
            String generatedByAgentId,String generatedByRunConfigurationSnapshotId,ProjectBlueprintSource source,
            ProjectBlueprintDocument document){
        public CreateCommand(String tenantId,String userId,String projectId,String sourceRepositoryId,
                String ignoredLegacyGenerator,ProjectBlueprintSource source,ProjectBlueprintDocument document){
            this(tenantId,userId,projectId,sourceRepositoryId,null,null,source,document);
        }
    }
    record ConfirmCommand(String tenantId,String userId,String projectId,String blueprintId){}
    record Query(String tenantId,String userId,String projectId,String blueprintId){}
    record ListQuery(String tenantId,String userId,String projectId){}
    record BlueprintView(String id,String projectId,int versionNumber,ProjectBlueprintStatus status,
            ProjectBlueprintSource source,String sourceRepositoryId,String generatedByAgentId,
            String generatedByRunConfigurationSnapshotId,String createdBy,
            String confirmedBy,Instant confirmedAt,ProjectBlueprintDocument document,Instant createdAt,Instant updatedAt){
        public BlueprintView(String id,String projectId,int versionNumber,ProjectBlueprintStatus status,
                ProjectBlueprintSource source,String sourceRepositoryId,String ignoredLegacyGenerator,
                String createdBy,String confirmedBy,Instant confirmedAt,ProjectBlueprintDocument document,
                Instant createdAt,Instant updatedAt){
            this(id,projectId,versionNumber,status,source,sourceRepositoryId,null,null,
                    createdBy,confirmedBy,confirmedAt,document,createdAt,updatedAt);
        }
    }
}
