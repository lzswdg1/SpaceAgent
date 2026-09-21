CREATE TABLE IF NOT EXISTS platform_agent_runs (
    id                   VARCHAR(36) PRIMARY KEY,
    agent_definition_id  VARCHAR(36) NOT NULL,
    owner_id             VARCHAR(36) NOT NULL,
    conversation_id      VARCHAR(36) NOT NULL,
    project_id           VARCHAR(36),
    task_id              VARCHAR(36),
    state                VARCHAR(32) NOT NULL,
    failure_reason       TEXT,
    created_at           TIMESTAMP NOT NULL,
    updated_at           TIMESTAMP NOT NULL,
    completed_at         TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_platform_agent_runs_owner
    ON platform_agent_runs(owner_id, updated_at DESC);

CREATE INDEX IF NOT EXISTS idx_platform_agent_runs_conversation
    ON platform_agent_runs(conversation_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_platform_agent_runs_task
    ON platform_agent_runs(task_id, created_at DESC);

CREATE TABLE IF NOT EXISTS platform_run_steps (
    id           VARCHAR(36) PRIMARY KEY,
    agent_run_id VARCHAR(36) NOT NULL REFERENCES platform_agent_runs(id) ON DELETE CASCADE,
    sequence     INTEGER NOT NULL,
    type         VARCHAR(64) NOT NULL,
    state        VARCHAR(32) NOT NULL,
    created_at   TIMESTAMP NOT NULL,
    completed_at TIMESTAMP,
    CONSTRAINT uk_platform_run_step_sequence UNIQUE (agent_run_id, sequence)
);

CREATE INDEX IF NOT EXISTS idx_platform_run_steps_run
    ON platform_run_steps(agent_run_id, sequence);

CREATE TABLE IF NOT EXISTS platform_run_checkpoints (
    id             VARCHAR(36) PRIMARY KEY,
    agent_run_id   VARCHAR(36) NOT NULL REFERENCES platform_agent_runs(id) ON DELETE CASCADE,
    sequence       INTEGER NOT NULL,
    state_snapshot TEXT NOT NULL,
    created_at     TIMESTAMP NOT NULL,
    CONSTRAINT uk_platform_run_checkpoint_sequence UNIQUE (agent_run_id, sequence)
);

CREATE INDEX IF NOT EXISTS idx_platform_run_checkpoints_run
    ON platform_run_checkpoints(agent_run_id, sequence DESC);

CREATE TABLE IF NOT EXISTS platform_run_recoveries (
    id           VARCHAR(36) PRIMARY KEY,
    agent_run_id VARCHAR(36) NOT NULL REFERENCES platform_agent_runs(id) ON DELETE CASCADE,
    attempt      INTEGER NOT NULL,
    state        VARCHAR(32) NOT NULL,
    reason       TEXT NOT NULL,
    created_at   TIMESTAMP NOT NULL,
    completed_at TIMESTAMP,
    CONSTRAINT uk_platform_run_recovery_attempt UNIQUE (agent_run_id, attempt)
);

CREATE INDEX IF NOT EXISTS idx_platform_run_recoveries_run
    ON platform_run_recoveries(agent_run_id, attempt);

CREATE TABLE IF NOT EXISTS platform_run_handoffs (
    id                  VARCHAR(36) PRIMARY KEY,
    source_agent_run_id VARCHAR(36) NOT NULL REFERENCES platform_agent_runs(id) ON DELETE CASCADE,
    target_agent_run_id VARCHAR(36),
    state               VARCHAR(32) NOT NULL,
    goal                TEXT NOT NULL,
    current_state       TEXT NOT NULL,
    completed_work      TEXT NOT NULL,
    decisions           TEXT NOT NULL,
    failed_attempts     TEXT NOT NULL,
    changed_files       TEXT NOT NULL,
    test_status         VARCHAR(32) NOT NULL,
    blockers            TEXT NOT NULL,
    next_actions        TEXT NOT NULL,
    created_at          TIMESTAMP NOT NULL,
    completed_at        TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_platform_run_handoffs_source
    ON platform_run_handoffs(source_agent_run_id, created_at DESC);

CREATE TABLE IF NOT EXISTS platform_tool_execution_ledger (
    id              VARCHAR(36) PRIMARY KEY,
    agent_run_id    VARCHAR(36) NOT NULL REFERENCES platform_agent_runs(id) ON DELETE CASCADE,
    run_step_id     VARCHAR(36) NOT NULL REFERENCES platform_run_steps(id) ON DELETE CASCADE,
    tool_name       VARCHAR(160) NOT NULL,
    tool_call_id    VARCHAR(160) NOT NULL,
    idempotency_key VARCHAR(160),
    arguments       TEXT NOT NULL,
    input_hash      VARCHAR(160) NOT NULL,
    status          VARCHAR(32) NOT NULL,
    result          TEXT,
    result_ref      VARCHAR(1000),
    error           TEXT,
    started_at      TIMESTAMP NOT NULL,
    completed_at    TIMESTAMP,
    CONSTRAINT uk_platform_tool_ledger_call UNIQUE (agent_run_id, tool_call_id)
);

CREATE INDEX IF NOT EXISTS idx_platform_tool_ledger_run
    ON platform_tool_execution_ledger(agent_run_id, started_at);

CREATE TABLE IF NOT EXISTS platform_conversation_context_snapshots (
    id                     VARCHAR(36) PRIMARY KEY,
    conversation_id        VARCHAR(36) NOT NULL,
    version                INTEGER NOT NULL,
    summary                TEXT NOT NULL,
    from_message_sequence  INTEGER NOT NULL,
    to_message_sequence    INTEGER NOT NULL,
    token_count            INTEGER NOT NULL,
    checksum               VARCHAR(160) NOT NULL,
    created_at             TIMESTAMP NOT NULL,
    CONSTRAINT uk_platform_conversation_snapshot_version UNIQUE (conversation_id, version)
);

CREATE INDEX IF NOT EXISTS idx_platform_conversation_snapshot_conversation
    ON platform_conversation_context_snapshots(conversation_id, version DESC);
