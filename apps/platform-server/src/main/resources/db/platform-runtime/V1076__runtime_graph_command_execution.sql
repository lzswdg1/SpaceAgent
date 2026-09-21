ALTER TABLE platform_runtime_graph_commands
    ADD COLUMN command_kind VARCHAR(32),
    ADD COLUMN next_cursor_sequence BIGINT,
    ADD COLUMN payload_json JSONB,
    ADD COLUMN attempt INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN execution_owner VARCHAR(160),
    ADD COLUMN execution_token UUID,
    ADD COLUMN execution_fencing_token BIGINT,
    ADD COLUMN safe_error_code VARCHAR(120),
    DROP CONSTRAINT ck_runtime_graph_command;

UPDATE platform_runtime_graph_commands command
SET command_kind='WAIT_FOR_APPROVAL',
    next_cursor_sequence=session.cursor_sequence,
    payload_json='{}'::jsonb,
    state=CASE WHEN command.state='PENDING' THEN 'UNKNOWN' ELSE command.state END,
    safe_error_code=CASE
        WHEN command.state='PENDING' THEN 'GRAPH_COMMAND_LEGACY_UNPROVEN'
        WHEN command.state='BLOCKED' THEN 'GRAPH_COMMAND_LEGACY_BLOCKED'
        ELSE NULL END
FROM platform_runtime_graph_sessions session
WHERE session.id=command.graph_session_id;

UPDATE platform_runtime_graph_sessions
SET state='BLOCKED',pending_command_id=NULL,pending_input_hash=NULL,
    revision=revision+1,updated_at=CURRENT_TIMESTAMP
WHERE state='WAITING_FOR_COMMAND';

ALTER TABLE platform_runtime_graph_commands
    ALTER COLUMN command_kind SET NOT NULL,
    ALTER COLUMN next_cursor_sequence SET NOT NULL,
    ALTER COLUMN payload_json SET NOT NULL;
ALTER TABLE platform_runtime_graph_commands
    ADD CONSTRAINT ck_runtime_graph_command CHECK (
        input_hash ~ '^sha256:[0-9a-f]{64}$'
        AND command_kind IN ('MODEL_REQUESTED','TOOL_REQUESTED','DELEGATE_SUBTASK',
            'HANDOFF_PROPOSED','REVIEW_REQUIRED','WAIT_FOR_APPROVAL','COMPLETED')
        AND next_cursor_sequence > 0 AND attempt >= 0 AND jsonb_typeof(payload_json)='object'
        AND state IN ('PENDING','EXECUTING','CONFIRMED','BLOCKED','UNKNOWN')
        AND revision > 0
        AND (state='EXECUTING' OR (execution_owner IS NULL AND execution_token IS NULL
                AND execution_fencing_token IS NULL))
        AND (state<>'EXECUTING' OR (execution_owner IS NOT NULL
                AND execution_token IS NOT NULL AND execution_fencing_token IS NOT NULL))
        AND (state IN ('BLOCKED','UNKNOWN') OR safe_error_code IS NULL)
        AND (state NOT IN ('BLOCKED','UNKNOWN') OR safe_error_code IS NOT NULL)
    );

CREATE INDEX idx_runtime_graph_command_pending
    ON platform_runtime_graph_commands(created_at,graph_session_id,command_id)
    WHERE state='PENDING';
