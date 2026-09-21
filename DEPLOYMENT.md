# SpaceAgent 部署指南

当前唯一 Java 运行时是 `apps/platform-server`，权威数据库是 PostgreSQL
`spaceagent_platform`。生产/共享环境使用 `.env.release` 和严格 release Compose
覆盖，不再部署旧 Gateway、Identity、Agent、Chat 或 Knowledge Service。

## Trusted Beta 边界

- 只允许受信任代码和受控 Workspace。
- 公共不可信代码执行保持关闭。
- Provider、MCP、GitHub 和 Sandbox 密钥必须使用独立高强度值。
- PostgreSQL 是业务、Runtime、Checkpoint、Tool Ledger 和 Automation 的权威存储。
- Sandbox Worker 是私有基础设施控制面，不发布公共端口。

## 首次部署

准备 Docker Engine 26+、Docker Compose、持久化卷和 HTTPS 入口，然后生成配置：

```bash
cp .env.release.example .env.release
chmod 600 .env.release
```

至少填写以下值：

- `DB_PASSWORD`
- `JWT_SECRET`
- `INTERNAL_SERVICE_TOKEN`
- `IDENTITY_ACTIVITY_HASH_KEY`
- `SYSTEM_ADMIN_JWT_SECRET`
- `MODEL_PROVIDER_ENCRYPTION_KEY`
- `MCP_CONNECTION_ENCRYPTION_KEY`
- `AI_EMBEDDING_API_KEY`
- `SPACEAGENT_RELEASE_VERSION`

每个加密 Key 和内部 Token 至少 32 个字符，不要复用数据库密码。保持：

```dotenv
PLATFORM_RELEASE_MODE=trusted-beta
PLATFORM_TRUSTED_CODE_ONLY=true
PLATFORM_PUBLIC_UNTRUSTED_CODE_ENABLED=false
PLATFORM_ALLOW_INSECURE_LOCAL=false
SYSTEM_ADMIN_ALLOW_INSECURE_LOCAL=false
```

执行发布前校验并启动：

```bash
RELEASE_ENV_FILE=.env.release ./scripts/release-preflight.sh
docker compose --env-file .env.release \
  -f docker-compose.yml -f docker-compose.release.yml up -d
```

默认发布 PostgreSQL 与 platform-server。按需增加 Web、Observability 或 Sandbox
profile：

```bash
docker compose --profile web --env-file .env.release \
  -f docker-compose.yml -f docker-compose.release.yml up -d web

docker compose --profile observability --env-file .env.release \
  -f docker-compose.yml -f docker-compose.release.yml up -d

docker compose --profile sandbox --env-file .env.release \
  -f docker-compose.yml -f docker-compose.release.yml up -d sandbox-worker
```

## 健康检查

```bash
curl -fsS http://127.0.0.1:9000/actuator/health/liveness
curl -fsS http://127.0.0.1:9000/actuator/health/readiness
docker compose ps
docker compose logs --tail=200 platform-server
```

Release readiness 会验证数据库连接、Flyway 状态、期望 Schema 版本和发布策略。
非健康 Actuator 与 OpenAPI 入口需要认证。

## Sandbox

启用 OCI worker 时配置：

```dotenv
SANDBOX_MODE=http
SANDBOX_INTERNAL_TOKEN=<独立的至少32字符令牌>
SANDBOX_CONTAINER_USER=999:999
SANDBOX_MAX_CONCURRENT_EXECUTIONS=4
SANDBOX_OCI_RUNTIME=<已验收的运行时，可为空使用默认 runc>
```

Worker 需要访问 Docker API，但子执行容器绝不能获得 Docker Socket。生产环境应在
Linux 上完成非 root、只读根文件系统、无网络、Workspace 子路径、资源限制、超时和
残留容器清理验收。未通过 `runsc`/等效隔离验收前不得开放公共不可信代码。

## Web Search 与外部集成

Web Search 需要独立部署或使用受控 SearXNG 服务：

```dotenv
TOOLING_WEB_SEARCH_MODE=searxng
TOOLING_WEB_SEARCH_BASE_URL=https://search.example.com
```

GitHub 主路径使用 MCP Marketplace 和官方 remote MCP OAuth。配置
`GITHUB_MCP_CLIENT_ID`、`GITHUB_MCP_CLIENT_SECRET`、允许的回调地址及 scopes。
原生 GitHub OAuth 路径已经退役，不再接受 GitHub 密码、PAT 或第二套 OAuth 配置。

## 备份与恢复

发布前和升级前执行：

```bash
RELEASE_ENV_FILE=.env.release ./scripts/backup-platform.sh
RELEASE_ENV_FILE=.env.release ./scripts/verify-platform-backup.sh <backup-directory>
```

恢复必须在受控维护窗口执行，并明确提供备份目录：

```bash
RELEASE_ENV_FILE=.env.release \
  RESTORE_BACKUP_DIR=<backup-directory> \
  ./scripts/restore-platform.sh
```

不要通过删除 Flyway 文件或直接回退数据库 Schema 完成应用回滚。旧迁移必须保持
不可变；需要结构修正时增加新的迁移。

## 更新与回滚

1. 生成并验证数据库备份。
2. 拉取或部署目标不可变镜像版本。
3. 运行 `release-preflight.sh`。
4. 启动新版本并等待 readiness。
5. 执行 `scripts/release-golden-path.sh` 的受控阶段验证。

应用回滚只能回到与当前数据库 Schema 兼容的镜像。Tool、Git、Provider 等结果为
UNKNOWN 时禁止盲目重试，必须按对应 Ledger/Run evidence 调查。

完整操作步骤和发布证据要求见：

- `docs/operations/PRODUCTION-RUNBOOK.md`
- `docs/operations/RELEASE-CHECKLIST.md`
- `docs/architecture/SANDBOX-ISOLATION.md`
