DO $chat_task_plan_scope$
BEGIN
    IF to_regclass('public.platform_tasks') IS NULL
            OR to_regclass('public.platform_task_plans') IS NULL
            OR to_regclass('public.platform_plan_steps') IS NULL
            OR to_regclass('public.platform_conversations') IS NULL THEN
        RETURN;
    END IF;

    ALTER TABLE platform_tasks
        DROP CONSTRAINT ck_platform_task_scope,
        ADD CONSTRAINT uk_platform_task_chat_conversation_id
            UNIQUE (conversation_id, id),
        ADD CONSTRAINT ck_platform_task_scope CHECK (
            (project_id IS NOT NULL
                AND conversation_id IS NULL
                AND source_message_id IS NULL)
            OR
            (project_id IS NULL
                AND conversation_id IS NOT NULL
                AND parent_task_id IS NULL
                AND source_message_id IS NOT NULL)
            OR
            (project_id IS NULL
                AND conversation_id IS NOT NULL
                AND parent_task_id IS NOT NULL
                AND source_message_id IS NULL
                AND current_task_plan_id IS NULL)),
        ADD CONSTRAINT fk_platform_task_chat_parent
            FOREIGN KEY (conversation_id, parent_task_id)
            REFERENCES platform_tasks(conversation_id, id) ON DELETE CASCADE;

    ALTER TABLE platform_task_plans
        ADD COLUMN tenant_id VARCHAR(36),
        ADD COLUMN owner_user_id VARCHAR(36),
        ADD COLUMN conversation_id VARCHAR(36),
        ADD COLUMN source_agent_run_id VARCHAR(36),
        ADD COLUMN proposal_hash VARCHAR(71),
        ADD COLUMN strategy_summary TEXT,
        ALTER COLUMN project_id DROP NOT NULL,
        ADD CONSTRAINT uk_platform_task_plan_id_chat
            UNIQUE (id, conversation_id),
        ADD CONSTRAINT uk_platform_task_plan_id_chat_root
            UNIQUE (id, conversation_id, root_task_id),
        ADD CONSTRAINT ck_platform_task_plan_scope CHECK (
            (project_id IS NOT NULL
                AND conversation_id IS NULL
                AND source_agent_run_id IS NULL
                AND proposal_hash IS NULL)
            OR
            (project_id IS NULL
                AND tenant_id IS NOT NULL
                AND owner_user_id IS NOT NULL
                AND conversation_id IS NOT NULL
                AND source_agent_run_id IS NOT NULL
                AND proposal_hash IS NOT NULL
                AND strategy_summary IS NOT NULL)),
        ADD CONSTRAINT fk_platform_task_plan_tenant
            FOREIGN KEY (tenant_id) REFERENCES platform_tenants(id),
        ADD CONSTRAINT fk_platform_task_plan_owner
            FOREIGN KEY (owner_user_id) REFERENCES platform_users(id),
        ADD CONSTRAINT fk_platform_task_plan_chat_conversation
            FOREIGN KEY (conversation_id) REFERENCES platform_conversations(id) ON DELETE CASCADE,
        ADD CONSTRAINT fk_platform_task_plan_chat_root
            FOREIGN KEY (conversation_id, root_task_id)
            REFERENCES platform_tasks(conversation_id, id)
            DEFERRABLE INITIALLY DEFERRED;

    CREATE UNIQUE INDEX uk_platform_task_plan_chat_source_run
        ON platform_task_plans(source_agent_run_id)
        WHERE source_agent_run_id IS NOT NULL;
    CREATE INDEX idx_platform_task_plan_chat_root_created
        ON platform_task_plans(conversation_id, root_task_id, version_number DESC)
        WHERE conversation_id IS NOT NULL;

    ALTER TABLE platform_plan_steps
        ADD COLUMN conversation_id VARCHAR(36),
        ALTER COLUMN project_id DROP NOT NULL,
        ADD CONSTRAINT ck_platform_plan_step_scope CHECK (
            (project_id IS NOT NULL AND conversation_id IS NULL)
            OR (project_id IS NULL AND conversation_id IS NOT NULL)),
        ADD CONSTRAINT fk_platform_plan_step_chat_plan
            FOREIGN KEY (task_plan_id, conversation_id)
            REFERENCES platform_task_plans(id, conversation_id) ON DELETE CASCADE,
        ADD CONSTRAINT fk_platform_plan_step_chat_child
            FOREIGN KEY (conversation_id, child_task_id)
            REFERENCES platform_tasks(conversation_id, id)
            DEFERRABLE INITIALLY DEFERRED;

    ALTER TABLE platform_tasks
        ADD CONSTRAINT fk_platform_task_chat_current_plan
            FOREIGN KEY (current_task_plan_id, conversation_id, id)
            REFERENCES platform_task_plans(id, conversation_id, root_task_id)
            DEFERRABLE INITIALLY DEFERRED;

    COMMENT ON TABLE platform_task_plans IS
        'Java-owned versioned PROJECT or CHAT plan proposal and approval lifecycle';
    COMMENT ON COLUMN platform_task_plans.source_agent_run_id IS
        'CHAT proposal idempotency source; null for PROJECT plans';
    COMMENT ON COLUMN platform_plan_steps.conversation_id IS
        'CHAT plan scope; mutually exclusive with project_id';
END
$chat_task_plan_scope$;
