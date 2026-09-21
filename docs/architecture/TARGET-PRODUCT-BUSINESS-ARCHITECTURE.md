# SpaceAgent 目标产品业务架构蓝图

> 日期：2026-08-23
> 状态：TARGET / NOT FULLY IMPLEMENTED
> 当前实现证据：`V2-IMPLEMENTED-FUNCTIONAL-ARCHITECTURE.md`
> 语言边界：Java 权威业务与 Runtime；TypeScript Multi-Agent；Python 仅用于 OCI Sandbox

当前进度：M13-PR1 已实现 Organization 创建/列表/切换、现有用户成员绑定、角色、唯一 OWNER、
所有权转移、退出和空组织 DELETING；M25-PR1 已实现哈希令牌邀请、过期/撤销、掩码预览、
身份绑定接受和防重放。M25-PR2B/PR2C 已实现空 Organization 的保留期、权威清理 Job/Step、
有序模块参与者和最终 DELETED Tombstone；邀请投递/UI 仍是目标能力。
M14-PR1 已实现 Provider 手动连接测试、耐久健康状态、Organization-scoped ModelPool 生命周期
以及确定性 Priority/Fallback 候选解析。当前 Agent 配置支持 ModelPool/直连
兼容绑定和 Chat 主候选解析；M15-PR2 已实现 Draft/Review/Publish/Deprecate/Rollback。
M16-PR1 已实现 Project-scoped Conversation active Task、版本化 TaskPlan/PlanStep DAG、
审批/激活和 Root Task current-plan 指针。
M17-PR1 已实现 TypeScript `multi-agent-orchestrator` 骨架、真实 LangGraph.js 确定性路由和
共享 Zod/JSON 契约；M18 已接入默认关闭的 Java Adapter；M19-M21 已实现
SourceRepository/Bridge、Workspace/ProjectBlueprint、单 Agent Coding Runtime 与 Artifact；
M22 已实现确定性 Supervisor/Specialist/Handoff/Reviewer 路由和 Java 权威的 durable
Delegation/Review/isolated child Workspace/Run 闭环。M28-PR1 已实现可选 Provider-backed
Supervisor：TypeScript 提出一次 `MODEL_REQUESTED`，Java 通过 ModelPool/预算/ModelCallLedger
执行并把标准化结果交回 LangGraph。M29-PR1 已实现 Review/Artifact/Governance 校验后的
本地集成 Ref CAS 与显式回滚，但远程自动合并和默认生产切换仍未实现。M23 已实现 PostgreSQL Worker Lease、Run CAS/Fencing、
durable Continuation 和 reconnectable RunEvent SSE，使 Java Runtime 协调可安全多副本运行；
Provider/Tool 副作用仍不承诺 exactly-once。M24-PR1 已实现 tenant-scoped Observability
read views、Monitoring API 与 Organization-admin operations UI；这些 view 不是恢复或计费权威。
M24-PR2 已实现 Organization Governance Policy、耐久 ApprovalRequest/Decision、职责分离、
过期和精确操作一次性消费，并在 Coding Runtime 文件修改/删除/命令执行前强制检查。
M24-PR3 已实现 Organization Schedule、Cron/时区、PostgreSQL-clock occurrence、审批等待、
Runtime Continuation/fencing、prepared Chat 和 at-most-once Execution/UNKNOWN。
M24-PR4 已实现 backend Trace list/detail/stats、Run/Model/Tool/Automation/Collaboration/
Artifact 关联与敏感 payload 脱敏；Trace view 不是恢复或计费权威。
M27-PR1 已实现 PostgreSQL 权威的定时 Provider 健康探测、claim/lease/fencing、退避和观察历史；
M27-PR2 已实现确定性 Priority/Weighted/Cost/Latency 路由、逐尝试账本和已知安全 Fallback。
M27-PR3 已实现 Organization 月度请求/Token/成本硬限制、预留/结算/UNKNOWN 保留和可信成本证据。
定时激活、独立 Reviewer 角色仍是目标能力。
M26-PR1~PR4 已实现 MCP Marketplace、账本化 Project 导入和短期 checkout grant 基础；
M31-PR1 已进一步实现 GitHub 官方 Remote MCP 的元数据发现、Spring Security OAuth2+PKCE、
`get_me`/`search_repositories` 映射、Token Refresh CAS 和私有 checkout；M38-PR2 已删除
Native GitHub OAuth/API，官方 Remote MCP 成为唯一 GitHub 账户路径。真实浏览器验收仍需要
运营方 GitHub App/OAuth App 配置。
M50-PR1 已实现版本化 Marketplace 数据基础：Publisher、不可变 ServerVersion、顺序 Remote
Transport 和 Trust/Lifecycle/来源证据由 Tooling/PostgreSQL 权威保存；现有及新 Installation
固定一个 APPROVED Version，Catalog current Version 变化不会静默升级安装。
M50-PR2A 已实现 Connection 资格验证：新配置进入 `PENDING_VALIDATION`，由官方 SDK 完成
initialize/有界 Tool 发现，并以 Connection revision fence 原子保存 CapabilitySnapshot 和
Health Observation 后才进入 `ACTIVE`；初次失败为 `ERROR`，激活后重验失败为 `DEGRADED`。
M50-PR2B 已实现非 GitHub MCP 的预注册通用 OAuth：RFC 9728、RFC 8414/OIDC、PKCE S256、
RFC 8707 resource、一次性 revision-bound state、加密 Grant 和安全 Refresh；OAuth 完成后
仍进入 `PENDING_VALIDATION`，不会绕过资格验证。DCR/Client ID Metadata Documents 后续迭代。
M50-PR3 已实现 Official MCP Registry 的增量 cursor 同步、租约 Job、不可变脱敏 Snapshot 和
审核 Candidate；同步成功不会自动进入市场，只有独立 Admin 控制面的最高管理员通过精确 scope、
recent-MFA、幂等命令与审计链路批准固定 HTTPS Streamable HTTP 版本后，才创建本地可安装版本。
M30-PR1 已完成后端 Trusted Beta Release Candidate：严格发布配置、备份/恢复验证、
graceful shutdown、运维手册和重启 API 黄金链路均已落地；M50-PR3 新增 V1042，M51-PR1/
PR2 继续推进到 V1044，并把当前 release readiness 版本同步到 V1044。该上线边界仅支持
受信任代码与私有/邀请制部署，不等同于公网不可信代码沙箱或最终蓝图全部完成。
M33-PR1 已实现 Java 权威的 16 项 Runtime Tool Registry/Dispatcher：Web/HTTP、Knowledge、
READY managed Workspace File/List/Git/Document、dynamic MCP、GitHub MCP Repository 和 Coding
工具共用 Run 配置快照权限、JSON Schema、Governance 与 ToolExecutionLedger。SearXNG 默认
未配置即不可用；非 Project Chat 的独立文件空间、通用审批恢复和 Skill Executor 仍是目标能力。
M34-PR1 已将 Coding command 从宿主机切换到经内部令牌认证的一次一 OCI 容器 Worker，采用
Docker SDK 7.2.0、精确 Workspace volume-subpath、无网络、非 root/只读/capability-free 和
cgroup/log/timeout 限制；runc 实机验收已通过。公网敌对多租户仍需 Linux gVisor runsc 验收，
因此 public-untrusted 发布开关继续 fail closed。
M40-PR3 已在独立平台管理控制面的安全基础上实现 owner-module 全局只读投影、专用
mTLS/service JWT、Dashboard、User/Organization 分页、活动语义和脱敏凭据清单。最高权限
`SystemAdministrator` 仍不属于任何 Organization，Admin 服务不拥有平台业务库或密钥；
M40-PR4 已通过 `platform-server` 模块所有者实现一次性激活创建、停用/恢复、会话撤销、
跨 owner 删除预检和 UNKNOWN 对账；M40-PR5 已实现 claim/lease/fencing、15 步 owner cleanup、
sole-member Organization 清理衔接、UNKNOWN/所有权阻断和最小 DELETED User Tombstone。
M42-PR1 已实现最高权限者的 Organization 新建、名称/Slug 修改、通过 ADR-025 CleanupJob
显式退役，以及按 User 查询脱敏 Provider 与 Agent 身份明细；Admin 仍不直接访问业务库或密钥。
M43-PR1 已实现 Admin 浏览器硬刷新后的安全会话恢复：HttpOnly Refresh Cookie 与可恢复的
SameSite=Strict CSRF Cookie/请求 Header/数据库 Hash 三方一致后才允许轮换，Access/Refresh
Token 仍不进入 Web Storage。
M44-PR1 已实现 Admin 组织成员分页、添加/重新激活、非 OWNER 角色调整、移除和显式 OWNER
转移；Identity 与唯一 ACTIVE OWNER 数据库约束继续拥有最终权威，Admin 不加入组织也不冒充用户。
M45-PR1 已实现 User/Organization 全局 Cleanup 队列、BLOCKED 原因聚合、Step 进度和 Admin
Command UNKNOWN/FAILED 分页；只允许现有 Command-ID 调和，不允许浏览器盲目重跑 Cleanup。
M46-PR1 已实现按 User 下钻 ModelPool、Project/Task/Workspace、Conversation、MCP、Knowledge、
USER Memory、Automation、Run 和 Model/Tool 风险效果；所有查询仍在 owner module，Admin 只接收
脱敏元数据，不读取业务库、正文、Prompt、路径、Tool payload 或凭据。
M47-PR1 已实现 Admin Principal 新建、暂停/恢复、强制首次改密、TOTP/一次性恢复码、凭据恢复和
Session 查看/撤销；原始交付材料只在首次响应出现，Admin V3 仅保存加密/Hash 证据。
M48-PR1 根据最终产品约束将其收敛为唯一的非组织 SystemAdministrator：Admin V4 物理阻止
第二个管理员，取消在线新增/暂停/恢复/凭据重置成功路径，仅保留本人其他 Session 撤销、本人
恢复码轮换，以及启动时一次性、可审计且防重放的离线 Break-glass。
M52-PR1 已实现 Spring Boot Micrometer/Prometheus、显式启用的 OpenTelemetry OTLP、低基数 Agent
SLO 指标、Grafana Agent Operations 面板、Prometheus 业务告警与 Alertmanager 路由/静默基础；
这些 telemetry 全部是可丢失投影，不取代 PostgreSQL Runtime/Ledger/Trace 权威证据。
M52-PR2 已固定 OpenTelemetry GenAI 独立仓库 commit `94f432d7` 的安全子集：Java 输出脱敏
Agent/Model/Tool span，并通过 W3C Trace Context 串联 TypeScript LangGraph 与 Python Sandbox；
两个 Worker 使用官方 OpenTelemetry SDK 且 OTLP 默认关闭。V1048 在有效 ModelCall claim 下仅写
一次真实 Provider 首个有效 stream chunk 的 PostgreSQL 时间戳和单调 dispatch 耗时，Trace/
Prometheus 展示 TTFC。Prompt、Reasoning、回复、Tool payload、业务 ID 与 Secret 不进入遥测。
M74 根据产品所有者的明确决定取消旧 Agent 发布生命周期：Agent 创建即生效、保存即更新
唯一当前配置；Runtime 在 Run 准入时保存不可变、无 Secret 的配置快照以继续支持恢复和审计。
V1081 已完成 AgentVersion contract 存储退役；V1082 新增的只是跨创建者编辑时的临时 Proposal 和
精确 Governance Approval，不是 Agent 配置历史，也不会进入 Runtime Run snapshot。

## 1. 文档目的

本文定义 SpaceAgent 最终要实现的完整产品业务，而不是描述当前已经交付的全部能力。
当前代码已经完成 Identity、Agent、Inference、Knowledge、Conversation、Memory、
Runtime、Ledger、Provider/ModelPool Foundation、Agent 显式版本工作流、
Project/Task/TaskPlan、SourceRepository/Workspace/ProjectBlueprint、Coding Runtime/Artifact，
以及 M22 deterministic Multi-Agent authority loop、M23 durable Runtime coordination、
M24-PR1 Observability/Monitoring operations、M24-PR2 Governance/Approval gates、
M24-PR3 authoritative Automation Schedule/Execution、M24-PR4 backend Tracing read model。
本文中 Agent 定时激活与自适应路由、Chat TaskPlan 自动调用/执行、远程自动合并、Webhook/Repository Event Automation、高级 Governance
与其余产品 UI parity 仍需按里程碑实现。

## 2. 产品定位

SpaceAgent 是一个面向个人与组织的持久化 Agent 平台，提供两种产品模式：

- **Chat Mode**：日常对话、检索资料、分析内容、编辑文档和低频文件操作；
- **Project Mode**：面向软件工程和大量文件修改，提供 Repository、Workspace、TaskPlan、
  Checkpoint、长期项目知识、恢复、换 Agent 接力和 Multi-Agent 协作。

两种模式共享 Organization、User、ModelPool、Agent、Conversation、Task、Plan、Runtime、
Context、Memory、Knowledge、Skill、MCP、Tool、Ledger 和审计基础。

## 3. 总体业务与物理拓扑

```text
Web / CLI / Desktop / External Client
                 |
                 v
        Java platform-server
        ├── Identity / Organization
        ├── Provider / ModelPool
        ├── Agent / Current Configuration
        ├── Chat / Project / Task / TaskPlan
        ├── Conversation / Context / Memory / Knowledge
        ├── Runtime / Checkpoint / Recovery / RunEvent
        ├── Tool / Skill / MCP / Ledger
        ├── Workspace / Artifact
        ├── Automation / Governance / Observability
        └── Integration / Security
                 |
                 +--> PostgreSQL: 权威业务、Runtime、Ledger、Metadata
                 +--> Git/Worktree: Project 源码与 Workspace
                 +--> S3/MinIO: Artifact 与大型对象
                 +--> Redis: Cache、Rate Window、短期 Lease 辅助状态
                 +--> Model/Embedding Providers
                 +--> TypeScript multi-agent-orchestrator (LangGraph.js)
                 +--> isolated sandbox-worker
```

### 3.1 语言边界

| 语言 | 最终职责 |
| --- | --- |
| Java | 业务领域、权限、密钥、ModelPool、Task/Plan、Runtime、Checkpoint、Ledger、Memory、Workspace/Artifact Metadata、审计 |
| TypeScript | Web 交互；Multi-Agent Supervisor/Subagent/Handoff/Plan reasoning；不拥有权威业务状态 |
| Python | OCI Sandbox Worker；不参与 AI/Multi-Agent 编排 |

### 3.2 独立平台管理控制面（M40 目标）

```text
Future Admin Web / Admin CLI
  -> Java platform-admin-server
       -> PostgreSQL spaceagent_admin（管理员身份/MFA/Session/Command/Audit）
       -> mTLS + 短期 service JWT
            -> platform-server internal SystemAdministration APIs
                 -> owner modules -> spaceagent_platform
```

`SystemAdministrator` 不是 User，不携带 Organization/Tenant claim。Admin 服务不直连
`spaceagent_platform`，也不持有 Provider/MCP 解密密钥；平台业务仍由现有 Java 模块权威写入。
详细设计见 `PLATFORM-ADMIN-CONTROL-PLANE.md` 和 ADR-034。

## 4. Organization 与用户生命周期

产品层统一使用 **Organization**；现有技术模型中的 Tenant 是同一个安全边界，不能再创建一套
重复的 Organization/Tenant 数据。

### 4.1 核心实体

```text
Organization
├── creatorUserId（唯一）
├── status: ACTIVE / DELETING / DELETED
├── Membership[]
├── ModelPool[]
├── Agent[]
├── Project[]
└── Organization Policy / Audit
```

Membership 角色：

- `OWNER`：唯一创建者/组织所有者，天然拥有管理权限；
- `ADMIN`：组织管理者，可有多位；
- `MEMBER`：普通成员；
- `VIEWER`：可选只读成员。

### 4.2 注册与默认组织

```text
Register
 -> 创建 User
 -> 若请求没有选择/绑定 Organization
      -> 创建新的 Personal Organization
      -> 当前用户成为唯一 OWNER
      -> Organization 成为 Active Organization
 -> 签发包含 activeOrganizationId 的 Session/JWT
```

这与当前注册时创建 Personal Tenant + OWNER Membership 的实现方向一致。

### 4.3 加入、切换与迁移组织

必须区分三个动作：

1. **Join Organization**：新增 Membership，不离开原组织；
2. **Switch Active Organization**：切换当前请求安全上下文，不修改 Membership；
3. **Transfer/Leave Organization**：离开原组织，可同时把目标组织设为 Active。

每次切换 Active Organization 必须重新验证 ACTIVE Membership，并轮换/重签访问令牌；不能只信任
客户端提交的 organizationId。

### 4.4 OWNER 离开和空组织销毁

- Organization 始终只能有一位 OWNER；
- OWNER 离开非空组织前必须把所有权转给一名 ACTIVE 成员；
- 若成员全部离开，事务内将 Organization 标记为 `DELETING`；
- 后台清理 Organization 下 Project、Agent、ModelPool、Token、Object Reference 等资源；
- 清理和保留策略完成后标记 `DELETED`；
- “自动销毁”采用可审计的逻辑删除与异步清理，不直接在请求事务里物理级联删除全部数据；
- 若存在运行中 AgentRun、未完成 Task、计费/审计保留要求，先进入受限的 DELETING 状态。

M25-PR2A 固化以下协议，PR2B 实现控制面，M25-PR2C 已实现全部当前 Owner Purge、
Runtime lease drain、Managed Git 清理、Scheduler 和最终 DELETED：

```text
last Membership leave transaction
  -> Organization = DELETING
  -> enqueue durable CleanupJob + retentionNotBefore

Integration Cleanup Coordinator
  -> claim Job with PostgreSQL SKIP LOCKED + lease/fencing
  -> AUTOMATION_FREEZE
  -> RUNTIME_QUIESCE (cancel continuation/run, release fence, wait old leaseUntil)
  -> ARTIFACT_PURGE
  -> RUNTIME_PURGE
  -> CONVERSATION_PURGE
  -> PROJECT_TASK_MEMORY_PURGE
  -> PROJECT_EXTERNAL_AND_DATABASE_PURGE
  -> AGENT_PURGE
  -> INFERENCE_PURGE
  -> GOVERNANCE_PURGE
  -> IDENTITY_FINALIZE (sessions/invitations/memberships, DELETED tombstone)
```

每一步由对应模块的 public Cleanup Application API 所有，要求幂等，并在单独事务后记录
CleanupStep。Identity 只拥有 Job/Step、Membership/Invitation/Session 和 Organization
Tombstone，不得直接访问其他模块 Repository。Integration 显式编排各模块 API，避免让
Inference 反向依赖 Identity，也不把业务型 Cleanup SPI 放入 shared。

保留与销毁边界：

- 保留 User/Profile/Credential、用户拥有的 Knowledge、USER Memory、迁移/外部审计证据、
  CleanupJob/Step 摘要和最小 DELETED Organization Tombstone；
- 删除 Organization-scoped Session/Invitation/Membership、Automation、Runtime/Ledger、
  Artifact Metadata、Conversation、PROJECT/TASK Memory、Project/Task/Plan/Workspace/Source、
  Agent/CurrentConfiguration/Key、Provider/ModelPool Secret、Governance；
- Managed Git worktree/mirror 由 Project 先清理外部资源再删元数据；Local Bridge 服务器只
  撤销 opaque capability，不能声称删除客户端本地根目录；
- 若未来 Artifact 使用 S3/MinIO，必须存在幂等对象删除适配器；否则该步骤 BLOCKED，不能
  伪造完成；
- `platform_users.tenant_id` 继续指向最小 Tombstone，登录按已有逻辑回退到其他 ACTIVE
  Organization，不能为了“物理删除租户行”破坏用户跨组织身份。

## 5. Provider、Model 与 ModelPool

### 5.1 Provider Connection

用户可以创建用户私有或 Organization 共享的 Provider Connection：

```text
ProviderConnection
├── organizationId
├── createdByUserId
├── visibility: PRIVATE / ORGANIZATION
├── providerType
├── baseUrl
├── encryptedApiKey / secretRef
├── status: DRAFT / TESTING / ACTIVE / UNHEALTHY / DISABLED
├── endpoint policy
└── lastConnectionTest
```

安全规则：

- API Key 永不返回明文；
- 使用 KMS/Envelope Encryption 或现有版本化 AES-GCM 边界；
- Base URL 必须经过 HTTPS、Host Allowlist、DNS/IP/Redirect SSRF 校验；
- 测试连接只能通过 Java Inference Infrastructure 执行；
- 测试记录延迟、能力、模型列表和错误摘要，日志不记录 Secret；
- 连接测试成功后才允许进入 ModelPool。

### 5.2 ModelPool

ModelPool 是 Agent 使用的稳定模型入口，不等同于单个 Provider：

```text
ModelPool
├── organizationId / ownerUserId / visibility
├── PoolMember[]
│   ├── providerConnectionId
│   ├── modelId
│   ├── priority / weight
│   ├── capability tags
│   └── health state
├── routingPolicy
├── fallbackPolicy
├── token/cost/latency limits
└── status
```

Java Inference 模块负责：

- Capability、Context Length、健康度、成本和延迟路由；
- Primary/Fallback；
- Provider 并发与限流；
- ModelCallLedger、Usage 和审计；
- 为每次 AgentRun 固定实际 Provider/Model 决策证据。

TypeScript Multi-Agent 服务只接收 `modelPoolRef` 或经 Java 解析后的短期模型调用能力，不接收
Provider Secret。

## 6. Agent 管理模块

Agent 是独立的 Organization 业务模块，不与 Runtime/Project/Conversation 混在一起。

```text
AgentDefinition
├── organizationId
├── ownerUserId
├── name / description / avatar / tone
├── status
└── currentConfiguration

AgentCurrentConfiguration（唯一、可修改）
├── modelPoolId
├── model selection constraints
├── systemPrompt
├── persona / replyTone / language
├── contextWindow / maxOutputTokens / tokenBudget
├── temperature / reasoning / maxTurns / timeout
├── Skill bindings
├── MCP bindings
├── Tool schemas and parameters
├── Knowledge bindings
├── memoryPolicy / ragPolicy
├── file/network/permission policy
└── configHash / revision
```

创建或更新 Agent 时先校验 ModelPool、Knowledge、Skill、MCP 和权限引用，然后在同一个 Agent
事务中立即写入当前配置。产品不再存在 Draft、Review、Publish、Deprecate、Rollback、版本历史或
定时激活。并发保存使用 revision CAS，过期写入必须重新加载，不能静默覆盖。

每个新 AgentRun 在准入时从当前配置创建一次 Runtime-owned `AgentRunConfigurationSnapshot`。
该快照固定 Prompt、ModelPool、Tool、Skill、MCP、Knowledge 和权限引用，仅供该 Run 的执行、恢复、
Handoff 和审计；它不是可管理的 Agent 发布版本。修改 Agent 只影响之后
创建的 Run，已经开始的 Run 继续读取自己的快照。

## 7. Conversation、Task 与 TaskPlan

### 7.1 基本关系

```text
Conversation
├── mode: CHAT | PROJECT
├── organizationId / userId
├── projectId（PROJECT mode 必填）
├── defaultAgentId
├── activeTaskId
├── Message[]
└── Task[]

Task（用户大目标）
├── goal / description
├── constraints / acceptanceCriteria
├── state
├── currentTaskPlanVersionId
└── childTask[]

TaskPlan（执行策略，可版本化）
├── rootTaskId
├── version
├── status: DRAFT / PROPOSED / APPROVED / ACTIVE / COMPLETED / CANCELLED
├── PlanStep[]
└── generatedByRunId / generatedByAgentId / approvedByUserId

PlanStep
├── childTaskId
├── dependencyStepIds（DAG）
├── requiredCapability / preferredAgentRef
├── expectedOutput
├── acceptanceCriteria
├── retry/timeout/approval policy
└── state
```

Conversation 是交互容器，不是 Agent Runtime。每个可执行用户目标产生一个 Root Task；Conversation
可以保存历史 Task，同时只有一个 `activeTaskId`。TaskPlan 是 Task 的执行策略，PlanStep 可引用子
Task，不能把 Plan 文本塞进 Conversation 后当作权威状态。

M54-PR1 已实现普通 Chat 的 Root Task 闭环；M54-PR2 进一步让 LangGraph.js 的结构化
`PLAN_PROPOSED` 经过 Java scope/Agent/Run-snapshot/DAG 校验后，原子持久化为 CHAT-scoped TaskPlan、
Child Task 与 PlanStep。Java 生成全部 ID，并以 source AgentRun + proposal hash 幂等；用户可审核、
激活或取消。CHAT 与 PROJECT scope 互斥且不创建隐藏 Project。M54-PR3A 已增加 default-off
自动 Planner 调用和 `chat-plan-review/v1` 同 Run 等待；M54-PR3B 已实现 lease-fenced 逐 PlanStep
执行、审批/UNKNOWN 中途恢复、完成 Step 证据与最终汇总。

### 7.2 Plan 生成规则

1. Java 编译当前 User、Organization、Mode、Project、Conversation、Memory、Knowledge 上下文；
2. TypeScript Planner Agent 生成结构化 Task/TaskPlan Proposal；
3. Java 使用 Zod 对应的共享 JSON/Protobuf Schema 再验证；
4. 检查 DAG 无环、权限、预算、Tool/Skill/MCP、Acceptance Criteria；
5. 需要时让用户 Review/Approve；
6. Java 持久化 TaskPlan Version 和 Child Tasks；
7. Runtime 执行 ACTIVE Plan，不执行未批准的危险计划。

## 8. Chat Mode

Chat Mode 面向日常对话、资料检索、文档编辑和低频文件修改。

### 8.1 Chat 业务链

```text
User Message
 -> Resolve Active Organization
 -> Resolve Conversation default Agent + current configuration
 -> Start Run and persist immutable Run configuration snapshot
 -> Compile user profile + reusable long-term memory
 -> Compile Conversation short-term context
 -> Compile optional Knowledge / File references
 -> Planner 判断是否需要显式 TaskPlan
 -> 创建 Root Task
 -> 生成 TaskPlan + Child Tasks
 -> Supervisor 执行搜索/读取/文档/Tool/MCP/Skill Steps
 -> Reviewer/Synthesizer 汇总证据
 -> 返回结果或请求用户批准
 -> 关闭/继续 Task
 -> 提取长期 MemoryCandidate
```

对于简单问答，TaskPlan 可以是一个单 Step；不应为了展示 Multi-Agent 而强制调用多个 Agent。

### 8.2 示例：查询最近 GitHub 活跃 AI 项目

```text
Root Task: 返回最近活跃的 GitHub AI 项目及证据

Plan
  Step 1: 明确“最近/活跃/AI”的时间、指标和输出格式
  Step 2: Search Agent 通过 GitHub/API/MCP 获取候选项目
  Step 3: Evidence Agent 校验 stars、commit、release、contributor 活跃度
  Step 4: Rank Agent 按可解释规则排序
  Step 5: Reviewer 检查重复、时间和来源
  Step 6: Synthesis Agent 返回结构化结果和链接
```

独立查询可以并行，但最终证据校验和输出必须有一个明确的汇总节点。

## 9. Project Mode

Project Mode 面向 Coding Agent、大量文件修改、长周期开发和可恢复执行。

### 9.1 SourceRepository 导入

支持两种入口：

1. **Local Project**：CLI/Desktop Local Workspace Bridge 注册用户授权的根目录；浏览器不能直接把
   任意本地绝对路径交给远程服务器读取；
2. **Remote Repository**：最终统一通过 Marketplace 安装的 GitHub MCP；用户可以 OAuth 登录
   自己的 GitHub 账户后选择私有/Organization/公共仓库，也可以提交 public GitHub URL 发现；
   Native GitHub OAuth/API 已在 M38-PR2 删除。

MCP OAuth/Token 由 Java 加密保存为 Connection/Auth Reference；GitHub MCP Tool 调用仍经过
Java 权限、Governance 和 ToolExecutionLedger，MCP 不直写 Project 数据库。
私有仓库 checkout 使用 Tooling 加密保存的 3-10 分钟 grant；Project 只通过内存 lease
把 Basic/Bearer Header 交给 Git 子进程环境，成功/失败后消费清除，崩溃遗留由数据库时钟清理。

### 9.2 Project 结构

```text
Project
├── Organization / Membership
├── ProjectDirectory[]
│   └── Conversation[]
├── SourceRepository[]
├── ProjectBlueprint
├── ProjectMemory
├── Task[] / TaskPlan[]
├── Workspace[]
├── Artifact[]
└── Audit / Governance Policy
```

### 9.3 ProjectBlueprint

Project 第一次导入或用户首次提出开发目标时，Agent 生成并由 Java 持久化结构化蓝图：

- 产品/开发目标与需求文档；
- 模块划分和依赖边界；
- 架构决策与 ADR；
- 关键目录、启动/构建/测试命令；
- 环境配置名称和 Secret Reference（不保存 Secret 明文到 Memory/文档）；
- 编码规范、安全边界和禁止范围；
- 已知风险、未解决问题；
- Task/Plan 状态和 Acceptance Criteria；
- 最近成功 Checkpoint、Artifact 和验证证据。

ProjectBlueprint 必须有版本、来源、更新时间、生成 Run/Agent 配置哈希和用户确认状态，不能只有向量化文本。

### 9.4 Workspace 与 Coding Loop

```text
TaskPlan Step
 -> Provision isolated Git Worktree/Workspace
 -> Compile ProjectBlueprint + Task + Repository + Memory Context
 -> Persist Run configuration snapshot / ModelPool decision
 -> Inspect files
 -> Edit through ledgered tools
 -> Compile/Test/Lint
 -> Persist RunEvent + Checkpoint + Artifact
 -> Reviewer validates Acceptance Criteria
 -> Commit/Patch/Merge proposal
 -> Complete Child Task
 -> Consolidate Project Memory
 -> Clean/Archive Workspace
```

并行 Agent 不共享同一个可写 Worktree。每个可并行 Child Task 使用独立 Workspace；Merge/Integration
由专门 Step 或 Agent 处理，避免多个 Agent 同时覆盖文件。

M51-PR1 将目录层级固化为 `Project -> ProjectDirectory -> Conversation`。ProjectDirectory 只保存
SourceRepository 内的逻辑相对路径，不接受浏览器绝对路径。只有 SourceRepository 根目录可绑定当前
Sandbox Workspace；新的 Coding Run 同时固定 ProjectDirectory 和 Workspace，后续请求不能替换。
M51-PR2 将恢复输入固化为 Runtime 权威保存的不可变 Project Execution Context Snapshot：通过各
Owner Application API 组合 Blueprint/TaskPlan/Conversation/Artifact/Model/Tool/Approval 引用，并且
只在绑定的 OCI Sandbox Workspace 内读取 Git HEAD、Status 和有界 tracked/untracked Patch。快照是
带 SHA-256 的时间点证据，不取代任何模块的当前状态；UNKNOWN 只生成阻断和对账下一步，不自动重试。
M51-PR3 在 Task 创建前加入 Project-owned Intake Job：只接受 READY 的远程 SourceRepository 根目录，
由租约 Worker 建立 `workspaces/{jobId}` 临时 detached worktree，并以只读、无网络 OCI Sandbox 执行
allowlisted Git 检查。经过截断与 Secret 行脱敏的证据通过当前 Agent 配置、Runtime Run snapshot、ModelPool 和
ModelCallLedger 生成严格结构化提案；提案不携带持久化 ID，也不会提前创建 Blueprint/Task/Plan。
只有原 owner 确认完全相同的 proposal hash 后，Java 才在一个事务内确认 Blueprint、创建 Root/Child
Task、激活 TaskPlan，并把原 Conversation 聚焦到 Root Task。拒绝不会产生这些业务实体。
M51-PR4 将单个 ACTIVE PlanStep 接入 Runtime-owned `ProjectCodingJob`：Job 固定完整 Project/
Directory/Conversation/Task/Plan/Agent/Reviewer Agent 范围，并在各自 Run 准入时固定配置快照，以 PostgreSQL lease/fencing
驱动有界模型 Tool 循环。每个 Child Task 使用独立可写 Workspace；File/Document/Git/写删/测试和
Commit 准备全部进入无网络 OCI Sandbox，旧宿主机 Workspace Gateway 已移除。精确 Governance
审批会在 effect 前暂停并以同一 Tool ID 恢复；成功测试和 Acceptance Artifact 后由不同 reviewer Agent
审核，修改意见可进入有界下一轮，批准才完成 Run/PlanStep 并生成 manual-first SourceMerge Proposal。
远程 push/PR/merge 仍不会自动执行。
M56-PR1 在该单 Step 循环上增加 Plan 级串行调度。Owner 只提交一次审核后的 Directory/Source/
Conversation/Agent/Reviewer Agent/base-ref 绑定；Java 可原子激活 APPROVED Plan，并按 DAG
依赖与 sequence 仅创建一个 ready Coding Job。Job 启动同步 Root/Child/Step，审核完成与下一 Job
创建处于同一事务，最后一步关闭 TaskPlan/Root。重复 execute 返回现有 Job；UNKNOWN/租约歧义不
推进也不重试。并行 ready Step 仍等待独立 Workspace、Agent 分配和 SourceMerge 顺序策略。
M51-PR5 已将换 Conversation、Agent 和模型接入同一持久化恢复链：只允许没有活动租约的暂停/
阻断 Coding Job 发起，Runtime 固定源/目标 Job 与 Run、Recovery Snapshot 和原 Workspace，源 Run
进入 HANDED_OFF，源 Run 以精确 handoff 标记终止但不取消 PlanStep；目标 Agent 通过新 Run 的当前
配置快照和自己的 ModelPool 继续执行。目标上下文
自动加入有界 Project Memory 和 Recovery Package，未调和 UNKNOWN 会继续阻断。完成审核与本地
SourceMerge 后，独立 PostgreSQL lease/fence Finalizer 写入幂等 Project Memory，并由 Project
归档 Workspace；远程 Git 仍不自动修改。

### 9.5 中断、恢复与换 Agent

恢复包至少包含：

- ProjectBlueprint Version；
- Root Task、TaskPlan Version、当前 PlanStep/Child Task；
- Workspace/Git HEAD、dirty diff、patch/commit references；
- pinned Run configuration snapshot 和实际 Model selection；
- Conversation ContextSnapshot；
- unresolved tool/model UNKNOWN entries；
- latest Checkpoint、RunEvent cursor；
- tests/build status、Acceptance Criteria evidence；
- next action、blockers、required approval。

用户更换模型或 Agent 时创建新的 AgentRun/Handoff，旧 Run 保留原配置快照。新 Agent 从 Java
生成的 ContextPackage/HandoffPackage 接力，不依赖旧模型的进程内存。

## 10. 三层记忆体系

### 10.1 长期记忆 Long-term

跨 Chat/Project、跨 Conversation 使用：

- 用户画像、语言、回复语气、格式偏好；
- 稳定工作习惯、持续要求；
- 经确认的事实、决策和可复用经验；
- Organization 共享政策（与用户私有记忆分离）。

每轮结束只生成 MemoryCandidate，经过置信度、敏感信息、冲突和 Review 策略后再 Consolidate。
用户必须能够查看、更正、删除和禁止某类自动提取。

### 10.2 中期记忆 Mid-term / Project Memory

覆盖一个 Project 的生命周期：

- ProjectBlueprint、需求、模块拆分和边界；
- 架构决策、环境配置名称、命令和依赖；
- Task/Plan、Checkpoint、Handoff、Artifact、测试证据；
- 未解决问题、技术债和下一步；
- 可跨 Conversation 恢复的 Project ContextSnapshot。

中期记忆由结构化 PostgreSQL 状态、版本化文档/Artifact 和可检索 Memory 共同组成，不能只靠向量库。

### 10.3 短期记忆 Short-term / Conversation Context

只服务当前 Conversation/AgentRun：

- 最近 Message；
- 当前 Active Task/PlanStep；
- Tool/Model 结果摘要；
- ContextPackage 和 ConversationContextSnapshot；
- Token Budget、压缩摘要和当前推理工作集。

短期上下文可压缩和淘汰；需要跨会话使用的信息必须经过 Candidate/Consolidation 进入中长期层。

### 10.4 记忆流转

```text
Conversation Short-term Context
 -> MemoryCandidate
 -> Task completion / explicit review
 -> Project Mid-term Memory or User Long-term Memory
 -> conflict/version/sensitivity policy
 -> future ContextCompiler recall
```

## 11. Java Runtime 与 TypeScript Multi-Agent

### 11.1 Java Runtime 状态机

最终 Runtime 负责：

- Task/Plan/Agent/ModelPool/Workspace 引用校验；
- AgentRun、RunStep、Checkpoint、RunEvent、Recovery；
- Worker Lease、Heartbeat、CAS/Fencing；
- ModelCallLedger 和 ToolExecutionLedger；
- Approval、Budget、Timeout、Cancellation；
- Artifact 和 Acceptance Evidence；
- SSE/WebSocket Cursor 和 Resume；
- Multi-Agent 调用的授权与持久化边界。

### 11.2 TypeScript Multi-Agent 模式

优先采用成熟 LangGraph.js 模式：

- **Router**：一次分类选择路径；
- **Supervisor + Subagents**：主 Agent 保持用户上下文，子 Agent 作为工具完成隔离子任务；
- **Handoff**：让另一个 Agent 接管后续交互；
- **Subgraph**：复杂专业 Agent 拥有独立 Graph；
- **Parallel branches**：无依赖的 PlanStep 并行；
- **Reviewer/Judge**：在完成/合并前验证结果和证据；
- **Human interrupt**：危险 Tool、计划变更、成本超限或 Merge 前等待批准。

不为简单任务强制使用 Multi-Agent。Supervisor 必须受最大 Agent 数、最大并发、Token、时间、成本、
递归深度和重复委派检测限制。

### 11.3 调用链

```text
Java Runtime
 -> build OrchestrationRequest
 -> TypeScript LangGraph Supervisor
 -> return typed command
 -> Java validates and persists
 -> Java Inference/Tool/Workspace action
 -> Java writes Ledger/Checkpoint/RunEvent
 -> invoke TypeScript with durable cursor
 -> complete / wait user / recover / handoff
```

## 12. Skill、MCP 与 Tool

- Skill 是版本化、可审计的 Agent 能力包；
- MCP Server Connection 归 User/Organization 管理，Secret 加密；
- Tool Schema 使用 JSON Schema/Zod 共享契约；
- Agent 当前配置绑定允许的 Skill/MCP/Tool 与参数，并复制进 Run snapshot；
- Tool 调用必须经过 Java Permission、Approval、ToolExecutionLedger 和 Sandbox Gateway；
- Search/File/GitHub 等只读工具可以按策略自动批准；
- Shell、Git push、删除、外部写操作需要更高权限或人工批准；
- UNKNOWN 副作用必须 Reconcile，不能自动重试。

当前 M33-PR1 已实现上述 Tool 执行基础：Agent 校验、Inference function schema 与 Java
Runtime 使用同一份 16-Tool catalog；外部网络、Knowledge、Project Workspace/Git/Document、
动态 MCP/GitHub 和 Coding 均通过 owner Application API。动态 MCP 会二次核验远端广告的
Schema/只读注解，副作用歧义进入 UNKNOWN。M53-PR1 已实现通用 Chat approval/resume：原 Run、
回复占位和 Tool 链进入版本化 Runtime Checkpoint，精确审批后由 PostgreSQL Worker Lease 保护同
Run 恢复。M53-PR2 已为 Document/Coding Workspace write/delete 增加只读后置状态核验和同 Run
Chat 恢复；MCP mutation/command 因无通用证明协议继续保持 UNKNOWN。M55-PR1 已实现 Skill
Definition/immutable SkillVersion Registry 与历史精确绑定；M74 将绑定迁移到
Agent 当前配置并复制到 Run snapshot；M55-PR2 已将固定 SkillVersion 接入 Chat Planner、
normal/planned Chat、Project Coding/Reviewer 上下文与 hash-only RunStep
证据。Skill 不执行代码且不授予权限；非 Project 文档空间仍未实现。

M50-PR1 进一步将 MCP Catalog 的稳定 Entry 与 Publisher、ServerVersion、Remote Transport
分离，并让 Installation 固定 ServerVersion。M50 后续已完成 Official Registry、通用 OAuth 和
Connection Qualification/Health；M61-PR1 已完成 Agent current-config exact MCP binding 以及有界 Resources/
Prompts。MCP Tasks 为 `DEFERRED_UPSTREAM / NOT IMPLEMENTED`：当前不声明能力、不发送 Task 方法，
远端 Task capability/result 稳定返回 `MCP_TASKS_UNSUPPORTED`，Java Continuation 仍只管理平台任务。

## 13. Artifact、Automation、Governance、Observability

### Artifact

保存 Patch、Commit、构建物、报告、文档、截图、测试日志、Coverage、验收证据和对象存储引用，关联
Organization/Project/Task/Run/PlanStep/Agent；精确执行配置由 Run snapshot 关联。

### Automation

支持 Schedule、Webhook、Repository Event、Task Trigger 和 Follow-up；所有触发创建新的 Task/Run，
不能绕过 Runtime、权限和 Ledger。

当前 M24-PR3 已实现 periodic/one-time Schedule、手动 Trigger、PostgreSQL clock +
`SKIP LOCKED` due materialization、唯一 occurrence、Governance 等待/消费、固定 Run 配置快照、
Conversation/AgentRun 与 `AUTOMATION_EXECUTION` Continuation。M74 后每次触发在新 Run 准入时读取
Agent 当前配置，不存在可管理的 Agent 发布版本。Webhook、Repository Event、
Task/Follow-up Trigger 和 effect-aware 自动重试仍是目标扩展。

### Governance

支持 Organization Policy、模型/工具/网络/文件权限、配额、预算、人工审批、数据保留、敏感信息和
审计策略。

当前 M24-PR2 已实现 Policy、ApprovalRequest/Decision、职责分离、TTL、精确 actor/action/
resource/operation hash 绑定和一次性消费，并接入 Coding Runtime 文件/命令边界；M33 已把
NETWORK_ACCESS 和 Document mutation 接入 Runtime Tool。M53-PR1 再把通用 Chat Tool 的审批
等待、耐久快照、并发 fenced resume 和最终回复接入同一 Governance 边界。数据保留、
M53-PR2 已实现 Workspace Tool-specific UNKNOWN 调和；M61-PR2 进一步为 GitHub issue update 与本地
Git config command 增加 exact、只读、hash-only verifier，并通过原 Tool Ledger CAS 与同 Run replay
恢复。其它 MCP/command 仍保持 UNKNOWN；remote merge 的实际执行门仍是目标能力。

### Observability

支持 Run/Agent/Model/Tool Trace、Usage、Cost、Latency、Error、UNKNOWN、Recovery、Handoff、Task
完成率、Acceptance 通过率、SLO 和告警；Trace 不能成为业务恢复源。

当前 M24-PR1 已实现 Monitoring，M24-PR4 已实现 owner-scoped Trace list/detail/stats 与
RunEvent、ModelCallLedger、ToolExecutionLedger、AutomationExecution、Handoff/Delegation/
Review、Artifact 关联；M27 已补齐有完整价格证据的可信成本；M52-PR1 已补齐 Prometheus/
OpenTelemetry 基础、Agent Run/Model/Tool/UNKNOWN/Latency/Token/Cost/Handoff/Review/Acceptance
低基数 SLO 指标、Grafana 面板与 Alertmanager 告警控制面。可信 first-token timestamp、稳定
GenAI semantic spans 和生产外发通知 receiver 的实机验收仍是目标能力。

## 14. 权威数据归属

| 数据 | 权威 Owner |
| --- | --- |
| Organization/User/Membership | Java Identity + PostgreSQL |
| Provider Secret/ModelPool/Usage | Java Inference + PostgreSQL/KMS |
| AgentDefinition/CurrentConfiguration | Java Agent + PostgreSQL |
| AgentRunConfigurationSnapshot | Java Runtime + PostgreSQL |
| Project/Task/TaskPlan/PlanStep | Java Project + PostgreSQL |
| Conversation/Message/Snapshot | Java Conversation + PostgreSQL |
| Long/Mid/Task Memory | Java Memory/Project + PostgreSQL/Object Storage |
| AgentRun/Checkpoint/RunEvent/Handoff | Java Runtime + PostgreSQL |
| Model/Tool idempotency | Java Ledger + PostgreSQL |
| Source and Workspace | Git/Worktree，Java 管理 Metadata |
| Artifact binary | S3/MinIO，PostgreSQL 保存 Metadata/Reference |
| Cache/短期锁 | Redis，可丢失/重建 |
| Multi-Agent graph definition | TypeScript source/versioned bundle |
| Multi-Agent durable state | Java Runtime projection；TypeScript 不独立拥有 |
| SystemAdministrator/MFA/Admin Session/Command/Audit | Java platform-admin-server + PostgreSQL spaceagent_admin |
| Platform-wide admin business mutation | Java platform-server owning module；Admin 服务只发出审计命令 |

## 15. 最终端到端业务链

### 15.1 Onboarding

```text
Register/Login
 -> create or join Organization
 -> choose Active Organization
 -> configure Provider Connection
 -> test connection
 -> create ModelPool
 -> create Agent（立即可用）/ save current configuration
```

组织协作编辑不引入 Agent 版本：创建者或当前 Organization OWNER 直接 Save；其他具备写权限的活跃成员
修改他人 Agent 时，保存的是临时 Proposal，只有当前 OWNER 批准后才以 revision CAS 更新唯一当前配置。
Proposal 在批准、拒绝、过期或失效后清除正文，只保留有界审计摘要；待审批内容不会进入新 Run。

### 15.2 Chat

```text
Create Conversation
 -> bind default Agent
 -> user request
 -> create Root Task
 -> propose/approve TaskPlan
 -> execute Agent/Subagents + Tool/MCP/Skill
 -> synthesize answer/evidence
 -> persist Conversation/Task/Run
 -> extract long-term MemoryCandidate
```

### 15.3 Project Coding

```text
Import Local/GitHub Repository
 -> create ProjectBlueprint
 -> create Conversation + Root Task
 -> propose TaskPlan/Child Tasks
 -> provision Workspaces
 -> execute inspect/edit/test/review branches
 -> checkpoint and stream RunEvents
 -> recover or handoff when interrupted
 -> produce Artifact/Patch/Commit proposal
 -> validate Acceptance Criteria
 -> complete Task
 -> consolidate Project/User Memory
 -> archive Workspace
```

## 16. 交付里程碑顺序

1. Organization lifecycle、Active Organization、ownership transfer、empty-org cleanup；
2. Provider Connection Test、ModelPool、Priority/Fallback Foundation（M14-PR1 COMPLETE）；
3. Agent-to-ModelPool 绑定（M15-PR1 COMPLETE）与显式 Version workflow（M15-PR2 COMPLETE）；
4. Conversation Active Task、TaskPlan、PlanStep、Child Task、Approval（M16-PR1 COMPLETE）；
5. TypeScript `multi-agent-orchestrator` skeleton、LangGraph spike、共享 contract（M17-PR1 COMPLETE）；
6. Task-scoped Runtime、RunEvent、UUID/FK convergence（M18-PR1 COMPLETE）；
7. SourceRepository、Native GitHub OAuth/API、Local Workspace Bridge（M19-PR1 COMPLETE）；
   GitHub MCP Marketplace/ledger/import 的内部 contract 已由 M26-PR1~PR4 完成；
   M31-PR1 已基于官方协议完成 Metadata Discovery、Host OAuth2+PKCE、repository tool
   mapping、refresh CAS 与短期 checkout adapter（真实账号验收待 OAuth App 配置）；
8. Workspace/Worktree、ProjectBlueprint 和中期记忆（M20-PR1 COMPLETE）；
9. 单 Agent Coding Loop 与 Artifact（M21-PR1 COMPLETE）；
10. TypeScript Supervisor/Subagents/Handoff/Reviewer Multi-Agent authority loop（M22-PR1 COMPLETE；M28-PR1 Provider-backed reasoning COMPLETE）；
11. Worker Lease、CAS/Fencing、自动 Continuation、SSE Cursor/Active-Active（M23-PR1 COMPLETE）；
12. Automation、Governance、Observability 与产品前端收口（M24-PR1 Observability COMPLETE；M24-PR2 Governance COMPLETE；M24-PR3 Automation COMPLETE；M24-PR4 backend Tracing COMPLETE；frontend changes deferred by user direction）；
13. Organization invitation/empty-Organization durable cleanup（M25 COMPLETE）；
14. MCP Marketplace/GitHub private Workspace、Provider health/routing/budget（M26-M27 COMPLETE）；
15. reviewed manual-first local Source integration（M29-PR1 COMPLETE；remote automatic merge deferred）；
16. backend API-first Trusted Beta Production Release Candidate（M30-PR1 COMPLETE；public untrusted Coding blocked）。
17. backend Runtime Tool Registry/Dispatcher（M33-PR1 COMPLETE；M53-PR1 通用 Chat
    approval/resume COMPLETE；M53-PR2 Workspace Tool-specific UNKNOWN reconciliation COMPLETE；
    Skill Registry/binding M55-PR1 + Runtime context/evidence M55-PR2 COMPLETE；MCP/command reconciliation、
    non-Project document workspace deferred）。
18. disposable OCI Sandbox + Coding command cutover（M34-PR1 COMPLETE；real runc accepted；
    public-untrusted Linux/runsc acceptance deferred）。
19. independent Platform Administration Control Plane（M40-PR5 durable User cleanup COMPLETE；
    非空 Organization 仍要求显式 ownership transfer，不做自动 successor 猜测）。
20. versioned MCP Marketplace model（M50-PR1 COMPLETE；Registry sync、generic OAuth、
    Connection Qualification、Agent binding 和 protocol expansion deferred）。
21. automatic Chat Root Task/Conversation focus/AgentRun binding（M54-PR1 COMPLETE）；durable
    Chat TaskPlan proposal/Child Task DAG/owner review（M54-PR2 COMPLETE；automatic invocation and
    durable review wait M54-PR3A COMPLETE；lease-fenced PlanStep execution M54-PR3B COMPLETE）。
22. mutable Agent current configuration + immutable per-Run snapshot（M74 deterministic implementation
    COMPLETE；旧管理/治理/消费者/存储已移除，最终回归与 Frontend 另行收口）。

每个里程碑继续遵守 inspect -> implement -> compile -> test -> architecture check ->
ExecPlan/status 的受控迁移方式。

## 17. 明确禁止的架构捷径

- 不把 TypeScript/LangGraph 数据库变成第二业务源；
- 不让浏览器直接提交任意服务器本地路径；
- 不把 Provider Secret 发送给 Web、CLI 或 TypeScript Orchestrator；
- 不让 Multi-Agent 绕过 Java Runtime/ToolExecutionLedger；
- 不用完整 Conversation 重放猜测 Task/Run 状态；
- 不把向量检索结果当作 ProjectBlueprint/TaskPlan 的权威结构；
- 不让多个 Agent 并行写同一个 Worktree；
- 不为了展示 Multi-Agent 对简单任务做无意义委派；
- 不在 Organization 请求事务中不可恢复地物理删除全部资源；
- 不把当前 Python AI Orchestrator 描述成最终目标实现。
- 不把平台最高权限塞进隐藏 SYSTEM Organization 或 Tenant RBAC；
- 不让 Admin 服务直接读写 `spaceagent_platform` 或返回明文/密文 API Key；
- 不在 HTTP 请求中级联硬删除 User，必须先停用、检查 ownership/UNKNOWN blocker 并执行耐久清理。
