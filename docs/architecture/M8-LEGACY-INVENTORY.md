# M8a - Legacy Inventory and Cutover Matrix

> Historical migration evidence. The inventoried Java sources are removed from the active
> tree and are available only in a private archive that is not included in this public snapshot; rollback is not supported.

Status: M8a COMPLETE (inventory only; no code was deleted)

This document is the M8a inventory of every remaining externally reachable and
stateful capability in the legacy/transitional Java surfaces:

- `backend/**`
- `services/gateway-service`
- `services/identity-service`
- `services/agent-service`
- `services/chat-service`
- `services/knowledge-service`

It intentionally excludes unrelated client work.

## Classification

| Class | Meaning |
| --- | --- |
| A | Already owned by `apps/platform-server` |
| B | Compatibility adapter only; target state is already authoritative elsewhere |
| C | Still authoritative legacy implementation |
| D | Obsolete/dead |
| E | Requires migration before deletion |

`A`/`B` are not a license to keep a legacy caller forever. They identify the
current ownership during the cutover; final deletion still depends on M8c-M8h.

## Cutover matrix at a glance

| Legacy/transitional surface | Authoritative status | Dominant class | Target platform module |
| --- | --- | --- | --- |
| `backend/` monolith | Legacy compatibility monolith | C/E | split into platform modules below |
| `services/gateway-service` | Edge/channel/session adapter | C/E | integration + observability |
| `services/identity-service` | Auth/tenant/user compatibility boundary | C/E | identity |
| `services/agent-service` | Agent/provider compatibility boundary | C/E | agent + inference |
| `services/chat-service` | Chat/compat orchestration boundary | C/E | conversation, context, runtime, memory, inference, tooling, automation, integration, governance, observability |
| `services/knowledge-service` | Knowledge ingestion/retrieval boundary | C/E | knowledge |

The `apps/platform-server` physical module already owns durable control-plane
state for identity, agent, inference, knowledge, conversation, memory, runtime,
tooling, context, and sandbox/AI orchestration. It does not yet own the full
legacy HTTP/SSE/channel/scheduled-job surface, which is why M8c-M8h remain
required.

## 1. backend

### REST endpoints

| Path | Method(s) | Class | M8 classification | Notes |
| --- | --- | --- | --- | --- |
| `/api/v1/auth/register`, `/api/v1/auth/login`, `/api/v1/auth/change-password` | POST | auth AuthController | C/E | superseded by identity-service auth + platform identity |
| `/api/v1/users/me` | GET | user UserController | C/E | identity-service compatibility endpoint |
| `/api/v1/users/me/profile` | GET/PUT | profile ProfileController | B/E | profile write now has contract boundary; final owner is identity |
| `/api/v1/chat/messages`, `/api/v1/chat/messages/stream`, `/api/v1/chat/messages/stream/v2` | POST | chat ChatController | C/E | conversation/context/runtime/inference/tooling split |
| `/api/v1/chat/conversations`, `/api/v1/chat/conversations/{id}`, `/close`, `/usage` | GET/POST | chat ChatController | C/E | conversation state target |
| `/api/v1/agents`, `/api/v1/agents/{id}` | GET/PUT/DELETE/POST | agent AgentConfigController | C/E | agent definition/config target |
| `/api/v1/agents/{agentId}/keys` | GET/POST/DELETE | agent AgentApiKeyController | C/E | agent API-key ownership |
| `/api/v1/agents/{agentId}/schedules` | GET/POST/PUT/DELETE | agent ScheduleController | C/E | automation |
| `/api/v1/agents/{agentId}/usage` | GET | agent UsageController | C/E | observability |
| `/api/v1/monitoring/*` | GET | agent AgentMonitoringController | C/E | observability |
| `/api/v1/admin/monitoring/*` | GET | monitoring MonitoringController | C/E | observability |
| `/api/v1/agent-templates`, `/api/v1/agents/{agentId}/clone` | GET/POST/DELETE | agent TemplateController | C/E | agent templates |
| `/api/v1/models/available` | GET | model AvailableModelsController | C/E | inference |
| `/api/v1/model-providers` | GET/POST/PUT/DELETE | model ModelProviderController | C/E | inference provider/model config |
| `/api/v1/admin/mcp/configs` | GET/POST/PUT/DELETE | mcp McpConfigController | C/E | tooling MCP config |
| `/api/v1/skills/*` | GET/POST/PUT | skills SkillProposalController | C/E | tooling skill registry |
| `/api/v1/learning/reviews*` | GET/POST | learning LearningController | C/E | memory/automation/tooling |
| `/api/knowledge-base/documents*` | GET/POST/DELETE | knowledgebase KnowledgeBaseController | C/E | knowledge ingestion/retrieval |
| `/api/v1/files/upload`, `/api/v1/files/temp/{token}` | POST/GET | file FileController / TempFileController | C/E | artifact |
| `/api/v1/sessions/{id}/messages`, `/api/v1/sessions/{id}/stream`, `/api/v1/sessions/{id}` | POST/GET/DELETE | streaming SessionController | C/E | integration/runtime streaming |
| `/api/v1/sessions/{sessionId}/answers/{requestId}` | POST | agent InteractionController | C/E | interaction/runtime |
| `/api/v1/sse/subscribe` | GET | channel SseController | C/E | integration SSE |
| `/api/v1/wechatwork/callback` | GET/POST | channel WeChatWorkCallbackController | C/E | integration external channel |
| `/api/v1/gateway/messages` | POST | gateway GatewayController | C/E | integration |
| `/ai-proxy/v1/messages`, `/ai-proxy/messages` | POST | ai AiProxyController | C/E | inference/legacy proxy |
| `/api/v1/tools/catalog` | GET | ai ToolCatalogController | C/E | tooling |
| `/api/v1/system/health` | GET | system HealthController | B/E | health; platform root health exists but path differs |

### SSE/WebSocket

| Path | Class | M8 classification | Notes |
| --- | --- | --- | --- |
| `/api/v1/chat/messages/stream`, `/api/v1/chat/messages/stream/v2` | chat ChatController | C/E | streaming must move to integration/runtime |
| `/api/v1/sessions/{sessionId}/stream` | streaming SessionController | C/E | in-memory session/event buffer |
| `/api/v1/sse/subscribe` | channel SseController | C/E | channel event fan-out |

### Scheduled/background jobs

| Class | M8 classification | Notes |
| --- | --- | --- |
| `ScheduleExecutorImpl` | C/E | agent schedules -> automation |
| `FollowUpTaskScheduler` | C/E | follow-ups -> automation |
| `InMemoryTempFileServer` cleanup | C/E | in-memory temp files -> artifact storage |
| `HealthProbeScheduler` | C/E | provider health -> inference |
| `DockerSandboxExecutor` cleanup | C/E | sandbox -> tooling/worker |
| `SessionRegistry` / `InMemorySessionRegistry` cleanup | C/E | in-memory session state |
| `AgentRateLimitFilter` / `RateLimitFilter` cleanup | C/E | Redis cache/ephemeral, not durable |

### Event consumers/producers

| Class | M8 classification | Notes |
| --- | --- | --- |
| `AdminBootstrap` `ApplicationReadyEvent` | C/E | admin seed -> identity governance |
| `ConversationTitleListener` `@TransactionalEventListener` | C/E | conversation/automation |
| `MemoryWriteListener` `@TransactionalEventListener` | C/E | memory lifecycle |

### Database tables

`users`, `user_profiles`, `api_key`, `session_bindings`, `conversations`,
`messages`, `memory_items`, `follow_up_tasks`, `safety_events`,
`knowledge_documents`, `knowledge_chunks`, `uploaded_files`,
`model_providers`, `provider_models`, `agent_configs`,
`agent_knowledge_bases`, `agent_api_keys`, `agent_usage_records`,
`agent_templates`, `agent_schedules`, `agent_container`,
`agent_capability_config`, `agent_trace`, `trace_span`, `mcp_server_config`,
`skill_proposals`, `companion_skills`, `learning_review_items`,
`chat_turn_usages`.

All are `C/E` unless explicitly listed as `A`/`B` in M8b. The platform-server
owns replacement tables under `db/platform-server` and
`db/platform-runtime`.

### Redis keys

| Key/pattern | Class | Notes |
| --- | --- | --- |
| `jwt:blacklist:{digest}` | C/E | auth revocation cache |
| `rate-limit:{clientIp}:{path}` | C/E | rate-limit window cache |
| `global:messages:{yyyy-MM-dd}` | C/E | monitoring aggregate |
| `agk_:*`, `ec_:*` API-key caches | C/E | auth/agent cache |
| active-user/monitoring realtime keys | C/E | observability ephemeral state |

Redis is cache/ephemeral only and is not a durable migration source.

### Object storage paths

| Path | Class | Notes |
| --- | --- | --- |
| `FILE_LOCAL_BASE_PATH` (`./data/uploads`) | C/E | uploaded artifacts -> artifact |
| knowledge base local storage under backend | C/E | superseded by knowledge-service |

### Auth/security behavior

- Legacy JWT secret/blacklist and rate-limit filters: C/E.
- Legacy admin bootstrap: C/E.
- API-key authentication filters: C/E.
- External channel signatures (WeChat Work): C/E.

### MCP/tool integrations

- Legacy MCP server config CRUD and stdio/HTTP connections: C/E.
- Legacy skill proposal/registry: C/E.
- Legacy sandbox executor and audit: C/E.

### External integrations

- WeChat Work callback: C/E.
- Compatible OpenAI/DashScope model proxy and embeddings: C/E.

## 2. services/gateway-service

### REST endpoints

| Path | Method(s) | Class | M8 classification | Notes |
| --- | --- | --- | --- | --- |
| `/api/v1/gateway/bind`, `/messages`, `/binding`, `/bindings` | POST/GET/DELETE | GatewayController | C/E | session/channel binding -> integration |
| `/api/v1/gateway/deliveries`, `/deliveries/{channelType}/{externalMessageId}/retry` | GET/POST | ChannelDeliveryOperationsController | C/E | channel delivery -> integration |
| `/api/v1/channels/{channelType}/webhook` | GET/POST | ChannelWebhookController | C/E | external channel webhook -> integration |
| `/api/v1/system/health` | GET | SystemHealthController | B/E | health adapter |
| proxy paths for auth/public/users/tenants/chat/memory/tools/models/mcp/knowledge/agents/openapi | all | GatewayProxyController | B | compatibility proxy; remove after platform-server exposes same contract |

### Scheduled/background jobs

| Class | M8 classification | Notes |
| --- | --- | --- |
| `ExternalChannelIngressService` delivery recovery | C/E | integration delivery retry |
| `ChannelOperationalMetrics` refresh | B/E | observability |

### Event consumers/producers

- No Kafka/Rabbit/JMS listeners found.
- Shared-kernel outbox producers are used for audit/event publication.

### Database tables

`session_bindings`, `channel_conversation_bindings`,
`channel_webhook_events`, `business_audit_outbox`.

### Redis keys

| Key/pattern | Class | Notes |
| --- | --- | --- |
| `spaceagent:gateway:rate-limit:*` | C/E | gateway rate-limit cache |
| `spaceagent:identity:revoked-token:{sha256}` | C/E | revoked JWT digest cache |
| `spaceagent:identity:tenant-membership-revoked:{tenant}:{user}` | C/E | tenant revocation cache |

### Object storage paths

None owned by gateway-service.

### Auth/security behavior

Gateway JWT verification, tenant revocation checks, channel signature validation,
and rate limiting: C/E.

### MCP/tool integrations

None directly; gateway proxies `/api/v1/mcp`.

### External integrations

DingTalk, Feishu, WeChat Work channel adapters and webhooks: C/E.

## 3. services/identity-service

### REST endpoints

| Path | Method(s) | Class | M8 classification | Notes |
| --- | --- | --- | --- | --- |
| `/api/v1/auth/register`, `/login`, `/refresh`, `/logout`, `/change-password` | POST | AuthController | C/E | identity auth flows |
| `/auth/*` aliases | POST | AuthController | B | compatibility alias |
| `/api/v1/public/auth-config`, `/api/v1/public/branding` | GET | FrontendPublicCompatibilityController | B | public compatibility |
| `/internal/auth/verify`, `/internal/users/{userId}`, `/internal/users/{userId}/profile` | GET | InternalAuthController | C/E | internal service contract |
| `/api/v1/tenants/*` | GET/POST/PATCH/DELETE | TenantController | C/E | tenant/membership/invitation/audit |
| `/api/v1/tenant-invitations/*` | GET/POST | TenantInvitationController | C/E | invitation lifecycle |
| `/api/v1/users/me`, `/api/v1/users/me/profile` | GET/PUT | UserController | C/E | user/profile compatibility |

### Scheduled/background jobs

| Class | M8 classification | Notes |
| --- | --- | --- |
| `TenantInvitationService` expiry | C/E | automation/identity |
| `TenantOutboxDispatcher` outbox dispatch | C/E | event publication |

### Event consumers/producers

`TenantOutboxDispatcher` uses `@TransactionalEventListener` and publishes
outbox events.

### Database tables

`users`, `user_profiles`, `refresh_tokens`, `tenants`,
`tenant_memberships`, `tenant_invitations`, `tenant_audit_logs`,
`tenant_outbox_events`, `business_audit_outbox`.

### Redis keys

| Key/pattern | Class | Notes |
| --- | --- | --- |
| `spaceagent:identity:revoked-token:{sha256}` | C/E | revoked refresh/access token digest |
| `spaceagent:identity:tenant-membership-revoked:{tenant}:{user}` | C/E | membership revocation marker |

### Object storage paths

None owned by identity-service.

### Auth/security behavior

Password auth, refresh-token rotation, logout revocation, tenant membership and
invitation security: C/E.

### MCP/tool integrations

None.

### External integrations

None directly; identity is the internal authority for user/tenant identity.

## 4. services/agent-service

### REST endpoints

| Path | Method(s) | Class | M8 classification | Notes |
| --- | --- | --- | --- | --- |
| `/agents`, `/agents/{agentId}` | GET/POST/PUT/DELETE | AgentController | C/E | agent definition/config |
| `/agents/{agentId}/keys`, `/agents/{agentId}/keys/{keyId}` | GET/POST/DELETE | AgentController | C/E | API-key ownership |
| `/internal/agents/{agentId}/runtime-config` | GET | AgentController | C/E | runtime config snapshot |
| `/internal/agents/knowledge-bindings/{documentId}` | DELETE | AgentController | C/E | knowledge binding cleanup |
| `/internal/agents/api-keys/verify` | POST | AgentController | C/E | internal API-key verification |
| `/users/agents*`, `/users/agents/{id}*` | GET/POST/PUT/PATCH/DELETE | FrontendAgentCompatibilityController | B | frontend compatibility |
| `/users/model-providers*`, `/admin/model-providers*`, `/users/models*`, `/admin/models*` | GET/POST/PUT/PATCH/DELETE | FrontendModelProviderCompatibilityController | B/C | provider/model compatibility |
| `/model-providers`, `/model-providers/{providerId}`, `/models/available` | GET/POST/PUT/DELETE | ModelProviderController | C/E | inference config |

### Scheduled/background jobs

No `@Scheduled` jobs found in agent-service.

### Event consumers/producers

No direct Kafka/Rabbit/JMS listeners found. Shared-kernel outbox is available.

### Database tables

`agent_configs`, `agent_knowledge_bases`, `agent_api_keys`,
`model_providers`, `provider_models`.

### Redis keys

`agk_:*` API-key cache is referenced by `JdbcAgentApiKeyService`.

### Object storage paths

None owned by agent-service.

### Auth/security behavior

Internal service-token API-key verification and agent API-key CRUD: C/E.

### MCP/tool integrations

Agent config references tool/skill IDs, but MCP execution is owned by
chat-service.

### External integrations

None directly.

## 5. services/chat-service

### REST endpoints

| Path | Method(s) | Class | M8 classification | Notes |
| --- | --- | --- | --- | --- |
| `/api/v1/chat/messages`, `/api/v1/chat/messages/stream` | POST | ChatController | C/E | conversation/runtime/inference |
| `/api/v1/chat/conversations`, `/conversations/{id}`, `/conversations/{id}/messages`, `/messages/page`, `/usage` | GET/DELETE | ChatController | C/E | conversation state |
| `/api/v1/memory` | GET/DELETE | MemoryController | C/E | memory compatibility |
| `/api/v1/mcp/servers`, `/api/v1/mcp/tools`, `/api/v1/mcp/audit` | GET/POST/PUT/DELETE | McpController | C/E | tooling MCP |
| `/api/v1/tools/catalog` | GET | ToolCatalogController | C/E | tooling |
| `/users/sessions*`, `/users/agents/{id}/sessions*` | GET/POST/PUT/PATCH/DELETE | FrontendSessionCompatibilityController | B | frontend compatibility |
| `/users/custom-mcp-servers*` | GET/POST/PUT/PATCH/DELETE | FrontendMcpCompatibilityController | B | frontend compatibility |
| `/users/skill-registries*`, `/admin/skill-registries*` | GET/POST/PUT/PATCH/DELETE | FrontendSkillRegistryController | B/C | tooling/skill |
| `/users/skills`, `/admin/skills`, `/users/skills/search` | GET | FrontendSkillsController | B/C | tooling/skill |
| `/users/tracing*` | GET | FrontendTracingController | B/C | observability |
| `/users/agents/{id}/stats`, `/users/sessions/page`, `/users/dashboard`, `/monitoring/*` | GET/POST | FrontendAnalyticsController | B/C | observability/analytics |

### SSE/WebSocket

`/api/v1/chat/messages/stream` is SSE and is the main conversational streaming
surface.

### Scheduled/background jobs

| Class | M8 classification | Notes |
| --- | --- | --- |
| `ChatOperationalMetrics` refresh | B/E | observability |

### Event consumers/producers

No direct Kafka/Rabbit/JMS listeners found. Chat writes runtime/tool/conversation
state through platform Application APIs when wired.

### Database tables

`conversations`, `chat_messages`, `chat_turns`, `chat_turn_usages`,
`conversation_context_summaries`, `memory_entries`, `follow_up_tasks`,
`mcp_server_configs`, `mcp_tool_audit_logs`, `skill_registries`,
`local_skills`, `local_skill_files`, `business_audit_outbox`.

### Redis keys

No dedicated chat-service Redis key owner was found; chat depends on
identity/gateway security caches and shared-kernel outbox/event publisher.

### Object storage paths

Local skill files and any uploaded skill archives are owned by chat-service.

### Auth/security behavior

Internal service-token propagation, JWT propagation, MCP secret encryption,
and remote tool permission/audit: C/E.

### MCP/tool integrations

Native MCP registry, Streamable HTTP client, builtin tools, and skill registry:
C/E.

### External integrations

LangChain4j provider calls, MCP servers, and local skill file paths: C/E.

## 6. services/knowledge-service

### REST endpoints

| Path | Method(s) | Class | M8 classification | Notes |
| --- | --- | --- | --- | --- |
| `/knowledge/documents`, `/knowledge/documents/{id}`, `/knowledge/retrieve`, `/knowledge/diagnostics` | GET/POST/DELETE | KnowledgeController | C/E | knowledge ingestion/retrieval |
| `/internal/knowledge/retrieve`, `/internal/knowledge/documents/validate` | POST | KnowledgeController | C/E | internal retrieval/validation |
| `/users/knowledge-bases*` | GET/POST/DELETE | FrontendKnowledgeCompatibilityController | B | frontend compatibility |

### Scheduled/background jobs

| Class | M8 classification | Notes |
| --- | --- | --- |
| `KnowledgeIngestionCoordinator` recovery | C/E | knowledge ingestion lease/recovery |
| `KnowledgeOperationalMetrics` refresh | B/E | observability |

### Event consumers/producers

No direct Kafka/Rabbit/JMS listeners found.

### Database tables

`knowledge_documents`, `knowledge_chunks`, plus lease columns in the same
schema.

### Redis keys

No durable keys. Embedding cache is in-process Caffeine in the current
knowledge-service implementation.

### Object storage paths

- Local storage root `./data/uploads/knowledge-base`
- Optional S3/MinIO bucket `spaceagent-knowledge`

Both are `C/E` and must move under the platform `knowledge` module with the
same object-key invariants.

### Auth/security behavior

Internal service-token and tenant isolation: C/E.

### MCP/tool integrations

None.

### External integrations

Embedding provider API (OpenAI/DashScope compatible): C/E.

## Cross-cutting classification by platform module

| Target module | A already owned | B compatibility only | C authoritative legacy | E requires migration |
| --- | --- | --- | --- | --- |
| identity | platform user/profile/tenant core | frontend auth aliases | auth flows, refresh/logout, tenant membership/invitation, revocation | full identity HTTP surface and migration |
| agent | agent definition/configuration state | frontend agent compatibility | API keys, runtime config, legacy controller behavior | API-key/runtime endpoint cutover |
| inference | provider/model ownership APIs | model-provider compatibility controllers | legacy provider connection/test behavior | provider execution/secret policy migration |
| knowledge | document/chunk metadata APIs | frontend knowledge compatibility | ingestion, chunking, embeddings, storage, retrieval | object storage and ingestion migration |
| conversation | conversation/message/snapshot APIs | session/message compatibility | legacy chat-turn/message persistence | conversation/message count migration |
| context | ContextCompiler API | legacy context-window adapter | legacy context compilation | context cutover |
| memory | candidate/consolidation APIs | legacy MemoryService recall | legacy `memory_items` | memory scope/state migration |
| runtime | run/step/checkpoint/recovery/handoff | chat runtime coordinator | legacy in-memory sessions/event buffers | run ledger migration |
| tooling | tool ledger and sandbox gateway | MCP/skill compatibility controllers | legacy MCP/skill/sandbox execution | MCP/tool migration |
| automation | none external yet | none | schedules, follow-ups, invitations expiry | job ownership migration |
| integration | none external yet | gateway proxy, frontend compatibility | channel webhooks/delivery/SSE/session bindings | channel/session migration |
| governance | none external yet | none | safety events, admin bootstrap, audit | safety/governance migration |
| observability | none external yet | metrics compatibility | legacy tracing/monitoring/usage | observability migration |
| artifact | none external yet | none | uploaded files/temp file server | file/artifact migration |

## Deletion prerequisites

No legacy module can be deleted until its `C` and `E` capabilities are either:

1. moved behind a platform-server public API;
2. proven non-overlapping/obsolete; or
3. retained as a thin documented compatibility adapter with no authoritative
   state ownership.

The next document, `M8-DATA-MIGRATION-MAP.md`, defines the data-level cutover
for every state family above.
