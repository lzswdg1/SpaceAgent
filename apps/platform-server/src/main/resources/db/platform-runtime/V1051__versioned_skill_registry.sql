DO $skill_registry$
BEGIN
IF to_regclass('platform_tenants') IS NULL OR to_regclass('platform_users') IS NULL THEN
    RETURN;
END IF;

CREATE TABLE platform_skill_definitions (
    id UUID PRIMARY KEY,
    tenant_id VARCHAR(36) NOT NULL REFERENCES platform_tenants(id) ON DELETE CASCADE,
    owner_user_id VARCHAR(36) REFERENCES platform_users(id) ON DELETE SET NULL,
    name VARCHAR(100) NOT NULL,
    description VARCHAR(1000),
    lifecycle_state VARCHAR(16) NOT NULL,
    current_version_id UUID,
    revision BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    archived_at TIMESTAMPTZ,
    CONSTRAINT ck_platform_skill_definition_lifecycle
        CHECK (lifecycle_state IN ('ACTIVE', 'ARCHIVED')),
    CONSTRAINT ck_platform_skill_definition_revision CHECK (revision > 0),
    CONSTRAINT ck_platform_skill_definition_archive CHECK (
        (lifecycle_state = 'ACTIVE' AND archived_at IS NULL)
        OR (lifecycle_state = 'ARCHIVED' AND archived_at IS NOT NULL AND current_version_id IS NULL))
);

CREATE UNIQUE INDEX uk_platform_skill_definition_active_name
    ON platform_skill_definitions(tenant_id, LOWER(name))
    WHERE lifecycle_state = 'ACTIVE';
CREATE UNIQUE INDEX uk_platform_skill_definition_tenant_id
    ON platform_skill_definitions(tenant_id, id);

CREATE TABLE platform_skill_versions (
    id UUID PRIMARY KEY,
    skill_id UUID NOT NULL REFERENCES platform_skill_definitions(id) ON DELETE CASCADE,
    version_number INTEGER NOT NULL,
    lifecycle_state VARCHAR(16) NOT NULL,
    config_hash VARCHAR(64) NOT NULL,
    instructions TEXT NOT NULL,
    required_tool_ids JSONB NOT NULL DEFAULT '[]'::jsonb,
    created_by VARCHAR(36) REFERENCES platform_users(id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ NOT NULL,
    published_by VARCHAR(36) REFERENCES platform_users(id) ON DELETE SET NULL,
    published_at TIMESTAMPTZ,
    deprecated_by VARCHAR(36) REFERENCES platform_users(id) ON DELETE SET NULL,
    deprecated_at TIMESTAMPTZ,
    CONSTRAINT uk_platform_skill_version_number UNIQUE (skill_id, version_number),
    CONSTRAINT uk_platform_skill_version_skill_id UNIQUE (skill_id, id),
    CONSTRAINT ck_platform_skill_version_number CHECK (version_number > 0),
    CONSTRAINT ck_platform_skill_version_hash CHECK (config_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_platform_skill_version_instruction_size
        CHECK (OCTET_LENGTH(instructions) BETWEEN 1 AND 131072),
    CONSTRAINT ck_platform_skill_version_required_tools
        CHECK (jsonb_typeof(required_tool_ids) = 'array' AND jsonb_array_length(required_tool_ids) <= 32),
    CONSTRAINT ck_platform_skill_version_lifecycle CHECK (
        (lifecycle_state = 'DRAFT' AND published_at IS NULL AND deprecated_at IS NULL)
        OR (lifecycle_state = 'PUBLISHED' AND published_at IS NOT NULL AND deprecated_at IS NULL)
        OR (lifecycle_state = 'DEPRECATED' AND published_at IS NOT NULL AND deprecated_at IS NOT NULL))
);

ALTER TABLE platform_skill_definitions
    ADD CONSTRAINT fk_platform_skill_definition_current_version
    FOREIGN KEY (id, current_version_id)
    REFERENCES platform_skill_versions(skill_id, id)
    DEFERRABLE INITIALLY DEFERRED;

CREATE INDEX idx_platform_skill_version_skill_created
    ON platform_skill_versions(skill_id, version_number DESC);

COMMENT ON TABLE platform_skill_definitions IS
    'Organization-scoped Skill identity; instructions live only in immutable versions';
COMMENT ON TABLE platform_skill_versions IS
    'Bounded inert Skill instructions and declared Tool requirements; never executable code';
END
$skill_registry$;
