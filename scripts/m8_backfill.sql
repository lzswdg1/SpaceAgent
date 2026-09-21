-- Re-runnable M8 legacy -> platform backfill job.
-- Inputs are psql variables: run_id, job_version, job_checksum,
-- identity_conn, agent_conn, chat_conn, knowledge_conn.

\set ON_ERROR_STOP on

CREATE EXTENSION IF NOT EXISTS dblink;

INSERT INTO platform_migration_runs (
    run_id, job_version, job_checksum, status, source_snapshot, started_at
) VALUES (
    :'run_id', :'job_version', :'job_checksum', 'RUNNING', '{}', CURRENT_TIMESTAMP
)
ON CONFLICT (run_id) DO UPDATE SET
    job_version = EXCLUDED.job_version,
    job_checksum = EXCLUDED.job_checksum,
    status = 'RUNNING',
    error_message = NULL,
    started_at = CURRENT_TIMESTAMP,
    completed_at = NULL;

-- ---------------------------------------------------------------------------
-- Identity
-- ---------------------------------------------------------------------------

CREATE TEMP TABLE stage_identity_tenants ON COMMIT PRESERVE ROWS AS
SELECT * FROM dblink(:'identity_conn', $remote$
    SELECT id::text, name, slug, status, created_at, updated_at
    FROM tenants
    ORDER BY id::text
$remote$) AS source(
    id TEXT,
    name TEXT,
    slug TEXT,
    status TEXT,
    created_at TIMESTAMP,
    updated_at TIMESTAMP
);

CREATE TEMP TABLE stage_identity_users ON COMMIT PRESERVE ROWS AS
SELECT * FROM dblink(:'identity_conn', $remote$
    SELECT u.public_id::text,
           COALESCE(
               (
                   SELECT membership.tenant_id::text
                   FROM tenant_memberships membership
                   WHERE membership.user_public_id = u.public_id
                     AND membership.status = 'ACTIVE'
                   ORDER BY
                       CASE WHEN membership.tenant_id = u.public_id THEN 1 ELSE 0 END DESC,
                       CASE WHEN membership.tenant_role = 'OWNER' THEN 1 ELSE 0 END DESC,
                       membership.joined_at,
                       membership.tenant_id::text
                   LIMIT 1
               ),
               u.public_id::text
           ) AS primary_tenant_id,
           u.username,
           u.password_hash,
           u.display_name,
           u.created_at,
           u.updated_at
    FROM users u
    ORDER BY u.public_id::text
$remote$) AS source(
    id TEXT,
    tenant_id TEXT,
    username TEXT,
    password_hash TEXT,
    display_name TEXT,
    created_at TIMESTAMP,
    updated_at TIMESTAMP
);

CREATE TEMP TABLE stage_identity_memberships ON COMMIT PRESERVE ROWS AS
SELECT * FROM dblink(:'identity_conn', $remote$
    SELECT tenant_id::text, user_public_id::text, tenant_role, status, joined_at, updated_at
    FROM tenant_memberships
    ORDER BY tenant_id::text, user_public_id::text
$remote$) AS source(
    tenant_id TEXT,
    user_id TEXT,
    tenant_role TEXT,
    status TEXT,
    joined_at TIMESTAMP,
    updated_at TIMESTAMP
);

CREATE TEMP TABLE stage_identity_profiles ON COMMIT PRESERVE ROWS AS
SELECT * FROM dblink(:'identity_conn', $remote$
    SELECT user_public_id::text,
           COALESCE(preferred_tone, 'FRIENDLY'),
           COALESCE(timezone, 'UTC'),
           COALESCE(profile_summary, ''),
           created_at,
           updated_at
    FROM user_profiles
    ORDER BY user_public_id::text
$remote$) AS source(
    user_id TEXT,
    preferred_tone TEXT,
    timezone TEXT,
    summary TEXT,
    created_at TIMESTAMP,
    updated_at TIMESTAMP
);

CREATE TEMP TABLE stage_identity_refresh_tokens ON COMMIT PRESERVE ROWS AS
SELECT * FROM dblink(:'identity_conn', $remote$
    SELECT refresh.token_hash::text,
           refresh.user_public_id::text,
           refresh.tenant_id::text,
           COALESCE(membership.tenant_role, 'OWNER'),
           refresh.expires_at,
           refresh.revoked_at,
           refresh.replaced_by_hash::text,
           refresh.created_at
    FROM refresh_tokens refresh
    LEFT JOIN tenant_memberships membership
      ON membership.tenant_id = refresh.tenant_id
     AND membership.user_public_id = refresh.user_public_id
    ORDER BY refresh.token_hash::text
$remote$) AS source(
    token_hash TEXT,
    user_id TEXT,
    tenant_id TEXT,
    tenant_role TEXT,
    expires_at TIMESTAMP,
    revoked_at TIMESTAMP,
    replaced_by_hash TEXT,
    created_at TIMESTAMP
);

BEGIN;

INSERT INTO platform_migration_watermarks (
    domain, status, started_at, updated_at
) VALUES ('identity', 'RUNNING', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT (domain) DO UPDATE SET
    status = 'RUNNING', started_at = CURRENT_TIMESTAMP,
    completed_at = NULL, updated_at = CURRENT_TIMESTAMP;

INSERT INTO platform_tenants (id, name, slug, status, created_at, updated_at)
SELECT id, name, slug, status, created_at, updated_at
FROM stage_identity_tenants
ON CONFLICT (id) DO UPDATE SET
    name = EXCLUDED.name,
    slug = EXCLUDED.slug,
    status = EXCLUDED.status,
    updated_at = EXCLUDED.updated_at;

INSERT INTO platform_users (id, tenant_id, external_id, display_name, created_at, updated_at)
SELECT id, tenant_id, username, display_name, created_at, updated_at
FROM stage_identity_users
ON CONFLICT (id) DO UPDATE SET
    tenant_id = EXCLUDED.tenant_id,
    external_id = EXCLUDED.external_id,
    display_name = EXCLUDED.display_name,
    updated_at = EXCLUDED.updated_at;

INSERT INTO platform_user_credentials (user_id, username, password_hash, created_at, updated_at)
SELECT id, username, password_hash, created_at, updated_at
FROM stage_identity_users
ON CONFLICT (user_id) DO UPDATE SET
    username = EXCLUDED.username,
    password_hash = EXCLUDED.password_hash,
    updated_at = EXCLUDED.updated_at;

INSERT INTO platform_tenant_memberships (
    tenant_id, user_id, tenant_role, status, joined_at, updated_at
)
SELECT tenant_id, user_id, tenant_role, status, joined_at, updated_at
FROM stage_identity_memberships
ON CONFLICT (tenant_id, user_id) DO UPDATE SET
    tenant_role = EXCLUDED.tenant_role,
    status = EXCLUDED.status,
    updated_at = EXCLUDED.updated_at;

INSERT INTO platform_user_profiles (
    user_id, preferred_tone, timezone, summary, created_at, updated_at
)
SELECT user_id, preferred_tone, timezone, summary, created_at, updated_at
FROM stage_identity_profiles
ON CONFLICT (user_id) DO UPDATE SET
    preferred_tone = EXCLUDED.preferred_tone,
    timezone = EXCLUDED.timezone,
    summary = EXCLUDED.summary,
    updated_at = EXCLUDED.updated_at;

INSERT INTO platform_refresh_tokens (
    token_hash, user_id, tenant_id, tenant_role, expires_at,
    revoked_at, replaced_by_hash, created_at
)
SELECT token_hash, user_id, tenant_id, tenant_role, expires_at,
       revoked_at, NULLIF(replaced_by_hash, ''), created_at
FROM stage_identity_refresh_tokens
ON CONFLICT (token_hash) DO UPDATE SET
    user_id = EXCLUDED.user_id,
    tenant_id = EXCLUDED.tenant_id,
    tenant_role = EXCLUDED.tenant_role,
    expires_at = EXCLUDED.expires_at,
    revoked_at = EXCLUDED.revoked_at,
    replaced_by_hash = EXCLUDED.replaced_by_hash;

INSERT INTO platform_migration_id_map (
    domain, entity_type, legacy_id, platform_id, migrated_at
)
SELECT 'identity', 'tenant', id, id, CURRENT_TIMESTAMP FROM stage_identity_tenants
UNION ALL
SELECT 'identity', 'user', id, id, CURRENT_TIMESTAMP FROM stage_identity_users
UNION ALL
SELECT 'identity', 'membership', tenant_id || '|' || user_id,
       tenant_id || '|' || user_id, CURRENT_TIMESTAMP FROM stage_identity_memberships
ON CONFLICT (domain, entity_type, legacy_id) DO UPDATE SET
    platform_id = EXCLUDED.platform_id,
    migrated_at = EXCLUDED.migrated_at;

UPDATE platform_migration_watermarks SET
    last_migrated_id = (SELECT max(id) FROM stage_identity_users),
    source_count = (SELECT count(*) FROM stage_identity_users)
                 + (SELECT count(*) FROM stage_identity_tenants)
                 + (SELECT count(*) FROM stage_identity_memberships),
    target_count = (SELECT count(*) FROM platform_users)
                 + (SELECT count(*) FROM platform_tenants)
                 + (SELECT count(*) FROM platform_tenant_memberships),
    source_checksum = md5(
        (SELECT md5(COALESCE(string_agg(id || ':' || username, ',' ORDER BY id), '')) FROM stage_identity_users)
        || ':' ||
        (SELECT md5(COALESCE(string_agg(id || ':' || slug, ',' ORDER BY id), '')) FROM stage_identity_tenants)
        || ':' ||
        (SELECT md5(COALESCE(string_agg(tenant_id || ':' || user_id || ':' || tenant_role,
                                         ',' ORDER BY tenant_id, user_id), ''))
         FROM stage_identity_memberships)
    ),
    target_checksum = md5(
        (SELECT md5(COALESCE(string_agg(id || ':' || external_id, ',' ORDER BY id), '')) FROM platform_users)
        || ':' ||
        (SELECT md5(COALESCE(string_agg(id || ':' || slug, ',' ORDER BY id), '')) FROM platform_tenants)
        || ':' ||
        (SELECT md5(COALESCE(string_agg(tenant_id || ':' || user_id || ':' || tenant_role,
                                         ',' ORDER BY tenant_id, user_id), ''))
         FROM platform_tenant_memberships)
    ),
    status = 'MIGRATED',
    completed_at = CURRENT_TIMESTAMP,
    updated_at = CURRENT_TIMESTAMP
WHERE domain = 'identity';

COMMIT;

-- ---------------------------------------------------------------------------
-- Agent + inference/provider references
-- ---------------------------------------------------------------------------

CREATE TEMP TABLE stage_agent_providers ON COMMIT PRESERVE ROWS AS
SELECT * FROM dblink(:'agent_conn', $remote$
    SELECT id, tenant_id, owner_user_id, name, provider_type, base_url,
           api_key_ciphertext, auth_type, enabled, is_default, created_at, updated_at
    FROM model_providers
    ORDER BY id
$remote$) AS source(
    id TEXT,
    tenant_id TEXT,
    owner_id TEXT,
    name TEXT,
    provider_type TEXT,
    base_url TEXT,
    api_key_ciphertext TEXT,
    auth_type TEXT,
    enabled BOOLEAN,
    is_default BOOLEAN,
    created_at TIMESTAMP,
    updated_at TIMESTAMP
);

CREATE TEMP TABLE stage_agent_models ON COMMIT PRESERVE ROWS AS
SELECT * FROM dblink(:'agent_conn', $remote$
    SELECT id, provider_id, model_id, display_name, max_context_tokens,
           is_default, created_at
    FROM provider_models
    ORDER BY id
$remote$) AS source(
    id TEXT,
    provider_id TEXT,
    model_id TEXT,
    display_name TEXT,
    max_context_tokens INTEGER,
    is_default BOOLEAN,
    created_at TIMESTAMP
);

CREATE TEMP TABLE stage_agent_configurations ON COMMIT PRESERVE ROWS AS
SELECT * FROM dblink(:'agent_conn', $remote$
    SELECT config.id,
           config.created_by,
           config.tenant_id,
           config.name,
           config.description,
           config.system_prompt,
           config.model_provider_id,
           config.model_id,
           config.temperature,
           config.max_tokens,
           config.max_turns,
           config.permission_mode,
           config.memory_enabled,
           config.rag_enabled,
           config.network_enabled,
           COALESCE(
               (SELECT jsonb_agg(binding.knowledge_base_id ORDER BY binding.knowledge_base_id)
                FROM agent_knowledge_bases binding WHERE binding.agent_id = config.id),
               '[]'::jsonb
           )::text,
           config.enabled_tool_ids::text,
           config.skill_ids::text,
           config.config_version,
           config.created_at,
           config.updated_at
    FROM agent_configs config
    ORDER BY config.id
$remote$) AS source(
    id TEXT,
    owner_id TEXT,
    tenant_id TEXT,
    name TEXT,
    description TEXT,
    system_prompt TEXT,
    model_provider_id TEXT,
    model_id TEXT,
    temperature DOUBLE PRECISION,
    max_tokens INTEGER,
    max_turns INTEGER,
    permission_mode TEXT,
    memory_enabled BOOLEAN,
    rag_enabled BOOLEAN,
    network_enabled BOOLEAN,
    knowledge_base_ids TEXT,
    enabled_tool_ids TEXT,
    skill_ids TEXT,
    config_version BIGINT,
    created_at TIMESTAMP,
    updated_at TIMESTAMP
);

CREATE TEMP TABLE stage_agent_bindings ON COMMIT PRESERVE ROWS AS
SELECT * FROM dblink(:'agent_conn', $remote$
    SELECT agent_id, knowledge_base_id
    FROM agent_knowledge_bases
    ORDER BY agent_id, knowledge_base_id
$remote$) AS source(agent_id TEXT, knowledge_id TEXT);

CREATE TEMP TABLE stage_agent_api_keys ON COMMIT PRESERVE ROWS AS
SELECT * FROM dblink(:'agent_conn', $remote$
    SELECT id, agent_id, name, key_hash, key_prefix, scopes, enabled,
           created_at, last_used_at, expires_at, revoked_at
    FROM agent_api_keys
    ORDER BY id
$remote$) AS source(
    id TEXT,
    agent_id TEXT,
    name TEXT,
    key_hash TEXT,
    key_prefix TEXT,
    scopes TEXT,
    enabled BOOLEAN,
    created_at TIMESTAMP,
    last_used_at TIMESTAMP,
    expires_at TIMESTAMP,
    revoked_at TIMESTAMP
);

BEGIN;

INSERT INTO platform_migration_watermarks (domain, status, started_at, updated_at)
VALUES ('agent', 'RUNNING', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT (domain) DO UPDATE SET
    status = 'RUNNING', started_at = CURRENT_TIMESTAMP,
    completed_at = NULL, updated_at = CURRENT_TIMESTAMP;

INSERT INTO platform_model_providers (
    id, tenant_id, owner_id, name, provider_type, base_url,
    api_key_ciphertext, auth_type, enabled, is_default, created_at, updated_at
)
SELECT id, tenant_id, owner_id, name, provider_type, base_url,
       api_key_ciphertext, auth_type, enabled, is_default, created_at, updated_at
FROM stage_agent_providers
ON CONFLICT (id) DO UPDATE SET
    tenant_id = EXCLUDED.tenant_id,
    owner_id = EXCLUDED.owner_id,
    name = EXCLUDED.name,
    provider_type = EXCLUDED.provider_type,
    base_url = EXCLUDED.base_url,
    api_key_ciphertext = EXCLUDED.api_key_ciphertext,
    auth_type = EXCLUDED.auth_type,
    enabled = EXCLUDED.enabled,
    is_default = EXCLUDED.is_default,
    updated_at = EXCLUDED.updated_at;

INSERT INTO platform_provider_models (
    id, provider_id, model_id, display_name, max_context_tokens, is_default, created_at
)
SELECT id, provider_id, model_id, display_name, max_context_tokens, is_default, created_at
FROM stage_agent_models
ON CONFLICT (id) DO UPDATE SET
    provider_id = EXCLUDED.provider_id,
    model_id = EXCLUDED.model_id,
    display_name = EXCLUDED.display_name,
    max_context_tokens = EXCLUDED.max_context_tokens,
    is_default = EXCLUDED.is_default;

INSERT INTO platform_agent_definitions (
    id, owner_id, name, version, configuration, status, created_at, updated_at
)
SELECT id,
       owner_id,
       name,
       config_version::text,
       jsonb_build_object(
           'systemPrompt', system_prompt,
           'modelProviderId', model_provider_id,
           'modelId', model_id,
           'temperature', temperature,
           'maxTokens', max_tokens,
           'maxTurns', max_turns,
           'permissionMode', permission_mode,
           'memoryEnabled', memory_enabled,
           'ragEnabled', rag_enabled,
           'networkEnabled', network_enabled
       )::text,
       'ACTIVE',
       created_at,
       updated_at
FROM stage_agent_configurations
ON CONFLICT (id) DO UPDATE SET
    owner_id = EXCLUDED.owner_id,
    name = EXCLUDED.name,
    version = EXCLUDED.version,
    configuration = EXCLUDED.configuration,
    status = EXCLUDED.status,
    updated_at = EXCLUDED.updated_at;

INSERT INTO platform_agent_configurations (
    id, owner_id, tenant_id, name, description, system_prompt,
    model_provider_id, model_id, temperature, max_tokens, max_turns,
    permission_mode, memory_enabled, rag_enabled, network_enabled,
    knowledge_base_ids, enabled_tool_ids, skill_ids, config_version,
    created_at, updated_at
)
SELECT id, owner_id, tenant_id, name, description, system_prompt,
       model_provider_id, model_id, temperature, max_tokens, max_turns,
       permission_mode, memory_enabled, rag_enabled, network_enabled,
       knowledge_base_ids, enabled_tool_ids, skill_ids, config_version,
       created_at, updated_at
FROM stage_agent_configurations
ON CONFLICT (id) DO UPDATE SET
    owner_id = EXCLUDED.owner_id,
    tenant_id = EXCLUDED.tenant_id,
    name = EXCLUDED.name,
    description = EXCLUDED.description,
    system_prompt = EXCLUDED.system_prompt,
    model_provider_id = EXCLUDED.model_provider_id,
    model_id = EXCLUDED.model_id,
    temperature = EXCLUDED.temperature,
    max_tokens = EXCLUDED.max_tokens,
    max_turns = EXCLUDED.max_turns,
    permission_mode = EXCLUDED.permission_mode,
    memory_enabled = EXCLUDED.memory_enabled,
    rag_enabled = EXCLUDED.rag_enabled,
    network_enabled = EXCLUDED.network_enabled,
    knowledge_base_ids = EXCLUDED.knowledge_base_ids,
    enabled_tool_ids = EXCLUDED.enabled_tool_ids,
    skill_ids = EXCLUDED.skill_ids,
    config_version = EXCLUDED.config_version,
    updated_at = EXCLUDED.updated_at;

DELETE FROM platform_agent_knowledge_bindings
WHERE agent_id IN (SELECT id FROM stage_agent_configurations);

INSERT INTO platform_agent_knowledge_bindings (agent_id, knowledge_base_id)
SELECT agent_id, knowledge_id FROM stage_agent_bindings
ON CONFLICT (agent_id, knowledge_base_id) DO NOTHING;

INSERT INTO platform_agent_api_keys (
    id, agent_id, name, key_hash, key_prefix, scopes, enabled,
    created_at, last_used_at, expires_at, revoked_at
)
SELECT id, agent_id, name, key_hash, key_prefix, scopes, enabled,
       created_at, last_used_at, expires_at, revoked_at
FROM stage_agent_api_keys
ON CONFLICT (id) DO UPDATE SET
    agent_id = EXCLUDED.agent_id,
    name = EXCLUDED.name,
    key_hash = EXCLUDED.key_hash,
    key_prefix = EXCLUDED.key_prefix,
    scopes = EXCLUDED.scopes,
    enabled = EXCLUDED.enabled,
    last_used_at = EXCLUDED.last_used_at,
    expires_at = EXCLUDED.expires_at,
    revoked_at = EXCLUDED.revoked_at;

INSERT INTO platform_migration_id_map (
    domain, entity_type, legacy_id, platform_id, migrated_at
)
SELECT 'agent', 'agent_definition', id, id, CURRENT_TIMESTAMP FROM stage_agent_configurations
UNION ALL
SELECT 'agent', 'agent_configuration', id, id, CURRENT_TIMESTAMP FROM stage_agent_configurations
UNION ALL
SELECT 'agent', 'api_key', id, id, CURRENT_TIMESTAMP FROM stage_agent_api_keys
UNION ALL
SELECT 'inference', 'model_provider', id, id, CURRENT_TIMESTAMP FROM stage_agent_providers
UNION ALL
SELECT 'inference', 'provider_model', id, id, CURRENT_TIMESTAMP FROM stage_agent_models
ON CONFLICT (domain, entity_type, legacy_id) DO UPDATE SET
    platform_id = EXCLUDED.platform_id,
    migrated_at = EXCLUDED.migrated_at;

UPDATE platform_migration_watermarks SET
    last_migrated_id = (SELECT max(id) FROM stage_agent_configurations),
    source_count = (SELECT count(*) FROM stage_agent_configurations)
                 + (SELECT count(*) FROM stage_agent_api_keys)
                 + (SELECT count(*) FROM stage_agent_bindings),
    target_count = (SELECT count(*) FROM platform_agent_configurations)
                 + (SELECT count(*) FROM platform_agent_api_keys)
                 + (SELECT count(*) FROM platform_agent_knowledge_bindings),
    source_checksum = md5(
        (SELECT md5(COALESCE(string_agg(id || ':' || name || ':' || config_version,
                                         ',' ORDER BY id), '')) FROM stage_agent_configurations)
        || ':' ||
        (SELECT md5(COALESCE(string_agg(id || ':' || agent_id || ':' || key_hash,
                                         ',' ORDER BY id), '')) FROM stage_agent_api_keys)
    ),
    target_checksum = md5(
        (SELECT md5(COALESCE(string_agg(id || ':' || name || ':' || config_version,
                                         ',' ORDER BY id), '')) FROM platform_agent_configurations)
        || ':' ||
        (SELECT md5(COALESCE(string_agg(id || ':' || agent_id || ':' || key_hash,
                                         ',' ORDER BY id), '')) FROM platform_agent_api_keys)
    ),
    status = 'MIGRATED',
    completed_at = CURRENT_TIMESTAMP,
    updated_at = CURRENT_TIMESTAMP
WHERE domain = 'agent';

COMMIT;

-- ---------------------------------------------------------------------------
-- Conversation/chat
-- ---------------------------------------------------------------------------

CREATE TEMP TABLE stage_chat_conversations ON COMMIT PRESERVE ROWS AS
SELECT * FROM dblink(:'chat_conn', $remote$
    SELECT public_id::text,
           tenant_id::text,
           user_id::text,
           agent_id::text,
           COALESCE(title, 'New conversation'),
           status,
           created_at,
           updated_at
    FROM conversations
    ORDER BY public_id::text
$remote$) AS source(
    id TEXT,
    tenant_id TEXT,
    user_id TEXT,
    agent_id TEXT,
    title TEXT,
    status TEXT,
    created_at TIMESTAMP,
    updated_at TIMESTAMP
);

CREATE TEMP TABLE stage_chat_messages ON COMMIT PRESERVE ROWS AS
SELECT * FROM dblink(:'chat_conn', $remote$
    SELECT public_id::text,
           conversation_id::text,
           (row_number() OVER (
               PARTITION BY conversation_id ORDER BY created_at, id
           ) - 1)::integer AS sequence_number,
           role,
           content,
           created_at
    FROM chat_messages
    ORDER BY conversation_id::text, created_at, id
$remote$) AS source(
    id TEXT,
    conversation_id TEXT,
    sequence_number INTEGER,
    role TEXT,
    content TEXT,
    created_at TIMESTAMP
);

CREATE TEMP TABLE stage_chat_snapshots ON COMMIT PRESERVE ROWS AS
SELECT * FROM dblink(:'chat_conn', $remote$
    SELECT md5(conversation_id::text || ':' || version::text)::uuid::text,
           conversation_id::text,
           version::integer,
           summary,
           0,
           GREATEST(source_message_count - 1, 0),
           estimated_tokens,
           'md5:' || md5(summary),
           created_at
    FROM conversation_context_summaries
    ORDER BY conversation_id::text, version
$remote$) AS source(
    id TEXT,
    conversation_id TEXT,
    version INTEGER,
    summary TEXT,
    from_sequence INTEGER,
    to_sequence INTEGER,
    token_count INTEGER,
    checksum TEXT,
    created_at TIMESTAMP
);

BEGIN;

INSERT INTO platform_migration_watermarks (domain, status, started_at, updated_at)
VALUES ('chat', 'RUNNING', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT (domain) DO UPDATE SET
    status = 'RUNNING', started_at = CURRENT_TIMESTAMP,
    completed_at = NULL, updated_at = CURRENT_TIMESTAMP;

INSERT INTO platform_conversations (
    id, project_id, task_id, tenant_id, user_id, agent_id,
    title, status, created_at, updated_at
)
SELECT id, NULL, NULL, tenant_id, user_id, NULLIF(agent_id, ''),
       title, status, created_at, updated_at
FROM stage_chat_conversations
ON CONFLICT (id) DO UPDATE SET
    tenant_id = EXCLUDED.tenant_id,
    user_id = EXCLUDED.user_id,
    agent_id = EXCLUDED.agent_id,
    title = EXCLUDED.title,
    status = EXCLUDED.status,
    updated_at = EXCLUDED.updated_at;

INSERT INTO platform_messages (
    id, conversation_id, sequence_number, role, content, created_at
)
SELECT id, conversation_id, sequence_number, role, content, created_at
FROM stage_chat_messages
ON CONFLICT (id) DO UPDATE SET
    conversation_id = EXCLUDED.conversation_id,
    sequence_number = EXCLUDED.sequence_number,
    role = EXCLUDED.role,
    content = EXCLUDED.content;

INSERT INTO platform_conversation_context_snapshots (
    id, conversation_id, version, summary, from_message_sequence,
    to_message_sequence, token_count, checksum, created_at
)
SELECT id, conversation_id, version, summary, from_sequence,
       to_sequence, token_count, checksum, created_at
FROM stage_chat_snapshots
ON CONFLICT (conversation_id, version) DO UPDATE SET
    summary = EXCLUDED.summary,
    from_message_sequence = EXCLUDED.from_message_sequence,
    to_message_sequence = EXCLUDED.to_message_sequence,
    token_count = EXCLUDED.token_count,
    checksum = EXCLUDED.checksum;

INSERT INTO platform_migration_id_map (
    domain, entity_type, legacy_id, platform_id, migrated_at
)
SELECT 'chat', 'conversation', id, id, CURRENT_TIMESTAMP FROM stage_chat_conversations
UNION ALL
SELECT 'chat', 'message', id, id, CURRENT_TIMESTAMP FROM stage_chat_messages
UNION ALL
SELECT 'chat', 'context_snapshot', conversation_id || '|' || version::text,
       id, CURRENT_TIMESTAMP FROM stage_chat_snapshots
ON CONFLICT (domain, entity_type, legacy_id) DO UPDATE SET
    platform_id = EXCLUDED.platform_id,
    migrated_at = EXCLUDED.migrated_at;

UPDATE platform_migration_watermarks SET
    last_migrated_id = (SELECT max(id) FROM stage_chat_messages),
    source_count = (SELECT count(*) FROM stage_chat_conversations)
                 + (SELECT count(*) FROM stage_chat_messages)
                 + (SELECT count(*) FROM stage_chat_snapshots),
    target_count = (SELECT count(*) FROM platform_conversations)
                 + (SELECT count(*) FROM platform_messages)
                 + (SELECT count(*) FROM platform_conversation_context_snapshots),
    source_checksum = md5(
        (SELECT md5(COALESCE(string_agg(id || ':' || user_id || ':' || status,
                                         ',' ORDER BY id), '')) FROM stage_chat_conversations)
        || ':' ||
        (SELECT md5(COALESCE(string_agg(id || ':' || conversation_id || ':' || role || ':' || md5(content),
                                         ',' ORDER BY id), '')) FROM stage_chat_messages)
    ),
    target_checksum = md5(
        (SELECT md5(COALESCE(string_agg(id || ':' || user_id || ':' || status,
                                         ',' ORDER BY id), '')) FROM platform_conversations)
        || ':' ||
        (SELECT md5(COALESCE(string_agg(id || ':' || conversation_id || ':' || role || ':' || md5(content),
                                         ',' ORDER BY id), '')) FROM platform_messages)
    ),
    status = 'MIGRATED',
    completed_at = CURRENT_TIMESTAMP,
    updated_at = CURRENT_TIMESTAMP
WHERE domain = 'chat';

COMMIT;

-- ---------------------------------------------------------------------------
-- Knowledge
-- ---------------------------------------------------------------------------

CREATE TEMP TABLE stage_knowledge_documents ON COMMIT PRESERVE ROWS AS
SELECT * FROM dblink(:'knowledge_conn', $remote$
    SELECT id,
           user_id,
           file_name,
           content_type,
           'legacy://knowledge/' || id || '/' || file_name,
           CASE
               WHEN status IN ('COMPLETED', 'READY') THEN 'READY'
               WHEN status = 'FAILED' THEN 'FAILED'
               WHEN status = 'PROCESSING' THEN 'PROCESSING'
               ELSE 'UPLOADED'
           END,
           error_reason,
           created_at,
           COALESCE(processed_at, created_at)
    FROM knowledge_documents
    ORDER BY id
$remote$) AS source(
    id TEXT,
    owner_id TEXT,
    name TEXT,
    content_type TEXT,
    storage_location TEXT,
    status TEXT,
    error_reason TEXT,
    created_at TIMESTAMP,
    updated_at TIMESTAMP
);

CREATE TEMP TABLE stage_knowledge_chunks ON COMMIT PRESERVE ROWS AS
SELECT * FROM dblink(:'knowledge_conn', $remote$
    SELECT id,
           document_id,
           chunk_index,
           content,
           CASE WHEN embedding IS NULL THEN NULL ELSE 'legacy-vector:' || id END,
           md5(content) || md5(content),
           CASE WHEN embedding IS NULL THEN NULL ELSE 'legacy-preserved' END,
           CASE WHEN embedding IS NULL THEN 0 ELSE vector_dims(embedding) END,
           CASE WHEN embedding IS NULL THEN '[]' ELSE embedding::text END,
           created_at
    FROM knowledge_chunks
    ORDER BY id
$remote$) AS source(
    id TEXT,
    document_id TEXT,
    sequence_number INTEGER,
    content TEXT,
    embedding_reference TEXT,
    content_hash TEXT,
    embedding_model TEXT,
    embedding_dimensions INTEGER,
    embedding_vector TEXT,
    created_at TIMESTAMP
);

BEGIN;

INSERT INTO platform_migration_watermarks (domain, status, started_at, updated_at)
VALUES ('knowledge', 'RUNNING', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT (domain) DO UPDATE SET
    status = 'RUNNING', started_at = CURRENT_TIMESTAMP,
    completed_at = NULL, updated_at = CURRENT_TIMESTAMP;

INSERT INTO platform_knowledge_documents (
    id, owner_id, name, content_type, storage_location, status,
    error_reason, created_at, updated_at
)
SELECT id, owner_id, name, content_type, storage_location, status,
       error_reason, created_at, updated_at
FROM stage_knowledge_documents
ON CONFLICT (id) DO UPDATE SET
    owner_id = EXCLUDED.owner_id,
    name = EXCLUDED.name,
    content_type = EXCLUDED.content_type,
    storage_location = EXCLUDED.storage_location,
    status = EXCLUDED.status,
    error_reason = EXCLUDED.error_reason,
    updated_at = EXCLUDED.updated_at;

INSERT INTO platform_knowledge_chunks (
    id, document_id, sequence_number, content, embedding_reference,
    content_hash, embedding_model, embedding_dimensions, embedding_vector, created_at
)
SELECT id, document_id, sequence_number, content, embedding_reference,
       content_hash, embedding_model, embedding_dimensions, embedding_vector, created_at
FROM stage_knowledge_chunks
ON CONFLICT (id) DO UPDATE SET
    document_id = EXCLUDED.document_id,
    sequence_number = EXCLUDED.sequence_number,
    content = EXCLUDED.content,
    embedding_reference = EXCLUDED.embedding_reference,
    content_hash = EXCLUDED.content_hash,
    embedding_model = EXCLUDED.embedding_model,
    embedding_dimensions = EXCLUDED.embedding_dimensions,
    embedding_vector = EXCLUDED.embedding_vector;

INSERT INTO platform_migration_id_map (
    domain, entity_type, legacy_id, platform_id, migrated_at
)
SELECT 'knowledge', 'document', id, id, CURRENT_TIMESTAMP FROM stage_knowledge_documents
UNION ALL
SELECT 'knowledge', 'chunk', id, id, CURRENT_TIMESTAMP FROM stage_knowledge_chunks
ON CONFLICT (domain, entity_type, legacy_id) DO UPDATE SET
    platform_id = EXCLUDED.platform_id,
    migrated_at = EXCLUDED.migrated_at;

UPDATE platform_migration_watermarks SET
    last_migrated_id = (SELECT max(id) FROM stage_knowledge_chunks),
    source_count = (SELECT count(*) FROM stage_knowledge_documents)
                 + (SELECT count(*) FROM stage_knowledge_chunks),
    target_count = (SELECT count(*) FROM platform_knowledge_documents)
                 + (SELECT count(*) FROM platform_knowledge_chunks),
    source_checksum = md5(
        (SELECT md5(COALESCE(string_agg(id || ':' || owner_id || ':' || status,
                                         ',' ORDER BY id), '')) FROM stage_knowledge_documents)
        || ':' ||
        (SELECT md5(COALESCE(string_agg(id || ':' || document_id || ':' || sequence_number || ':' || md5(content),
                                         ',' ORDER BY id), '')) FROM stage_knowledge_chunks)
    ),
    target_checksum = md5(
        (SELECT md5(COALESCE(string_agg(id || ':' || owner_id || ':' || status,
                                         ',' ORDER BY id), '')) FROM platform_knowledge_documents)
        || ':' ||
        (SELECT md5(COALESCE(string_agg(id || ':' || document_id || ':' || sequence_number || ':' || md5(content),
                                         ',' ORDER BY id), '')) FROM platform_knowledge_chunks)
    ),
    status = 'MIGRATED',
    completed_at = CURRENT_TIMESTAMP,
    updated_at = CURRENT_TIMESTAMP
WHERE domain = 'knowledge';

COMMIT;

UPDATE platform_migration_runs SET
    status = 'BACKFILLED',
    source_snapshot = jsonb_build_object(
        'identity', (SELECT source_count FROM platform_migration_watermarks WHERE domain = 'identity'),
        'agent', (SELECT source_count FROM platform_migration_watermarks WHERE domain = 'agent'),
        'chat', (SELECT source_count FROM platform_migration_watermarks WHERE domain = 'chat'),
        'knowledge', (SELECT source_count FROM platform_migration_watermarks WHERE domain = 'knowledge')
    )::text,
    target_snapshot = jsonb_build_object(
        'identity', (SELECT target_count FROM platform_migration_watermarks WHERE domain = 'identity'),
        'agent', (SELECT target_count FROM platform_migration_watermarks WHERE domain = 'agent'),
        'chat', (SELECT target_count FROM platform_migration_watermarks WHERE domain = 'chat'),
        'knowledge', (SELECT target_count FROM platform_migration_watermarks WHERE domain = 'knowledge')
    )::text,
    completed_at = CURRENT_TIMESTAMP
WHERE run_id = :'run_id';

SELECT domain, last_migrated_id, source_count, target_count,
       source_checksum, target_checksum, status, completed_at
FROM platform_migration_watermarks
ORDER BY domain;
