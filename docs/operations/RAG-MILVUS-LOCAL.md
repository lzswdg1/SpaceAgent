# Milvus RAG 本地依赖与验证

状态：SDK 适配与真实 Milvus 集成已通过，M79-PR1 完成。默认业务平台仍不启用 Milvus；异步索引属于 PR2。
实施依据：[设计](../design/enterprise-rag/设计.md)、[计划](../../.agent/ENTERPRISE-RAG-PLAN.md)。

## 固定版本与前置条件

- Java SDK：`io.milvus:milvus-sdk-java:2.6.22`。
- 目标服务：`milvusdb/milvus:v2.6.22`，官方 multi-arch digest 已固定在 Compose 和示例环境中：
  `sha256:a8ac051e59eb084d41bd317ec51aac28553d664e91c43a806ccd6a1538abc1df`。
- Mac Docker Desktop：至少 8 GiB 内存、2 vCPU，并为现有其他服务留出额外容量。调内存可能重启 Docker。
  预检按 Docker 报告的可用 guest memory 判断，为内核开销保留余量，最低要求 7.5 GiB 可用。
- 官方依据：[硬件要求](https://milvus.io/docs/v2.6.x/prerequisite-docker.md)、
  [2.6.22 内嵌部署脚本](https://github.com/milvus-io/milvus/blob/v2.6.22/scripts/standalone_embed.sh)。
- 本地 Compose 使用内嵌 etcd 和本地持久卷，只映射 loopback 的 19530/19091，不暴露 etcd。
  这是单机开发依赖，不是生产 HA 部署证明。BM25/中文 analyzer 在 PR3 的混合检索步骤验收。

## 配置与启动

将 `.env.rag.example` 复制为被 Git 忽略的 `.env.rag`，填写真实镜像摘要和独立密码。核验 digest
可执行 `docker buildx imagetools inspect milvusdb/milvus:v2.6.22`；确认目标 CPU 架构。
`MILVUS_IMAGE` 格式为 `milvusdb/milvus:v2.6.22@sha256:<已验证摘要>`。

在所需环境变量已通过可信方式加载后：

```bash
bash scripts/check-rag-environment.sh
docker compose --env-file .env.rag -f docker-compose.rag.yml --profile rag up -d rag-milvus
```

不要对现有全栈执行 down/rebuild。`--env-file` 提供 Compose 插值，不会自动把应用变量注入现有
platform-server；宿主机应用需显式加载 `PLATFORM_KNOWLEDGE_*` 环境变量后启动。
生产连接使用 TLS 和专用受限账号；本地 override 仅接受 loopback/已知本地服务名。
bootstrap root 密码是第一次初始化输入，已存在卷的密码变更应通过 Milvus 凭据 API 完成。

## 真实集成测试

设置 `SPACEAGENT_MILVUS_TEST_URI` 和 `SPACEAGENT_MILVUS_TEST_TOKEN`，指向专用可测试实例。
测试建立随机命名 Collection，只清理本次 Collection，不调用真实 Embedding 或读用户文档。

```bash
./mvnw -pl apps/platform-server -am \
  -Dtest=MilvusVectorIndexIntegrationTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

必须获得实际执行 PASS；未配置 URI 时该测试会跳过，不能视为 B01B 通过。覆盖创建/重入、
幂等写、hash 清单校验、scope 与 generation 过滤、维度不匹配拒绝和删除后置校验。
并发代次不可变和迟到写清理仍由后续 PG 索引任务协调，单次 SDK precheck 不是分布式 CAS。

## 当前环境证据（2026-09-14）

用户调整并重启后，Docker 实测 `8321994752` bytes / 8 CPUs / aarch64，预检通过，原 8 个服务恢复 healthy。
独立 Compose project `spaceagent-rag-proof` 已启动，容器 `spaceagent-rag-proof-rag-milvus-1` healthy。
使用固定的官方 2.6.22 ARM64 digest，API `http://127.0.0.1:19530`，健康端口 `19091`。
测试仅使用本次随机 bootstrap 凭据，保存在专用测试容器环境中，不打印/提交，不等同于生产账号配置。

真实 Milvus + 离线 Gateway 首轮 6/6；B01B 集中回归 56/56、零跳过。增强实机断言覆盖错误凭据拒绝、
同组织不同 generation 隔离、删除不影响其他 scope/generation，重跑通过。随机测试 Collection 已删除，
实例与专用数据卷保留以供 PR2 复用。此证据不代表文档异步索引、BM25、模型质量或生产 HA 已实现。

### 已解除的历史阻断

Docker `aarch64`，总内存 `4108828672` bytes（约 3.8 GiB），不满足官方要求。
本轮通过本机 Clash 代理（127.0.0.1:7890）读取 Docker Hub 官方 tag metadata，核验 amd64/arm64
manifest；随后 Docker pull 成功，本地 inspect 确认 linux/arm64 和上述 RepoDigest。镜像/网络阻断已解除。
本机物理内存 16 GiB。尚未启动 Milvus，Docker 仍约 3.8 GiB；需要经用户确认将 Docker 分配到
至少 8 GiB 并重启，或提供专用测试 Milvus 后恢复 U03。不能把已下载镜像当作实机集成 PASS。

用户已确认资源调整。当前 macOS 拒绝设置文件访问，Computer Use 权限也未授予；代理未修改
Docker 设置、未停止 Docker，原 8 个容器复查均 healthy。可在 Docker Desktop 的 Resources 内存
设置中手动调整并应用重启；恢复后先复查内存和既有容器，再执行 Milvus 测试。
