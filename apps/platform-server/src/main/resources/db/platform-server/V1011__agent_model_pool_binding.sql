-- Immutable AgentVersion -> ModelPool compatibility binding.
DO $agent_model_pool_binding$
BEGIN
    IF to_regclass('public.platform_agent_versions') IS NULL
            OR to_regclass('public.platform_model_pools') IS NULL THEN
        RETURN;
    END IF;

    ALTER TABLE platform_agent_versions
        ADD COLUMN model_pool_id UUID;

    ALTER TABLE platform_agent_versions
        ADD CONSTRAINT fk_platform_agent_version_model_pool
            FOREIGN KEY (model_pool_id)
            REFERENCES platform_model_pools(id)
            ON DELETE RESTRICT,
        ADD CONSTRAINT ck_platform_agent_version_model_binding
            CHECK (
                model_pool_id IS NULL
                OR (model_provider_id IS NULL AND model_id IS NULL)
            );

    CREATE INDEX idx_platform_agent_version_model_pool
        ON platform_agent_versions(model_pool_id)
        WHERE model_pool_id IS NOT NULL;

    COMMENT ON COLUMN platform_agent_versions.model_pool_id IS
        'Stable ModelPool binding; mutually exclusive with legacy direct Provider/Model fields';
END
$agent_model_pool_binding$;
