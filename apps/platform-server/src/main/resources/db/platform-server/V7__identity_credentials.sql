CREATE TABLE IF NOT EXISTS platform_user_credentials (
    user_id       VARCHAR(36) PRIMARY KEY REFERENCES platform_users(id) ON DELETE CASCADE,
    username      VARCHAR(255) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    created_at    TIMESTAMP NOT NULL,
    updated_at    TIMESTAMP NOT NULL,
    CONSTRAINT uk_platform_user_credentials_username UNIQUE (username)
);
