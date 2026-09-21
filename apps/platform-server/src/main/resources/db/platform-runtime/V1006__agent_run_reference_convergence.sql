ALTER TABLE platform_agent_runs
    RENAME COLUMN agent_definition_id TO agent_id;

ALTER TABLE platform_agent_runs
    ADD CONSTRAINT fk_platform_agent_run_agent
        FOREIGN KEY (agent_id)
        REFERENCES platform_agent_definitions(id)
        ON DELETE RESTRICT
        NOT VALID;

CREATE INDEX IF NOT EXISTS idx_platform_agent_runs_agent
    ON platform_agent_runs(agent_id, created_at DESC);
