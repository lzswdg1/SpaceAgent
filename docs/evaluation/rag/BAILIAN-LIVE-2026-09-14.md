# 百炼知识库真实验收（2026-09-14）

结论：**PASS — 默认 1024 维、小文本的端到端 smoke**，不代表所有配置、批量规模或生产环境均已验收。
本报告对应当时的受控本地验收源码；私有仓库提交标识不随公开快照分发。用户明确授权使用
Git 忽略的本地凭据目录，本次没有执行 DeepSeek、GitHub 或 TEI。

## 已实际执行

- 通过项目 real-e2e 的凭据检查与临时 secret materializer；原文件保持 Git-ignored/0600。
- 使用新建 PostgreSQL、隔离 Workspace、当前打包 Java `TRUSTED_BETA` 进程与独立随机 Milvus Collection。
  没有更新当前用户业务库或重启业务服务；没有把真实 Key 放入命令参数、日志或 Git。
- 百炼 Provider 连接成功，ModelPool 激活；`text-embedding-v4` 默认1024维真实向量化完成，索引发布成功。
- 新 `/knowledge/collections/retrieve` 路径返回1条命中，degraded=false，引用 document ID 和正文 SHA256 校验通过。
- 同一个逻辑检索请求重放没有增加 Embedding 调用账本条目。
- `qwen-plus` 回答了只存在于新上传文档中的随机口令；用户问题和系统提示词不包含口令，证明答案使用了知识库证据。
- 另外一条 RAG SSE 请求收到 done；只保存流的 hash，不保存回答内容。
- 隔离 Java 重启后生效代次保持；删除测试文档后检索为空，没有新增模型请求。
- 原始模型内容/日志、临时 secret env、隔离 Java/PostgreSQL/匿名卷和本次 Milvus Collection 已清理。

## 脱敏计量

| 指标 | 观察结果 |
| --- | --- |
| Embedding | 4次，4次成功，UNKNOWN=0 |
| Embedding 输入 tokens | 78 |
| 普通 Chat 输入/输出 tokens | 214 / 21 |
| SSE | done 正常；未单独导出 token 计数 |
| 自动付费重试 | 0 |
| 明文密钥泄漏检查 | PASS |
| 隔离环境清理 | PASS |

普通答案 SHA256：`afef2550c4f07c9c17bf97443b8de910ebf6307c3ed6671bc3416af13ec5f31d`。
SSE SHA256：`a490ecd719805f4152951467c04adaaf2612b3130dfd26cfa99114279b93fcb7`。
实际金额没有做 Provider 账单对账，不用估算价格伪装实际扣费。

## 代码检查发现的两个未修复兼容缺口

这两项不是本次已复现的失败场景；本次特意使用默认1024维和单 Chunk，没有再次付费探测边界。

1. `OpenAiCompatibleEmbeddingBatchExecutor` 收到了 dimensions，却没有将其加入外部请求 body。
   因而自定义非默认维度（例如512）可能收到默认1024维并被本地校验拒绝。需要模型能力感知的参数透传和验收。
2. `KnowledgeEmbeddingStage.split` 通用批次最多64条，而百炼 text-embedding-v3/v4 的官方上限是10条。
   大文档/较短 Chunk 可能形成超过10条的批次，需要按 Provider/模型约束分批，保留幂等账本和 UNKNOWN 语义。

依据：[百炼同步 Embedding 官方接口：维度默认值及单批条数](https://www.alibabacloud.com/help/en/model-studio/text-embedding-synchronous-api)。
本次用户要求测试，未擅自修改业务实现。不能把这个 smoke PASS 用来覆盖以上缺口。

## 尚未覆盖

真实 TEI 重排、多文档 Recall/MRR/nDCG、大批量/自定义维度、生产 TLS/HA/容量、跨机器共享存储和告警投递。
M78-U04 资源测量也未在本次扩展。后续付费运行仍需明确授权。

执行器：`scripts/run-bailian-rag-e2e.py`。它通过项目 secret materializer 读取密钥；不调用已过时的旧 Knowledge
脚本，也不使用旧 Compose 启停整栈逻辑。原始脱敏 JSON 位于本次 `.run/real-e2e/.../evidence.json`，只含上述计数、hash 和断言。
