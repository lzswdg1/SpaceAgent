# Knowledge RAG 后端运行与恢复

## 部署二选一（M80 / V1098）

保留原 Milvus 配置；新增 `PLATFORM_KNOWLEDGE_VECTOR_STORE_MODE=pgvector`，复用现有 PostgreSQL。
只有选 `milvus` 才启动 `docker-compose.rag.yml` / `rag` profile 并配置 Milvus URI/token。
两种部署都使用 PostgreSQL 17/pgvector 镜像和 `public.vector` 扩展；生产应由数据库管理员预装扩展，
应用账号不需要新授予超级用户权限。`none` 仍是默认禁用，不会改变已经绑定的后端。

API 和全部 Worker 必须选择同一模式。V1098 为旧 generation 保守绑定 Milvus；新部署首次启用
后端时原子绑定。配置与绑定不一致会在启动期失败，而不是读取错误索引。**已有数据库不能仅改
.env 切换后端，不能删除绑定绕过检查。** 本版本不实现跨后端搬迁或双写；另行停写、备份、验证
重建与切换后才能实施。不能用旧 Milvus-only 二进制回滚 pgvector 数据库。

pgvector 新部署（先使用 `.env.pgvector.example`，与受保护的 release secret 配置组合）：

```sh
docker compose --env-file /absolute/protected/release.env --env-file /absolute/protected/pgvector.env \
  -f docker-compose.yml -f docker-compose.release.yml \
  --profile web --profile admin up -d --no-build
```

先发布已构建的 V1098 二进制并完成迁移，健康后再显式开启 intake/索引 Worker。
pgvector 不依赖 Milvus、不需要其凭据。`PLATFORM_KNOWLEDGE_PGVECTOR_QUERY_TIMEOUT_SECONDS` 为1–30秒，
默认5秒。精确原生 COSINE + scope B-tree 不建立全局 ANN；`hybrid_v2` 使用 CJK/Latin 编码与
PostgreSQL FTS/GIN/ts_rank_cd 后接原有 RRF，**不是 Milvus analyzer/BM25 的排序等价实现**。
最大16000维，超限在模型调用前拒绝；查询超时显示原有 partial/degraded 证据，不自动重复付费操作。

小服务器可在 API 进程设置 `PLATFORM_KNOWLEDGE_INDEX_WORKER_ENABLED=true` 启用单个索引调度，
不再启动额外 `knowledge-worker` JVM；该进程仍有其他任务，需限制沙箱/聊天并发并实际压测。
若使用独立 Worker，原 overlay 已支持二选一，不再强制 Milvus URI/token；务必传相同模式。
`SPRING_FLYWAY_ENABLED=false` 的 Worker 必须等 API 完成 V1098。未选后端却启用索引 Worker 会失败。

TXT/Markdown 不需要解析服务；PDF/DOCX 等二进制仍须原隔离 Tika，不能因 pgvector 就宣称所有
文档格式无需服务。密钥、正文、向量检查点、预算和引用/权限校验维持原协议。
备份时 pgvector 副本包括在业务数据库备份中；缓存缺失时原 repair 从检查点恢复原后端，
不再次请求 Embedding。以下 Milvus 操作只适用于 Milvus 部署，通用生命周期说明适用于两者。

## 边界

本手册对应 M79；代码完成不等于真实 Provider/Reranker 质量、高可用或生产网络验收。
当前仅本地专用 Tika/Milvus 和临时 PostgreSQL 用于确定性证明；不要自动读取 `.env`、`testapikey`、
业务凭据或生产库。以下生产/真实账号步骤需要操作者单独授权。不要 `down -v` 或清空现有 volume。

Java/PostgreSQL 拥有权限、正文、生效指针、任务/预算；Milvus 是可重建的敏感索引副本。
解析器只接收有界文档，不接收数据库、Provider 密钥或任意宿主机路径。重排使用自部署 TEI 的标准 API，
不是付费 Provider 的替代账本：本适配器不支持把商业按量服务伪装成免费自部署服务。

## 部署顺序

1. 备份数据库、受管理对象目录与单独保管的 Inference 加密密钥，确认能恢复。先保持 intake/worker 关闭。
2. 发布 API 二进制并一次性完成 V1098/110 migration；不要修改已执行迁移。Worker 设置 `SPRING_FLYWAY_ENABLED=false`。
3. 单独启动 Milvus `docker-compose.rag.yml`（固定 2.6.22）和解析 `docker-compose.parser.yml`（Tika 3.3.0）。
   两者是敏感内部依赖；测试模板只绑定 127.0.0.1，生产使用受限内网、TLS 和非默认凭据。
4. API 与 Worker 使用同一 Compose project / 同一 `platform-knowledge-index` POSIX volume 和相同 UID。
   跨主机部署必须提供可靠共享 POSIX 存储；本轮不声称本地 volume 可直接跨主机扩展。
5. Worker overlay `docker-compose.knowledge-worker.yml` 复用同一业务镜像，`PLATFORM_RUNTIME_ROLE=knowledge-worker`，
   不映射宿主端口、不挂 Docker socket、不挂 Project Workspace，只调度 Knowledge Index/Maintenance/Metrics。
   `/api/**`、`/internal/**` 均拒绝；只保留内部 health/prometheus。API 进程保持 `PLATFORM_KNOWLEDGE_INDEX_WORKER_ENABLED=false`。
6. 为 API 和 Worker 配置 Milvus/解析端点，再启用 intake 和 Worker；只重建/重启改变的服务。

同项目组合示意（自行指定受保护配置文件，不把真实值提交 Git）：

```sh
docker compose --env-file /absolute/protected/rag.env \
  -f docker-compose.yml -f docker-compose.rag.yml -f docker-compose.parser.yml \
  -f docker-compose.parser-platform.yml -f docker-compose.knowledge-worker.yml \
  --profile rag --profile knowledge-worker up -d --no-build knowledge-worker
```

先确保依赖已健康；这个命令不是授权自动启动用户生产栈。可用 `--scale knowledge-worker=2`，
不用重建同一镜像。每个 Worker 一次运行一个索引，PG fairness 行按最近领取消费轮换，
每租户最多一个 RUNNING 索引 lease；两 Worker 可以服务不同租户。失去 lease 后旧执行器不能发布。

## 配置（均为后端配置，不是用户表单）

| 配置 | 默认 / 意义 |
| --- | --- |
| `PLATFORM_KNOWLEDGE_VECTOR_STORE_MODE` | `none`；启用时二选一 `milvus` / `pgvector` |
| `PLATFORM_KNOWLEDGE_MILVUS_URI/TOKEN` | 私有实例 URI / secret，禁止日志输出 |
| `PLATFORM_KNOWLEDGE_PARSER_URI` | 受限解析网关，如 `http://rag-parser-gateway:8080` |
| `PLATFORM_KNOWLEDGE_PARSER_ALLOW_PRIVATE_HTTP` | 默认 false；只对明确内网测试开启 |
| `PLATFORM_KNOWLEDGE_INDEX_INTAKE_ENABLED` | 默认 false |
| `PLATFORM_KNOWLEDGE_INDEX_WORKER_ENABLED` | 默认 false；独立 Worker 才开启 |
| `PLATFORM_KNOWLEDGE_MAX_DOCUMENTS` | 10000 / Base |
| `PLATFORM_KNOWLEDGE_MAX_PENDING` | 64 / tenant；包括待调和状态，不以 UNKNOWN 释放容量 |
| `PLATFORM_KNOWLEDGE_MAX_SOURCE_BYTES` | 1 GiB / tenant，累计原始输入，重复修订保守计数，不代表磁盘硬配额 |
| `PLATFORM_KNOWLEDGE_LEGACY_MODE` | `compatibility`；完成显式迁移与 Agent 绑定调整后改 `disabled` |
| `PLATFORM_RERANKER_MODE` | `none` 或自部署 `tei` |
| `PLATFORM_RERANKER_URI/TOKEN` | 固定 TEI 根 URI / 可选内部认证 token |
| `PLATFORM_RERANKER_MODEL/REVISION` | 模型 ID / 不可变 40–64 位十六进制 revision |
| `PLATFORM_RERANKER_ALLOW_PRIVATE_HTTP` | 默认 false；仅私有固定服务测试 |

TEI 适配固定协议 1.9.0，先核对 `/info` 的 version/model_id/model_sha，再 POST `/rerank`；
最多 32 段、128k 字符，不自动 truncate，不返回远端正文，拒绝重复索引/非有限分数。
未配置不重排；超时/失效回退 RRF 并标 degraded。真实模型和 GPU/CPU 镜像部署需另外验收，
不要把 HTTP fixture 通过当作 TEI 引擎/语义效果通过。[官方接口](https://huggingface.github.io/text-embeddings-inference/)

## 删除、旧代次、容量

删除先清空 PG 生效指针，等 lease+60 秒后清理 Milvus/对象/Inference 缓存，两次确认后擦除元数据。
最小墓碑继续每小时清扫迟到写；已删除文档 ID 不重用。旧生效代次在被替代且完成超过 24 小时后，
仅回收其向量、Job 中间文件和 Chunk 正文；保留原文件修订与最小任务证据，**不删除当前文档和当前索引**。
删除整个文档时升级已有旧代次墓碑。原始修订按文档生命周期保留，管理员需规划实际磁盘容量。

在线查询每租户最多 8 个数据库 lease，进程崩溃后 10 分钟过期；查询流水线 45 秒软预算，
正在进行的单次有界请求不强杀。候选/模型空间上限及 partial/degraded 字段见设计契约。
取消/撤权不会盲目重发已付费但未知的模型调用。

## 存储备份与无付费恢复

1. 暂停新 intake、索引、维护与 repair，等已有 lease 结束；停止写入后取得 PostgreSQL 一致性备份。
2. 使用 `bash scripts/knowledge-object-snapshot.sh backup /absolute/object-root /absolute/new-archive.tgz`。
   根目录内必须存在 `knowledge-index/`，拒绝符号链接，不覆盖目标。另行加密/访问控制归档。
3. 隔离恢复 PG 和对象到**新实例/新目录**，用 `... restore /absolute/archive.tgz /absolute/new-root` 校验摘要并恢复；
   不覆盖现有数据。只从可信自建归档恢复；不要用本脚本接收互联网任意 tar。
4. 密钥恢复与 PG 权限校验完成后，重建 Milvus 空实例。对每个当前生效文档提交
   `POST /api/v1/knowledge/bases/{base}/indexed-documents/{doc}/repair`，body `generationId`，Header `Idempotency-Key`。
   MANAGE 才可申请；`GET /api/v1/knowledge/index-repairs/{id}` 查询。Worker 按原 Embedding 清单重放向量、验证搜索，
   **不调用 Embedding Provider**。当前代次变化则失败，不错误激活旧代次；未知向量写仅允许同一不可变 payload 重放。
5. 如原始向量对象缺失或校验失败，repair 不伪造成功；返回 `INDEX_REPAIR_NOT_CONFIRMED`。人工确认来源、预算和授权后
   重新导入生成新索引，不能自动购买模型请求。业务源/Provider 自身日志的备份不在 Milvus snapshot 中。

V1092 以前没有 inventory 的文件：恢复前盘点受管理根目录，将合法 `knowledge-index/<owner>/<sha256>` 引用登记到
inventory，保留 24 小时宽限，先审查清单再启用 GC。不得根据文件名猜测租户，不扫描任意用户目录。
`bash scripts/knowledge-object-inventory.sh /absolute/root 1000 [after-reference]` 输出经 SHA 校验的 reference/owner TSV，
不读写数据库；用最后一条 reference 分页。经授权导入临时表再 `INSERT ... ON CONFLICT DO NOTHING` 到 inventory，
保持 last_seen_at 为当前 DB 时间。这里的 owner 是对象前缀（Job/Doc ID），不是用户权限。

## 旧系统切换与回滚

`GET /api/v1/knowledge/bases/{base}/legacy-migration` 仅当前 MANAGE/原 owner 可检查旧私有文档。
旧向量一律标 `LEGACY_VECTOR_PROVENANCE_UNKNOWN`，不迁移未知模型向量。
inline 内容可显式 `POST .../legacy-migration/{document}`，body 为 `targetBaseId/spaceId` + `Idempotency-Key`；
目标必须是同 owner 的 PERSONAL Base，复制而不搬迁，保留新文档/任务映射。缺失原始文件则要求重新上传或 URL snapshot。
等待新 Job COMPLETED，调整 Agent 的 `knowledgeCollectionIds`（旧 `knowledgeBaseIds` 是 document IDs）。
然后关闭 legacy 模式；旧 process/retrieve/addChunk 返回迁移要求，不再使用全局旧 Embedding。
旧 URL 解析仍可保留已抓取 snapshot，但旧 URL 自动 embedding 也会拒绝；新知识库通过已授权 snapshot 导入使用新 Inference 路径。
在保留期内可重新打开 compatibility 并恢复旧 Agent 绑定，不能回退到不识别组织文档的新旧混合二进制。
未授权时不删除旧文档，不直接批量覆盖任何组织归属。

## 观测与告警

现有 Micrometer/Prometheus 复用：`spaceagent.rag.index.jobs` 按有限 state 标签统计，
oldestPendingSeconds/deletionsPending/sourceBytes/legacySizeUnknown；retrieval.duration 按 outcome，
index.duration 按终态，rerank.duration 按 outcome。无用户 ID、正文、query、密钥高基数标签。
费用从 Inference Embedding Call/预算账本读取，usage 缺失保持 UNKNOWN，不能填 0。
Runtime checkpoint `knowledge-retrieved` 保存计数、partial/degraded/warnings/阶段耗时；正文仍按现有上下文权限管理。
应告警：积压持续增长、RECONCILIATION_REQUIRED、删除长期 PENDING、DB/Milvus 不健康、查询 degraded 比率、
共享 volume 空间不足。生产告警接收端、容量和 P95 目标需实机验收，不能由本地单元测试宣称。
