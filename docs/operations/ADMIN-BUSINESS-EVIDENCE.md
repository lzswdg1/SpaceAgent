# Admin 业务审计、用量与可信资源观察

U02/U03/U05 历史交付为 V1095/107；2026-09-15 审计整改增量推进到 V1097/109，新增运行绑定 OCI 资源观察。
Admin 页面不改、Admin DB 不新增业务表；所有查询通过受限服务 JWT 访问业务所有者 API。资源观察有明确
采样/缺失边界，不代表生产容量、所有远程资源或真实账单已验收。

## 入口

外部管理员只调用 Admin Server 的 `/admin/v1`，必须通过当前环境管理密码登录、有效会话和私有入口检查。
浏览器不能直接访问下表对应的 `/internal/system-admin/v1`。业务平台普通用户/组织管理员 token 不可使用这些内部接口。

| Admin GET 路径 | 参数 | 返回 |
| --- | --- | --- |
| `/business-audit` | `userId`（操作人）、`organizationId`、`outcome`、`from/to`、`page/pageSize` | 脱敏 HTTP 尝试/结果时间线 |
| `/resource-capabilities` | 无 | 可复用命令清单、只读边界和执行条件；不是额外授权 |
| `/agent-change-evidence` | `userId`（提交人/Agent属主/决定人）、`organizationId`、分页 | 现有当前配置审批的 ID、状态、修订与时间；不含提案正文/备注 |
| `/usage/summary` | `userId`、`organizationId`、`from/to` | MODEL、EMBEDDING、TOOL 三类汇总，含查询范围与 generatedAt |
| `/usage/history` | `kind=MODEL/EMBEDDING/TOOL`、上述范围、`status`、分页 | 全状态调用账本元数据 |
| `/resource-observations` | `userId`、`organizationId`、`from/to` | 可信 OCI CPU/内存/网络采样及窗口内最近 Workspace 表观大小，含缺失量/coverage |

默认最近24小时，`from` 包含、`to` 不包含，单次时间窗口最多90天；page 0–10000，pageSize 1–100。
非法查询返回400；业务服务不可用时不能用空数据或0伪装成功。Admin 读取这些数据也会记录自己的审计事件，参数只保存哈希。
业务查询在只读 REPEATABLE READ 快照中组合状态分布、汇总和分页，避免并发更新造成同一响应内口径不一致。
内部新 scope：`system-admin:business-evidence:read`、`system-admin:usage:read`，均不携带租户授权 claims。

### 资源观察口径

仅 HTTP Sandbox 返回的原始 request/response executionId、RunId、ToolCallId 全部匹配后，Integration 才经 Runtime
owner API 记录 RESOURCE_OBSERVED。租户/用户归属来自不可变 Run，不取 Worker 声明或命令输出。
重复 executionId 在同 Run 内去重；字段白名单拒绝文本/路径/请求体。观测丢失不改变已知 Tool 结果，不触发重试。

cpuUsageNanos 是运行中 cgroup 累计采样，不是完整总 CPU；maximumObservedMemoryBytes 是观测到的最大 cgroup
accounted memory，不是峰值 RSS；网络字节缺失为null。latestObservedWorkspaceApparentBytes 仅按窗口内每个
Workspace 最新观测计一次，不累加重复快照；它不是实际分配磁盘、当前完整库存、所属用户存储或硬配额。
缺失值不当0，未录制的历史/远程 Provider/TEI/非运行绑定计算不伪装已覆盖。普通租户读取自己的当前租户/用户
范围 `/api/v1/monitoring/resource-observations`，不能通过 query params 覆盖身份。管理员走独立 scope
`system-admin:resources:observations:read`；现有管理页面不新增组件。

## 审计的事实含义

- 只记录已经过认证、成功匹配控制器的 POST/PUT/PATCH/DELETE。匿名登录/注册、Security Filter 提前拒绝、未匹配路由和内部后台任务
  不冒充已覆盖；Identity 登录审计、Admin 命令账本、Runtime/Tool 等已有证据仍由原 owner 保留。
- `actor_id/tenant_id` 来自认证上下文，不从 body/header/query 猜测。tenant 是操作人的认证范围，不代表被尝试访问的目标一定属于该范围。
- 记录的是可信 route template 和可识别 UUID 目标，不是原始 URI。没有 body、响应正文、提示词、名称、密钥、Tool 参数/结果或宿主路径。
  创建操作尚未有资源 ID 时字段为空。Admin 命令以签名 command ID 关联其原有命令账本。
- 控制器执行前单独提交 ATTEMPT；业务事务回滚不会抹掉它。完成观察单独追加，重复回调不改写首次结果；
  数据库拒绝 UPDATE/DELETE。这里的 append-only 是应用/普通 DML 边界，不承诺抵抗具有 DDL 权限的数据库管理员。
- 无完成记录显示 `UNCONFIRMED`，既可能尚在执行，也可能进程中断，绝不等同成功。
  `HTTP_ACCEPTED`（202）与 `HTTP_SUCCEEDED` 只描述传输响应，不证明异步 Job/模型/Tool 已完成。
  实际效果继续查看对应 command/job/Run/ledger；不盲目重试 UNKNOWN。
- 最小审计元数据与用户表没有级联 FK，业务用户删除不自动清空它；不提供任何审计修改/删除 API。
  需要改变保留策略时必须另行设计和授权，不以普通 Admin CRUD 擦除审计证据。

## 用量口径

- 数据来自 Inference/Tooling 自己的账本，没有跨 owner 查询 Mapper/DAO。Runtime 经只读 owner port 提供不可变 actor/tenant 归属。
  V1095 只对仍有 Runtime 来源的历史调用回填归属，不重写调用状态或结果；旧二进制混部产生的缺失归属明确保留。
- `calls` 是保留的逻辑账本条目数，包含运行中、失败、取消、超时、UNKNOWN，以及 Embedding 本地拒绝等状态；
  不是实际网络 POST 数、计费请求数，也不等于成功请求数。幂等重放不会新增同一逻辑条目。
- `knownInputTokens/knownOutputTokens/knownCostMicros/knownDurationMs` 只汇总已知值；全部未知时为 null。
  金额使用 USD 微单位（1 USD = 1,000,000 micros），不转换币种，不把无报价/无 usage 的调用当免费。
  金额沿用现有账本按固定价格和 usage 记录的数值；没有进行 Provider 实际账单对账，不冒充外部结算账单。
- `missingUsageCalls/missingCostCalls/missingDurationCalls` 表示缺失覆盖；失败调用如确有用量也参与汇总。
  Tool 账本未采集 Token 和费用，返回 null/未采集覆盖，不能从 Tool 成功推导成本为0。
- `unattributedCallsInWindow` 是**整个时间窗口**内归属不完整的数量，不是所选用户的子集，不能直接从用户 calls 中相减。
  无法分配的记录不猜测属于哪个用户/组织；历史已被 owner 正常删除的记录不凭空重建。
- 时间范围按调用创建时间筛选，状态/用量为查询时最新观察，不是指定 to 时刻的历史快照。
  耗时是终态账本墙钟时间，可包含等待/恢复，不是 CPU/GPU 执行时间。未知执行结束时间为 null。
- Tool ledger 中的 MCP Tool 不再叠加 MCP Invocation Ledger，避免双重计数；绕过 Runtime Tool ledger 的独立
  MCP 管理探测不计为这里的 Runtime Tool 调用，原 MCP Invocation 证据仍可独立查看。

## 能力与上线边界

用户/组织管理、会话撤销/密码重置、成员/OWNER 转移、MCP Registry review、Artifact 删除重试复用已有耐久命令。
仍要求近期 MFA、reason、幂等及 owner 生命周期校验；feature flag/资源状态也可能拒绝命令。
Agent 配置不允许 Admin 冒充属主修改或越过 OWNER 审批。Provider/MCP 密钥不返回明文；没有通用 SQL、任意行 CRUD、
任意用户 prompt/code 编辑或 UNKNOWN 自动重试入口。资源测量 U04 未实现，不能返回虚构观测值。

发布时先执行前向迁移再更新业务 API，随后更新 Admin Server。无需改 Admin 业务数据库，也不需要 Kafka/Redis。
本轮源码验证不自动授权部署、重启业务容器、真实账号写操作、前端开发或远程 push。

## 本轮验证记录

- V1094→V1095 固定升级、归属回填、幂等重跑、审计写后业务回滚/缺少完成记录、UPDATE/DELETE 拒绝通过。
- 真实组织成员提交他人 Agent 修改返回202，管理员只读 PENDING/修订/参与者元数据，提案正文和决定备注未外泄。
- 全状态 MODEL/EMBEDDING/TOOL、跨用户过滤、缺失用量/费用、归属不可改写、Admin MFA/会话与新服务 JWT scope 通过。
- 完整 Maven：860/860（Shared25 + Platform822 + Admin13），零失败/跳过；快照一致性追加37/37；打包、架构通过。
- 没有启动/重启现有业务服务、没有真实外部调用；U04 资源测量与其专门验收未执行。
