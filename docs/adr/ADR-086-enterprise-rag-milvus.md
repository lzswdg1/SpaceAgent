# ADR-086: 企业级 RAG 使用独立 Milvus 检索索引

Date: 2026-09-14
Status: ACCEPTED / DETERMINISTIC IMPLEMENTATION COMPLETE — 生产 HA/容量与语义质量验收另列
Deployment choice update: ADR-089 retains Milvus and adds a mutually exclusive pgvector deployment backend;
the original single-backend choice below is superseded, not its authorization/generation/recovery boundaries.
Execution plan: [M79 Enterprise RAG](../../.agent/ENTERPRISE-RAG-PLAN.md)
Confirmed design: [企业级 RAG 设计](../design/enterprise-rag/设计.md)

## Context

用户要求可解耦、可维护、可扩展的企业级向量 RAG，并要求在开发前展示架构和逐步计划。
当前 Knowledge 主要按 owner 管理文档，固定字符切分，PostgreSQL TEXT 向量计算 cosine。
部署虽启用了 pgvector 扩展，Knowledge 尚未利用其索引。新增独立向量数据平面符合本次目标。

## Decision

1. 选择 Milvus，Java Knowledge 保留文档、权限、原始 Chunk、索引任务及生效代次权威。
   PostgreSQL 是业务真相；Milvus 是可重建检索副本。原文件和恢复产物保存在受管理对象存储。
2. Indexing/Retrieval 首先在 Knowledge owner 内分组件，使用 DocumentParser、DocumentChunker、
   VectorIndexGateway 等中立接口；SDK 只进入适配层。Java Index Worker 支持独立启动扩容。
3. Tika 承担文件解析，LangChain4j 承担成熟切分组件；Milvus dense+BM25 及官方 SDK 承担召回。
   Embedding/Reranker 模型配置、密钥和用量由 Inference 公开 API 管理，Knowledge 固定模型空间。
4. 新模型、文档修订和切分配置变化建立新 IndexGeneration。PG 任务/Outbox 与清单管理至少一次
   写入，Milvus 同 PK 相同 payload 幂等，验证搜索可见性后 PG CAS 激活，不使用跨库分布式事务。
5. 个人知识默认所有者私有，组织知识按当前 membership 和显式 grant 鉴权；Agent 绑定不授予权限。
   旧 owner-only 数据不猜测当前组织；新增作用域时保留可证明的原始边界。
6. 查询具备授权过滤和 PG 回源最终鉴权；Milvus 中的旧向量、旧 ACL 或待删除副本不能作为返回
   正文的授权依据。删除墓碑持续到迟到写和副本清理可收敛。
7. Milvus BM25 所需文本副本按敏感数据保护，不能因“只是索引”降低访问和删除要求。
8. 每个模型空间固定模型修订/维度/距离/预处理；相同维度不构成兼容证据。检索不混用模型空间。
9. 首期使用独立 Milvus Standalone profile 及匹配稳定版本的必要存储/元数据组件；Distributed/
   HA 属于后续部署验收。Redis/Kafka 不回归为平台业务权威。
10. 后台 Embedding 不是 Agent Run。Inference 保存独立调用账本，并复用既有月度预算；预算记录严格
    二选一绑定 Run 或 Embedding Call，不创建虚假 Run、不让 Knowledge 持有 Provider 凭据。
    已完成调用以加密响应缓存恢复，Knowledge 保存内容寻址中间向量；不确定费用保留待核算状态。
11. 新组织源文档由组织 KnowledgeBase 持有，不绑定创建者的个人清理生命周期。其用户 owner 字段为空，
    通过数据库延迟约束要求显式组织 scope；创建者仍保留在操作记录中。历史个人文档不回填或猜测组织。

## Alternatives and consequences

pgvector 能用同库事务简化一致性，但当前选择优先满足向量服务独立扩容和独立运维需求。
不同时建设两种向量后端。保留一个窄接口即可支持未来替换，不提前实现无用户需求的适配器。

独立 Milvus 增加副本同步、删除确认、可见性验证和备份恢复成本。本计划明确承担这些工作；
只完成 Collection CRUD 或 Mock 搜索不能宣称完成企业级 RAG。

## Rollout and verification

先增量建立新知识库范围与索引结构，保留旧 API 的明确兼容行为。旧数据仅迁移可验证的模型空间；
未知来源保留为待重建。待新索引完整可见才切换，过渡回退期不破坏旧数据。
工程确定性证明必须覆盖真实 PG/Milvus 的权限、批次可见性、崩溃和删除恢复；语义质量与生产 HA
分别取得真实环境证据后再宣布完成。

架构图、具体契约和状态机统一维护在设计文件；逐步队列、验证证据和源码入口维护在实施计划。
