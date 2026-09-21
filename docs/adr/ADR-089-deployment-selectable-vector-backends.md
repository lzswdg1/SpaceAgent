# ADR-089: 部署二选一的 Milvus / pgvector 检索后端

Date: 2026-09-15
Status: ACCEPTED — implementation/verification tracked in `.agent/VECTOR-BACKEND-PLAN.md`

## Decision

用户明确要求保留 Milvus，并新增 pgvector，部署服务器时二选一。本决定扩展 ADR-086
“只建设一个 Milvus 后端”的选择，保留其 Knowledge 权限/正文/代次/发布/删除/恢复及
Inference 预算与缓存权威。pgvector 是现有业务 PostgreSQL 内的敏感可重建索引，不是另一套
业务数据库，不改变任何模块的数据所有权。管理员数据库和前端不变。

`PLATFORM_KNOWLEDGE_VECTOR_STORE_MODE=milvus|pgvector` 是部署选择；`none` 保留禁用状态。
同一个数据库的 API 与所有索引 Worker 只能选择同一个后端，没有运行时自动降级、双写或混读。

## Persistence and compatibility

V1098 增加索引副本表和单行后端选择记录。已有 generation 保守标记 Milvus，避免把既有
向量重新解释为 pgvector；新部署第一次启用后端时，原子绑定选择。绑定持久化，`none` 不擦除。
不同选择在启动期被拒绝，不接受付费查询/构建后才发现读错数据。即使首次启用尚未产生索引，
绑定也不由失败重试/环境变量自动改变；改选需要明确审查数据库状态的运维流程。

不实现跨后端迁移。已有库不能仅改 .env 切换；必须另行规划停写、完整备份、索引重建/验证与
切换及回退。不得删除绑定绕过此流程。旧 Milvus-only 二进制不能作为 pgvector 数据库的回滚版本。

两种部署都使用现有 PostgreSQL 17/pgvector 镜像与 `public.vector` 扩展；生产管理员须预装扩展，
应用迁移账号不因本功能获得超级用户权限。Admin DB 不执行该迁移。

## Retrieval semantics

pgvector 首期采用真实 `vector` 列与原生 COSINE 精确检索。SQL 严格限制 space/schema、scope、
base、generation 后评分排序，使用 scope B-tree；不建立全局 HNSW，不承担共享 ANN 索引的
后过滤召回与建索引内存成本。查询有 1–30 秒可配置 statement timeout，默认5秒。

`hybrid_v2` 提供本地 CJK 单字符/双字编码与 Latin word 编码、PostgreSQL FTS/GIN/ts_rank_cd
关键词召回，交给原有 RRF/可选重排。这不是 Milvus 中文 analyzer/BM25 的分词和排序等价实现，
语义质量另验；Milvus 的 SDK/HNSW/BM25 路径保持不变。模型空间最高16000维，超限在模型请求前拒绝。

索引 PK 覆盖 space/schema/scope/base/generation/chunk，包含权威正文 hash 与固定版本 payload hash。
同 payload 并发重试幂等，不同 payload 在数据库冲突锁下拒绝，整批事务回滚。文档删除仍由原有
生效指针/墓碑/迟到写清理协议负责；不能把同库索引写入成功当成已授权或已发布。

## Consequences and evidence limits

轻量部署不运行 Milvus 进程，也不需要 Milvus URI/token。文本/Markdown 无需 Tika；二进制仍须
隔离解析器。API可显式运行串行索引调度以节省独立 Worker JVM，不能同时再启用额外索引进程。
代码沙箱/付费模型、源文件容量、备份与代码执行边界不由选择 pgvector 自动解决。

接受条件：真实 PostgreSQL 的 fresh/upgrade、作用域与模型/代次隔离、不可变并发写、索引发布、
无付费恢复/删除，以及公共API接线和 Milvus 回归。容量、2CPU/4GB混合负载、语义质量、生产HA
与真实 Provider 未据此承诺。
