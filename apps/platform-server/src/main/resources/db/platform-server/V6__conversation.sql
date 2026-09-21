CREATE TABLE IF NOT EXISTS platform_conversations (
    id          VARCHAR(36) PRIMARY KEY,
    project_id  VARCHAR(36),
    task_id     VARCHAR(36),
    tenant_id   VARCHAR(36),
    user_id     VARCHAR(36) NOT NULL,
    agent_id    VARCHAR(36),
    title       VARCHAR(255) NOT NULL,
    status      VARCHAR(24) NOT NULL,
    created_at  TIMESTAMP NOT NULL,
    updated_at  TIMESTAMP NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_platform_conversations_user
    ON platform_conversations(user_id, updated_at DESC);

CREATE TABLE IF NOT EXISTS platform_messages (
    id              VARCHAR(36) PRIMARY KEY,
    conversation_id VARCHAR(36) NOT NULL REFERENCES platform_conversations(id) ON DELETE CASCADE,
    sequence_number INTEGER NOT NULL,
    role            VARCHAR(32) NOT NULL,
    content         TEXT NOT NULL,
    created_at      TIMESTAMP NOT NULL,
    CONSTRAINT uk_platform_message_conversation_sequence
        UNIQUE (conversation_id, sequence_number)
);

CREATE INDEX IF NOT EXISTS idx_platform_messages_conversation
    ON platform_messages(conversation_id, sequence_number);
