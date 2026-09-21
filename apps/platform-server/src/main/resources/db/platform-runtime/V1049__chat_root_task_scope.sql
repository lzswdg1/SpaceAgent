DO $chat_root_task_scope$
BEGIN
    IF to_regclass('public.platform_tasks') IS NULL
            OR to_regclass('public.platform_projects') IS NULL
            OR to_regclass('public.platform_conversations') IS NULL
            OR to_regclass('public.platform_messages') IS NULL
            OR to_regclass('public.platform_agent_runs') IS NULL THEN
        RETURN;
    END IF;

    ALTER TABLE platform_tasks
        ADD COLUMN tenant_id VARCHAR(36),
        ADD COLUMN owner_user_id VARCHAR(36),
        ADD COLUMN conversation_id VARCHAR(36),
        ADD COLUMN source_message_id VARCHAR(36);

    UPDATE platform_tasks task
       SET tenant_id = project.tenant_id,
           owner_user_id = project.owner_id
      FROM platform_projects project
     WHERE task.project_id = project.id;

    ALTER TABLE platform_tasks
        ALTER COLUMN tenant_id SET NOT NULL,
        ALTER COLUMN owner_user_id SET NOT NULL,
        ALTER COLUMN project_id DROP NOT NULL;

    ALTER TABLE platform_tasks
        ADD CONSTRAINT ck_platform_task_scope CHECK (
            (project_id IS NOT NULL
                AND conversation_id IS NULL
                AND source_message_id IS NULL)
            OR
            (project_id IS NULL
                AND parent_task_id IS NULL
                AND conversation_id IS NOT NULL
                AND source_message_id IS NOT NULL
                AND current_task_plan_id IS NULL)),
        ADD CONSTRAINT fk_platform_task_tenant
            FOREIGN KEY (tenant_id) REFERENCES platform_tenants(id),
        ADD CONSTRAINT fk_platform_task_owner
            FOREIGN KEY (owner_user_id) REFERENCES platform_users(id),
        ADD CONSTRAINT fk_platform_task_chat_conversation
            FOREIGN KEY (conversation_id) REFERENCES platform_conversations(id) ON DELETE CASCADE,
        ADD CONSTRAINT fk_platform_task_chat_source_message
            FOREIGN KEY (source_message_id) REFERENCES platform_messages(id) ON DELETE CASCADE;

    CREATE UNIQUE INDEX uk_platform_task_chat_source_message
        ON platform_tasks(source_message_id)
        WHERE source_message_id IS NOT NULL;
    CREATE INDEX idx_platform_task_chat_conversation
        ON platform_tasks(conversation_id, created_at, id)
        WHERE conversation_id IS NOT NULL;

    ALTER TABLE platform_agent_runs
        ADD COLUMN chat_task_id UUID,
        ADD CONSTRAINT fk_platform_agent_run_chat_task
            FOREIGN KEY (chat_task_id) REFERENCES platform_tasks(id) ON DELETE SET NULL,
        ADD CONSTRAINT ck_platform_agent_run_task_scope CHECK (
            NOT (chat_task_id IS NOT NULL AND task_uuid IS NOT NULL));
    CREATE INDEX idx_platform_agent_run_chat_task
        ON platform_agent_runs(chat_task_id)
        WHERE chat_task_id IS NOT NULL;

    IF to_regclass('public.platform_trace_roots') IS NOT NULL THEN
    CREATE OR REPLACE VIEW platform_trace_roots AS
    SELECT CASE WHEN run.id ~* '^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'
                THEN lower(run.id)
                ELSE substring(md5('trace:' || run.id), 1, 8) || '-'
                   || substring(md5('trace:' || run.id), 9, 4) || '-4'
                   || substring(md5('trace:' || run.id), 14, 3) || '-8'
                   || substring(md5('trace:' || run.id), 18, 3) || '-'
                   || substring(md5('trace:' || run.id), 21, 12) END AS trace_id,
           run.id AS agent_run_id,
           COALESCE(run.tenant_id, agent.tenant_id) AS tenant_id,
           run.owner_id, run.agent_id, agent.name AS agent_name,
           run.agent_version_id::TEXT AS agent_version_id,
           run.conversation_id AS session_id,
           conversation.title AS session_name,
           run.project_uuid::TEXT AS project_id,
           COALESCE(run.task_uuid, run.chat_task_id)::TEXT AS task_id,
           run.state AS run_state,
           (run.state = 'FAILED' AND run.failure_reason ILIKE '%retry%') AS retry_aborted,
           CASE WHEN run.state = 'FAILED' THEN 'Agent run failed'
                WHEN run.state = 'CANCELLED' THEN 'Agent run cancelled'
                ELSE NULL END AS error_message,
           run.created_at AS start_time, run.completed_at AS end_time,
           CASE WHEN run.completed_at IS NULL THEN NULL
                ELSE GREATEST(0, ROUND(EXTRACT(EPOCH FROM
                    (run.completed_at - run.created_at)) * 1000))::BIGINT END AS duration_ms,
           run.created_at, run.updated_at,
           jsonb_strip_nulls(jsonb_build_object(
               'agentVersionId', run.agent_version_id,
               'agentRunId', run.id,
               'projectId', run.project_uuid,
               'taskId', COALESCE(run.task_uuid, run.chat_task_id),
               'taskScope', CASE WHEN run.chat_task_id IS NOT NULL THEN 'CHAT'
                                 WHEN run.task_uuid IS NOT NULL THEN 'PROJECT' ELSE NULL END,
               'source', CASE WHEN automation.id IS NOT NULL THEN 'automation'
                              WHEN run.project_uuid IS NOT NULL THEN 'project' ELSE 'chat' END,
               'automationExecutionId', automation.id,
               'automationState', automation.state,
               'automationTriggerType', automation.trigger_type,
               'handoffCount', (SELECT COUNT(*) FROM platform_run_handoffs handoff
                                 WHERE handoff.source_agent_run_id = run.id),
               'delegationCount', (SELECT COUNT(*) FROM platform_agent_delegations delegation
                                    WHERE delegation.parent_run_id = run.id
                                       OR delegation.child_run_id = run.id),
               'reviewCount', (SELECT COUNT(*) FROM platform_agent_reviews review
                                WHERE review.parent_run_id = run.id
                                   OR review.child_run_id = run.id),
               'artifactCount', (SELECT COUNT(*) FROM platform_artifacts artifact
                                  WHERE artifact.agent_run_id = run.id)
           )) AS metadata
    FROM platform_agent_runs run
    LEFT JOIN platform_agent_definitions agent ON agent.id = run.agent_id
    LEFT JOIN platform_conversations conversation ON conversation.id = run.conversation_id
    LEFT JOIN LATERAL (
        SELECT execution.id, execution.state, execution.trigger_type
        FROM platform_automation_executions execution
        WHERE execution.dispatch_run_id = run.id OR execution.agent_run_id = run.id
        ORDER BY execution.created_at DESC, execution.id LIMIT 1
    ) automation ON TRUE;
    END IF;

    COMMENT ON COLUMN platform_tasks.conversation_id IS
        'CHAT-scoped Root Task Conversation; null for PROJECT Tasks';
    COMMENT ON COLUMN platform_agent_runs.chat_task_id IS
        'Immutable CHAT Root Task pin; distinct from canonical Project task_uuid';
END
$chat_root_task_scope$;
