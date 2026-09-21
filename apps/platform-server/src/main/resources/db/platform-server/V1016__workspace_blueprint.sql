DO $workspace_blueprint$
BEGIN
 IF to_regclass('public.platform_source_repositories') IS NULL THEN RETURN; END IF;
 ALTER TABLE platform_source_repositories ADD CONSTRAINT uk_platform_source_repo_scope UNIQUE(id,project_id,tenant_id);
 CREATE TABLE platform_workspaces(
  id UUID PRIMARY KEY,tenant_id VARCHAR(36) NOT NULL,project_id UUID NOT NULL,task_id UUID NOT NULL,
  source_repository_id UUID NOT NULL,bridge_id UUID,mode VARCHAR(24) NOT NULL,worktree_key UUID NOT NULL,
  base_ref VARCHAR(255) NOT NULL,branch_name VARCHAR(255) NOT NULL,worktree_ref VARCHAR(180),head_commit VARCHAR(64),
  writable BOOLEAN NOT NULL,state VARCHAR(32) NOT NULL,failure_reason TEXT,revision BIGINT NOT NULL,
  created_by VARCHAR(36) NOT NULL,created_at TIMESTAMPTZ NOT NULL,updated_at TIMESTAMPTZ NOT NULL,
  CONSTRAINT uk_platform_workspace_scope UNIQUE(id,project_id,tenant_id),
  CONSTRAINT ck_platform_workspace_mode CHECK(mode IN('MANAGED_GIT','LOCAL_BRIDGE')),
  CONSTRAINT ck_platform_workspace_state CHECK(state IN('PROVISIONING','WAITING_FOR_BRIDGE','READY','DIRTY','CLEANED_UP','ARCHIVED','FAILED')),
  CONSTRAINT ck_platform_workspace_bridge CHECK((mode='LOCAL_BRIDGE')=(bridge_id IS NOT NULL)),
  CONSTRAINT fk_platform_workspace_project FOREIGN KEY(project_id,tenant_id) REFERENCES platform_projects(id,tenant_id),
  CONSTRAINT fk_platform_workspace_task FOREIGN KEY(project_id,task_id) REFERENCES platform_tasks(project_id,id),
  CONSTRAINT fk_platform_workspace_source FOREIGN KEY(source_repository_id,project_id,tenant_id) REFERENCES platform_source_repositories(id,project_id,tenant_id),
  CONSTRAINT fk_platform_workspace_bridge FOREIGN KEY(bridge_id,tenant_id,created_by) REFERENCES platform_local_workspace_bridges(id,tenant_id,owner_id)
 );
 CREATE UNIQUE INDEX uk_platform_workspace_active_task_source ON platform_workspaces(task_id,source_repository_id) WHERE writable AND state NOT IN('ARCHIVED','CLEANED_UP','FAILED');
 CREATE UNIQUE INDEX uk_platform_workspace_active_key ON platform_workspaces(worktree_key) WHERE state NOT IN('ARCHIVED','CLEANED_UP','FAILED');
 CREATE TABLE platform_bridge_workspace_commands(
  id UUID PRIMARY KEY,workspace_id UUID NOT NULL,bridge_id UUID NOT NULL,root_handle VARCHAR(128) NOT NULL,
  base_ref VARCHAR(255) NOT NULL,branch_name VARCHAR(255) NOT NULL,worktree_key UUID NOT NULL,state VARCHAR(24) NOT NULL,
  created_at TIMESTAMPTZ NOT NULL,completed_at TIMESTAMPTZ,
  CONSTRAINT ck_platform_bridge_workspace_command_state CHECK(state IN('PENDING','COMPLETED','FAILED')),
  CONSTRAINT fk_platform_bridge_workspace_command_workspace FOREIGN KEY(workspace_id) REFERENCES platform_workspaces(id) ON DELETE CASCADE,
  CONSTRAINT fk_platform_bridge_workspace_command_bridge FOREIGN KEY(bridge_id) REFERENCES platform_local_workspace_bridges(id)
 );
 CREATE TABLE platform_project_blueprints(
  id UUID PRIMARY KEY,tenant_id VARCHAR(36) NOT NULL,project_id UUID NOT NULL,version_number INTEGER NOT NULL,
  status VARCHAR(24) NOT NULL,source VARCHAR(24) NOT NULL,source_repository_id UUID,generated_by_agent_version_id UUID,
  created_by VARCHAR(36) NOT NULL,confirmed_by VARCHAR(36),confirmed_at TIMESTAMPTZ,document_json JSONB NOT NULL,
  created_at TIMESTAMPTZ NOT NULL,updated_at TIMESTAMPTZ NOT NULL,
  CONSTRAINT uk_platform_blueprint_version UNIQUE(project_id,version_number),
  CONSTRAINT ck_platform_blueprint_status CHECK(status IN('DRAFT','CONFIRMED','SUPERSEDED')),
  CONSTRAINT ck_platform_blueprint_source CHECK(source IN('USER','AGENT','IMPORT')),
  CONSTRAINT ck_platform_blueprint_confirmation CHECK((confirmed_by IS NULL)=(confirmed_at IS NULL)),
  CONSTRAINT fk_platform_blueprint_project FOREIGN KEY(project_id,tenant_id) REFERENCES platform_projects(id,tenant_id),
  CONSTRAINT fk_platform_blueprint_source_repo FOREIGN KEY(source_repository_id) REFERENCES platform_source_repositories(id),
  CONSTRAINT fk_platform_blueprint_agent_version FOREIGN KEY(generated_by_agent_version_id) REFERENCES platform_agent_versions(id)
 );
 CREATE UNIQUE INDEX uk_platform_blueprint_confirmed ON platform_project_blueprints(project_id) WHERE status='CONFIRMED';
END $workspace_blueprint$;
