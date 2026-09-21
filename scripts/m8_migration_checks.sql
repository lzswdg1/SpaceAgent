-- Strict platform-side M8 cutover invariants.
-- Any violation raises an exception, so psql -v ON_ERROR_STOP=1 exits non-zero.

\set ON_ERROR_STOP on

DO $$
DECLARE
    required_table TEXT;
    affected BIGINT;
BEGIN
    FOREACH required_table IN ARRAY ARRAY[
        'platform_tenants',
        'platform_users',
        'platform_tenant_memberships',
        'platform_user_credentials',
        'platform_refresh_tokens',
        'platform_agent_configurations',
        'platform_agent_api_keys',
        'platform_agent_knowledge_bindings',
        'platform_model_providers',
        'platform_provider_models',
        'platform_conversations',
        'platform_messages',
        'platform_knowledge_documents',
        'platform_knowledge_chunks',
        'platform_agent_runs',
        'platform_run_steps',
        'platform_run_checkpoints',
        'platform_tool_execution_ledger'
        ,'platform_migration_runs'
        ,'platform_migration_watermarks'
        ,'platform_migration_id_map'
    ] LOOP
        IF to_regclass('public.' || required_table) IS NULL THEN
            RAISE EXCEPTION 'M8 validation failed: required platform table % is missing', required_table;
        END IF;
    END LOOP;

    -- Identity: IDs and membership foreign-key continuity.
    SELECT count(*) INTO affected
    FROM platform_users
    WHERE id IS NULL OR btrim(id) = '' OR tenant_id IS NULL OR btrim(tenant_id) = '';
    IF affected > 0 THEN
        RAISE EXCEPTION 'M8 identity validation failed: % users have blank IDs/tenant IDs', affected;
    END IF;

    SELECT count(*) INTO affected
    FROM platform_tenant_memberships membership
    LEFT JOIN platform_tenants tenant ON tenant.id = membership.tenant_id
    LEFT JOIN platform_users platform_user ON platform_user.id = membership.user_id
    WHERE tenant.id IS NULL OR platform_user.id IS NULL;
    IF affected > 0 THEN
        RAISE EXCEPTION 'M8 identity validation failed: % orphan memberships', affected;
    END IF;

    -- Agent/config/API-key/reference consistency.
    SELECT count(*) INTO affected
    FROM platform_agent_configurations agent
    LEFT JOIN platform_tenant_memberships membership
      ON membership.tenant_id = agent.tenant_id
     AND membership.user_id = agent.owner_id
     AND membership.status = 'ACTIVE'
    WHERE agent.id IS NULL OR btrim(agent.id) = ''
       OR agent.owner_id IS NULL OR btrim(agent.owner_id) = ''
       OR agent.tenant_id IS NULL OR btrim(agent.tenant_id) = ''
       OR membership.user_id IS NULL;
    IF affected > 0 THEN
        RAISE EXCEPTION 'M8 agent validation failed: % configurations have blank ownership IDs', affected;
    END IF;

    SELECT count(*) INTO affected
    FROM platform_agent_api_keys api_key
    LEFT JOIN platform_agent_configurations agent ON agent.id = api_key.agent_id
    WHERE agent.id IS NULL
       OR api_key.key_hash !~ '^[0-9a-f]{64}$'
       OR api_key.key_prefix IS NULL OR btrim(api_key.key_prefix) = '';
    IF affected > 0 THEN
        RAISE EXCEPTION 'M8 agent validation failed: % invalid/orphan API keys', affected;
    END IF;

    SELECT count(*) INTO affected
    FROM platform_agent_knowledge_bindings binding
    LEFT JOIN platform_agent_configurations agent ON agent.id = binding.agent_id
    LEFT JOIN platform_knowledge_documents document ON document.id = binding.knowledge_base_id
    WHERE agent.id IS NULL OR document.id IS NULL;
    IF affected > 0 THEN
        RAISE EXCEPTION 'M8 agent validation failed: % orphan Knowledge bindings', affected;
    END IF;

    SELECT count(*) INTO affected
    FROM platform_agent_configurations agent
    LEFT JOIN platform_model_providers provider ON provider.id = agent.model_provider_id
    LEFT JOIN platform_provider_models model
      ON model.provider_id = agent.model_provider_id AND model.model_id = agent.model_id
    WHERE (agent.model_provider_id IS NULL) <> (agent.model_id IS NULL)
       OR (agent.model_provider_id IS NOT NULL AND provider.id IS NULL)
       OR (agent.model_id IS NOT NULL AND model.id IS NULL);
    IF affected > 0 THEN
        RAISE EXCEPTION 'M8 agent validation failed: % broken provider/model bindings', affected;
    END IF;

    -- Chat conversation/message foreign keys and sequence continuity.
    SELECT count(*) INTO affected
    FROM platform_conversations conversation
    LEFT JOIN platform_tenant_memberships membership
      ON membership.tenant_id = conversation.tenant_id
     AND membership.user_id = conversation.user_id
     AND membership.status = 'ACTIVE'
    WHERE conversation.tenant_id IS NOT NULL AND membership.user_id IS NULL;
    IF affected > 0 THEN
        RAISE EXCEPTION 'M8 chat validation failed: % conversations have invalid tenant ownership', affected;
    END IF;

    SELECT count(*) INTO affected
    FROM platform_messages message
    LEFT JOIN platform_conversations conversation ON conversation.id = message.conversation_id
    WHERE conversation.id IS NULL;
    IF affected > 0 THEN
        RAISE EXCEPTION 'M8 chat validation failed: % orphan messages', affected;
    END IF;

    SELECT count(*) INTO affected
    FROM (
        SELECT conversation_id,
               sequence_number,
               lag(sequence_number) OVER (
                   PARTITION BY conversation_id ORDER BY sequence_number
               ) AS previous_sequence
        FROM platform_messages
    ) sequence_check
    WHERE (previous_sequence IS NULL AND sequence_number <> 0)
       OR (previous_sequence IS NOT NULL AND sequence_number <> previous_sequence + 1);
    IF affected > 0 THEN
        RAISE EXCEPTION 'M8 chat validation failed: % message sequence gaps', affected;
    END IF;

    -- Knowledge document/chunk and embedding metadata consistency.
    SELECT count(*) INTO affected
    FROM platform_knowledge_documents document
    LEFT JOIN platform_users platform_user ON platform_user.id = document.owner_id
    WHERE platform_user.id IS NULL;
    IF affected > 0 THEN
        RAISE EXCEPTION 'M8 knowledge validation failed: % documents have missing owners', affected;
    END IF;

    SELECT count(*) INTO affected
    FROM platform_knowledge_chunks chunk
    LEFT JOIN platform_knowledge_documents document ON document.id = chunk.document_id
    WHERE document.id IS NULL
       OR chunk.id IS NULL OR btrim(chunk.id) = ''
       OR (chunk.embedding_dimensions > 0 AND chunk.embedding_model IS NULL);
    IF affected > 0 THEN
        RAISE EXCEPTION 'M8 knowledge validation failed: % invalid/orphan chunks', affected;
    END IF;

    -- Runtime/tool ledger ownership consistency remains part of cutover safety.
    SELECT count(*) INTO affected
    FROM platform_tool_execution_ledger tool
    LEFT JOIN platform_agent_runs run ON run.id = tool.agent_run_id
    LEFT JOIN platform_run_steps step ON step.id = tool.run_step_id
    WHERE run.id IS NULL OR step.id IS NULL;
    IF affected > 0 THEN
        RAISE EXCEPTION 'M8 runtime validation failed: % orphan tool ledger entries', affected;
    END IF;

    SELECT count(*) INTO affected
    FROM (VALUES ('identity'), ('agent'), ('chat'), ('knowledge')) expected(domain)
    LEFT JOIN platform_migration_watermarks watermark ON watermark.domain = expected.domain
    WHERE watermark.domain IS NULL
       OR watermark.status <> 'MIGRATED'
       OR watermark.source_count <> watermark.target_count
       OR watermark.source_checksum IS DISTINCT FROM watermark.target_checksum;
    IF affected > 0 THEN
        RAISE EXCEPTION 'M8 migration validation failed: % missing or inconsistent domain watermarks', affected;
    END IF;
END
$$;

SELECT 'identity.users' AS entity, count(*) AS row_count FROM platform_users
UNION ALL SELECT 'identity.tenants', count(*) FROM platform_tenants
UNION ALL SELECT 'identity.memberships', count(*) FROM platform_tenant_memberships
UNION ALL SELECT 'agent.agents', count(*) FROM platform_agent_configurations
UNION ALL SELECT 'agent.configurations', count(*) FROM platform_agent_configurations
UNION ALL SELECT 'agent.api_keys', count(*) FROM platform_agent_api_keys
UNION ALL SELECT 'chat.conversations', count(*) FROM platform_conversations
UNION ALL SELECT 'chat.messages', count(*) FROM platform_messages
UNION ALL SELECT 'knowledge.documents', count(*) FROM platform_knowledge_documents
UNION ALL SELECT 'knowledge.chunks', count(*) FROM platform_knowledge_chunks
ORDER BY entity;
