# SpaceAgent 代码与命名规范

## 适用范围

本文最初约束五服务架构；当前主 Java 架构为 `apps/platform-server`。旧 Java
runtime 规则仅是历史证据，源码只存在于未随本公开快照分发的私有归档中，不再作为
rollback source。`shared/shared-kernel`、Go CLI、前端、配置和数据库命名仍受本文约束。
CLI 只允许在旧配置迁移代码中出现 `~/.emotion/config.json`。

执行自动检查：

```bash
scripts/check-naming-conventions.sh
```

## 品牌命名

- 产品名和界面名称统一使用 `SpaceAgent`。
- CLI 命令、Docker Compose 项目语义和配置目录统一使用 `spaceagent`。
- 新代码不得引入 `EmotionCompanion`、`emotion-companion` 或 `Companion` 品牌名。
- 兼容旧数据时必须用 `legacy`、`migration` 或注释明确其用途，不能让旧名称继续扩散。

## Java

### 包名

主架构统一使用：

```text
com.spaceagent.<bounded-context>.<layer>
```

示例：

```text
com.spaceagent.chat.application
com.spaceagent.knowledge.infrastructure.client
com.spaceagent.shared.auth
```

包路径必须与 `package` 声明完全一致。边界上下文固定为
`identity`、`agent`、`chat`、`knowledge`、`gateway` 和 `shared`。

### 类型

- 类、接口、枚举和 record 使用 `PascalCase`。
- 接口使用职责名，不增加 `I` 前缀，例如 `ChatApplicationService`。
- 默认实现使用 `Default` 前缀；基础设施实现使用机制前缀，例如 `Jdbc`、`Postgres`、`Redis`、`Http`。
- Controller 请求和响应分别使用 `Request`、`Response` 后缀。
- 配置聚合类使用 `<Service>Properties`；单项策略使用职责名，例如 `ModelProviderEndpointPolicy`。
- 持久化对象只有在确实区别于领域对象时使用 `DataObject`。
- 测试类使用 `<ClassUnderTest>Test`，并镜像生产代码包路径。

### 方法与变量

- 方法和变量使用 `lowerCamelCase`，布尔值优先使用 `is`、`has`、`can`、`should`。
- 集合使用复数名；ID 使用 `agentId`、`userId`，不要混用 `agentID`。
- 方法名使用动词表达行为，例如 `retrieveKnowledge`、`rotateSecrets`。
- 避免 `handleData`、`processInfo`、`doWork` 等无法表达领域含义的名称。

## 服务与配置

- 活动 Java 应用固定为 `apps/platform-server`；可选进程使用目录名对应的 `kebab-case` 名称。
- YAML 配置键使用 `kebab-case`。
- 环境变量使用 `UPPER_SNAKE_CASE`。
- 平台变量使用 `PLATFORM_` 前缀；Compose 输入使用职责前缀，例如 `SANDBOX_`、`GITHUB_MCP_`。
- 跨进程共享变量只在含义完全相同时使用全局名，例如 `INTERNAL_SERVICE_TOKEN`。
- 新变量不得继续增加含义重复的别名；旧变量兼容必须在文档中标记迁移方向。

模型配置的主变量固定为：

| 用途 | 主变量 |
|---|---|
| 聊天密钥 | `OPENAI_API_KEY` |
| 聊天兼容端点 | `OPENAI_BASE_URL` |
| Embedding 密钥 | `AI_EMBEDDING_API_KEY` |
| Embedding 兼容端点 | `AI_EMBEDDING_API_BASE_URL` |
| Embedding 模型 | `AI_EMBEDDING_MODEL` |

`AI_API_KEY`、`AI_API_BASE_URL` 和 `AI_MODEL` 只作为早期单体与旧 `.env`
的兼容别名。兼容层可以读取它们，但微服务代码、文档示例和新脚本不得把它们作为主变量。

## HTTP API

- 外部 API 使用 `/api/v1` 前缀，资源路径使用小写复数名词。
- 路径参数使用领域 ID，例如 `/agents/{agentId}`。
- 查询使用 `GET`，创建使用 `POST`，整体更新使用 `PUT`，局部更新使用 `PATCH`，删除使用 `DELETE`。
- `retry`、`refresh` 等确实表示状态转换的操作可以使用动作子资源。
- Java DTO 字段使用 `lowerCamelCase`，数据库列使用 `snake_case`。

## 数据库

- 表名和列名使用 `snake_case`。
- 表名使用复数资源名，例如 `conversations`、`knowledge_documents`。
- 主键统一为 `id`，外键使用 `<resource>_id`。
- 时间字段统一使用 `created_at`、`updated_at`、`deleted_at`、`expires_at` 等明确后缀。
- Flyway 文件使用 `V<version>__<lower_snake_description>.sql`。

## 指标与日志

- Micrometer 代码侧指标名使用点分层级，例如 `spaceagent.executor.queue.utilization`。
- Prometheus 自动转换为 `snake_case`，告警规则使用转换后的名称。
- 标签使用低基数名词，例如 `status`、`executor`；禁止把 `userId`、`conversationId` 放入指标标签。
- 日志字段使用稳定的 `lowerCamelCase` 键，并避免记录 API Key、JWT、密码和模型密钥。

## Go 与前端

- Go package 使用简短小写名，导出类型使用 `PascalCase`，非导出标识符使用 `camelCase`。
- Go module 路径跟随实际 Git 仓库地址；个人用户名不是产品品牌，不需要改写。
- React 组件文件使用 `PascalCase.tsx`，Hook 使用 `useXxx`，API 模块按后端权威资源命名。
- TypeScript 类型使用 `PascalCase`，变量和函数使用 `camelCase`，常量使用 `UPPER_SNAKE_CASE`。

## 变更要求

命名变更必须同步更新代码、测试、MyBatis namespace、组件扫描、文档和脚本。
对外 API、数据库字段和配置键的重命名属于兼容性变更，应提供迁移窗口，不能只做字符串替换。
