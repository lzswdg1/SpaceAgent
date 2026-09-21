# Milvus / pgvector 部署二选一验收

日期2026-09-15，M80-PR1；源码 V1098 / 110 migrations。私有源码标识不随公开快照分发。Milvus 保留，pgvector
作为另一种 Knowledge 检索后端。同一部署的API/全部Worker只能选同一种；不是同时双写或自动切换。
使用方法：[Knowledge RAG运行手册](KNOWLEDGE-RAG-RUNBOOK.md)、`.env.rag.example`、`.env.pgvector.example`。

## 已证明

- pgvector 使用真实 `public.vector` 列、原生精确 COSINE、scope B-tree、CJK/Latin关键词FTS/GIN。
  查询有界，PK隔离space/schema/scope/base/generation/chunk。不是Java读取TEXT向量后遍历计算。
- 真实SQL验证跨scope/base/generation/model空间/存储schema隔离、重复同payload幂等、不同payload
  并发一个成功一个拒绝、整批冲突回滚、文本hash/维度/topK限制、参数化关键词查询和迟到写清理。
- 完整生产Spring/JWT/API：选择pgvector后未实例化Milvus客户端，入库、索引发布、引用正文检索成功，
  外部用户404且不产生额外Embedding。模型完全mock，数据库/索引/FTS不是mock。
- 索引恢复从原始向量检查点重放，未第二次调用Embedding；文档删除清空生效指针并清理原后端副本。
  超过16000维在该模型请求前拒绝，不盲目购买模型请求。
- V1097升级到V1098保留旧generation并绑定Milvus；fresh首次选择持久化，none不重置，冲突选择失败。
- 原Milvus SDK/中文英文BM25/实际本地索引发布/修复/删除，以及真实Tika PDF/DOCX仍通过。
- 原Worker overlay不再强制Milvus凭据，API与Worker使用同一选择。pgvector预检不套用Milvus8GB规则；
  没有把通过配置检查当作2核4GB容量保证。

## 验证结果

全量命令：`node scripts/run-local-dependency-acceptance.mjs --full`。使用缓存的随机独立Milvus
容器、已有本地解析网关、Testcontainers隔离PG，未操作现有业务库或付费/真实GitHub。

第一次全量运行仅Artifact测试类在PG初始化连接时遇到SSL握手EOF，未执行其断言；其他集合完成，
无断言失败。未修改产品或该夹具，单独复跑 `ArtifactObjectRepositoryPostgresTest` **5/5通过**。
按全量启动时间过滤新Surefire报告、以复跑替换该类失败初始化报告，证据并集：
**Shared25 + Platform860 + Admin17 = 902通过，零失败/错误/跳过**。这是全量加环境失败项复跑的
合并证据，不冒充单次全量命令exit0；未把陈旧target报告合计成新证据。

打包：`./mvnw -o -q -DskipTests package` PASS。架构、Milvus/pgvector两种Compose组合、release
pgvector配置、5项无daemon预检夹具、Node/shell语法、`git diff --check` PASS。前端/Worker生产源码
未改，未重复其原有效回归或构建，没有执行镜像构建/业务服务重启。

小型串行查询夹具256行/128维/20查询对：pgvector P50 18ms/P95 20ms；同轮Milvus P50 4ms/P95 5ms。
包含各适配器的元数据检查等路径，不是严格算法公平benchmark、并发压测或生产SLO。

本机日志：

- 全量、环境复跑与打包原始日志是本机临时证据，不随仓库分发；本页保留脱敏结果摘要。

## 限制

此版本是部署二选一，不提供现有Milvus↔pgvector自动迁移；绑定不允许环境变量隐式改写。
修改旧数据库的后端需要单独停写、备份、审查与验证切换方案。pgvector关键词排序不等于Milvus BM25。
真实语义质量、大语料/高并发、2核4GB混合负载、生产网络/HA/Linux-runsc仍待验收。
二进制文档仍需要隔离解析器，代码编译仍受沙箱CPU/内存约束。选择pgvector不解决这些资源问题。
独立Milvus容器已移除，原服务/volume保留；未更新运行栈、未推送，原`output/`未跟踪文件不纳入提交。
