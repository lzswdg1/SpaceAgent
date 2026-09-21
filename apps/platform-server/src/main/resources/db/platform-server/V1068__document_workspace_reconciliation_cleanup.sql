DO $document_workspace_reconciliation_cleanup$ BEGIN
 IF to_regclass('public.platform_document_workspace_operations') IS NULL THEN RETURN; END IF;
 ALTER TABLE platform_document_workspace_operations ADD COLUMN agent_run_id VARCHAR(200) NOT NULL DEFAULT 'legacy-document-workspace';
 ALTER TABLE platform_document_workspace_operations ADD COLUMN run_step_id VARCHAR(200) NOT NULL DEFAULT 'legacy-document-workspace';
 ALTER TABLE platform_document_workspace_operations ALTER COLUMN agent_run_id DROP DEFAULT;
 ALTER TABLE platform_document_workspace_operations ALTER COLUMN run_step_id DROP DEFAULT;
 CREATE INDEX idx_document_workspace_operation_run ON platform_document_workspace_operations(tenant_id,agent_run_id,tool_call_id);
 IF to_regclass('public.platform_organization_cleanup_steps') IS NOT NULL THEN
  ALTER TABLE platform_organization_cleanup_steps DROP CONSTRAINT ck_platform_organization_cleanup_step_key;
  ALTER TABLE platform_organization_cleanup_steps ADD CONSTRAINT ck_platform_organization_cleanup_step_key CHECK(step_key IN('AUTOMATION_FREEZE_PURGE','RUNTIME_QUIESCE','ARTIFACT_PURGE','RUNTIME_PURGE','CONVERSATION_PURGE','TOOLING_CONFIGURATION_PURGE','PROJECT_TASK_MEMORY_PURGE','KNOWLEDGE_PURGE','PROJECT_EXTERNAL_AND_DATABASE_PURGE','AGENT_PURGE','INFERENCE_PURGE','GOVERNANCE_PURGE','IDENTITY_FINALIZE'));
  INSERT INTO platform_organization_cleanup_steps(organization_id,step_key,step_sequence,state,attempt,created_at,updated_at) SELECT organization_id,'KNOWLEDGE_PURGE',650,'PENDING',0,clock_timestamp(),clock_timestamp() FROM platform_organization_cleanup_jobs WHERE state<>'COMPLETED' ON CONFLICT DO NOTHING;
 END IF;
END $document_workspace_reconciliation_cleanup$;
