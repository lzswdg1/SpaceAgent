ALTER TABLE platform_project_directories DROP CONSTRAINT ck_platform_project_directory_authority;
ALTER TABLE platform_project_directories ADD CONSTRAINT ck_platform_project_directory_authority
    CHECK (source_repository_id IS NOT NULL OR is_default);

-- Preserve default directory IDs and all Conversation foreign keys. Each legacy blank
-- root gets its own managed empty source; physical initialization occurs in Java.
INSERT INTO platform_source_repositories
    (id,project_id,tenant_id,provider_repository_id,display_name,default_branch,
     repository_type,state,visibility,created_by,created_at,updated_at)
SELECT id,project_id,tenant_id,'managed:' || id::text,name,'main','GENERIC','PROVISIONING',
       'INTERNAL',created_by,created_at,updated_at
FROM platform_project_directories WHERE is_default AND source_repository_id IS NULL;

UPDATE platform_project_directories SET source_repository_id=id
WHERE is_default AND source_repository_id IS NULL;
