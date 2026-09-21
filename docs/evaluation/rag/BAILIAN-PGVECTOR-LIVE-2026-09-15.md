# 百炼＋pgvector真实DOCX验收

日期2026-09-15，当时的打包 Jar 包含 V1098 和 PgVectorIndexGateway；私有仓库提交标识不随
公开快照分发。用户明确授权使用 Git 忽略的本地百炼 Key。结论：**核心解析→入库→检索→模型使用证据链PASS**；
两次隔离尝试的结果分别记录，不能把首轮脚本失败报告改成全通过。

## 实际执行与证据

- 安全凭据检查、Git-ignore/0600和专用materializer通过；只调用百炼，不调用DeepSeek/GitHub/TEI。
- 当前Java `TRUSTED_BETA` 进程、随机临时PostgreSQL/Workspace/对象目录；`pgvector`选择持久化。
  未读取或变更现有业务数据，没有更新业务镜像/服务。Milvus服务未参与本轮。
- 上传新生成的小DOCX，使用真实隔离Tika3.3.0解析，保存 `TIKA_3.3.0_XHTML_V1` 元数据证据。
- `text-embedding-v4`真实1024维Embedding，索引Job COMPLETED，原生vector列1行、1024维；
  不是SQL TEXT模拟。接口返回1条命中、degraded=false，document ID及正文SHA256与引用一致。
- 文档包含随机口令，问题/系统提示不提供口令。真实 `qwen-plus` 普通回答匹配口令，证明使用了文档。
- 独立新Conversation的真实SSE同样回答另一个新文档中的随机口令，无前次回答历史；有delta/done，
  ragUsed=true、引用字符串对应文档，输入/输出tokens为真实Provider用量。
- 同逻辑检索重放没有新增Embedding账本；重启隔离Java后生效代次保持，删除文档后查询为空，
  没有因删除再发模型请求。删除的是生成的测试数据，不是用户业务文档。

## 两轮结果与费用边界

首轮 `.run/real-e2e/bailian-rag-7iur43na/evidence.json`：解析、真实pgvector入库、检索引用和普通
模型随机口令回答均PASS，随后测试脚本把Chat `knowledgeCitations`字符串当成对象 `.get()`，
发生AttributeError。该份报告保留FAIL，未发生不明模型结果重试；环境/临时Key已清理。
首轮在后续用量导出前退出，没有保存普通Chat token计数或回答hash，不能伪造其用量数据。

核对当前Java契约（`文档ID#切片ID`字符串）后只修正测试执行器，不改产品。唯一有界补测使用
`--sse-only`跳过普通回答，避免整条推理重复；新的隔离实例/新随机口令，补齐SSE/重启/删除。
第二轮 `.run/real-e2e/bailian-rag-3f9xx41k/evidence.json`：PASS。

第二轮明确测得：

| 指标 | 结果 |
| --- | --- |
| Embedding调用 | 3次/3成功，UNKNOWN=0，1024维 |
| Embedding输入tokens | 73 |
| SSE推理输入/输出tokens | 207 / 14 |
| 原生向量行/检索命中 | 1 / 1 |
| 明文泄漏检查/隔离清理 | 两轮均PASS |
| 自动重复不明付费请求 | 0 |

共执行一次普通回答和一次独立SSE回答；首轮完整计量未导出，因此不报两轮总tokens或实际金额。
平台调用中实际发生百炼用量，未与供应商账单对账。第二轮SSE hash：
`20644b4a8eb43fdf14a5eedbca3c3ec067e2f0afcfc7e9c0661deb6d480c4ac6`。
只保留脱敏检查、数量及hash，不保存模型正文/随机口令/密钥。两轮临时密钥、平台日志、测试库
及其匿名卷、Java进程、生成的文档/向量文件均清理；原Key文件仍0600/Git-ignored。

## 执行器与离线验证

`scripts/run-bailian-rag-e2e.py --backend pgvector --document-type docx`，有界补测另加 `--sse-only`。
该执行器沿用当前Knowledge API；Skill中的旧通用 `run_model_e2e.sh`使用历史文档接口，不用于
本轮pgvector验证。秘钥仍只通过专用materializer读取，不放入CLI参数或输出。
新增离线DOCX/SSE/Chat引用契约测试4/4；CLI/语法和diff检查通过，不为测试脚本改动再跑全Maven。

## 未覆盖与非阻断发现

这只是单文档、默认1024维的真实端到端smoke，不是大规模召回质量、复杂版式/PDF/OCR、非默认
维度/大批量Embedding、跨租户实机容量或2核4GB/生产HA验收。当前用户运行栈没有部署pgvector版。

只读代码检查发现 `KnowledgeCollectionRetrievalApi.Result.legacyView()`仍把固定 `MILVUS_RRF`
填入兼容 `KnowledgeRetrievalMatchView.embeddingModel`，不是真实Embedding模型名，pgvector下
也不准确。这不决定检索后端（本轮原生vector类型/模式/命中已验证），但兼容元数据应后续修正。
本轮用户要求测试，未改业务实现；不要把这份PASS解释为所有元数据或所有功能无问题。
