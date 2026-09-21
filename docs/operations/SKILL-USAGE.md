# Skill 注册、绑定与上下文证据

更新：2026-09-15。适用于当前无 AgentVersion 的 Agent 配置；Skill 自身仍保留不可变内容 ID。

## 用户路径

1. Agent 页面 → Skill 与工具 → 新建 Skill。填写名称、说明、完整指令，或导入单个 UTF-8 `.md`/`.txt`。
2. 可声明依赖工具，点击“保存并启用 Skill”。这是 Skill 内容保存，不是 Agent 保存。
3. 单独开启所需 Agent 工具，再勾选 Skill，最后保存 Agent。跨所有者编辑仍由现有 OWNER 审批流程处理。
4. 调用追踪 → 选择 Run → Skill 上下文证据 → 查询加载记录。

组织成员读取同组织注册表；只有 Skill 创建者可编辑、发布自己的内容。界面编辑现有 Skill 时
名称/说明只读（现有 API 仅支持创建新内容），不会冒充可修改元数据。编辑内容不会自动替换任何
Agent 已绑定的快照；选择新内容并保存 Agent 才更新绑定。旧 Run 的内容 ID 保持不变。

## 接口与保存恢复

- `GET /api/v1/skills`：真实组织注册表。前端不再把无租户能力目录的空 `skills` 当作注册表。
- `POST /api/v1/skills`：创建 Skill 及草稿。
- `POST /api/v1/skills/{id}/versions`：创建现有 Skill 的新指令内容。
- `GET /api/v1/skills/{id}` + `POST /api/v1/skills/{id}/versions/{versionId}/publish`：核对并启用精确草稿。
- Agent 仍通过既有 `PUT /api/v1/agents/{id}` 提交 `skillIds`；值是 Skill 内容 ID，不是定义 ID。

界面将创建和启用组合为一次用户操作，但后端仍是两次独立写入。内容已创建、启用响应失败时，
保留精确草稿 ID，继续保存前先 GET 核对；已生效则不重复 publish。创建响应不确定且没有 ID 时
停止写入，提示刷新注册表并选择已有记录；不自动重试创建。刷新/重新进入后可通过“编辑／继续保存”
恢复已有草稿。取消编辑不删除已持久化的草稿。编辑现有内容不会自动修改其他 Agent 的绑定。

## 指令与权限边界

- 单条指令最多128 KiB UTF-8；最多16个绑定，组合指令最多256 KiB；依赖工具最多32个。
- 文件导入仅导入文本，拒绝非法 UTF-8，不扫描主机文件或导入目录/引用文件/脚本。
- Planner 的源协议限制为32000字符，包含 Skill 元数据包装。超出时返回
  `AGENT_SKILL_PLANNER_CONTEXT_TOO_LARGE`（413），在 Planner 调用及对应准备证据写入前拒绝，
  不再截断尾部指令。该限制不等于注册存储的字节上限。
- Chat ContextCompiler 保留完整 Skill 源；总上下文装不下全部绑定时返回
  `AGENT_SKILL_CONTEXT_BUDGET_EXCEEDED`。Model/Tool 权限、网络、Workspace 和 Sandbox 仍单独校验。
- `.agents/skills` 是开发规范，不会自动进入租户注册表。绑定 Skill 不会运行脚本或授予权限。

## 准备证据不是模型遵守证明

`GET /api/v1/chat/runs/{runId}/skill-evidence` 支持拥有该 Run 的同租户用户，适用于 Chat/Project Run。
不存在或他人/跨租户 Run 返回404；未登录拒绝。只投影最近一次 `skill-context-bound` 检查点：

- `NO_EVIDENCE`：没有准备记录，不能推断已经加载或执行成功。
- `CONTEXT_PREPARED`：返回 `preparedAt` 和最多16项 `{skillVersionId, configHash}`。
- 损坏或超界证据返回503 `SKILL_EVIDENCE_INVALID`，不返回原始检查点、指令、名称或错误正文。

准备记录在模型调用之前产生，故不证明 Provider 已接受请求，更不证明模型遵守指令；也不是所有
历史步骤的完整列表。前端明确显示该边界，不把配置绑定或读到文件当作执行成功。

## 验证与部署

- 后端定向12/12：Skill 注册 HTTP/PostgreSQL、Chat/Planner 注入与长指令拒绝、Project Coding。
- Web142/142、TypeScript/Vite build、架构与差异检查通过。
- `WEB_TEST_FIXTURE=agent-skills node scripts/verify-web-interactions.mjs`：三语言/尺寸45项断言，
  包括 UTF-8 导入、丢失 publish 响应后的恢复、依赖限制、精确 ID 绑定、只读和证据内容。
- 既有 Agent 工具网格回归54项通过。浏览器测试使用真实组件和假接口；后端使用隔离库与假模型。
- 本批没有真实 Provider 调用、业务数据变更、服务重启或发布，不声明当前8080/9000已包含此修改。
