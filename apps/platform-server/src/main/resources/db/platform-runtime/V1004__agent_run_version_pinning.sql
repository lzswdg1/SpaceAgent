ALTER TABLE platform_agent_runs
    ADD COLUMN IF NOT EXISTS agent_version_id UUID;

ALTER TABLE platform_agent_runs
    ADD CONSTRAINT fk_platform_agent_run_version
        FOREIGN KEY (agent_version_id)
        REFERENCES platform_agent_versions(id)
        ON DELETE RESTRICT;

CREATE INDEX IF NOT EXISTS idx_platform_agent_runs_version
    ON platform_agent_runs(agent_version_id);
