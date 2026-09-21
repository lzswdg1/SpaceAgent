# RAG 可复现评估

`gold.json` 固定 4 份有仓库出处的工程文本与 8 道中英文问题；不代表真实企业知识分布。
真实语义评估须经授权使用实际 Embedding/Reranker，不能以 stub 向量的分数宣布效果通过。

将这四份文本导入独立测试知识库，为 dense/hybrid/rerank 三组保存 predictions.jsonl：

```json
{"id":"zh-permission","mode":"hybrid","hits":[{"documentId":"permissions","chunkId":"returned-chunk-id","contentHash":"64-character-sha256"}]}
```

`documentId` 先按导入回执映射回语料 ID。使用测试身份通过后端获得结果；不要把 Provider 密钥写进预测文件。
再运行纯离线计算：

```sh
node scripts/evaluate-rag.mjs docs/evaluation/rag/gold.json /absolute/predictions.jsonl 5
node --test scripts/evaluate-rag.test.mjs
```

输出 Recall@K、MRR、二值 nDCG@K、引用结构有效率，拒绝重复 query/mode 与未知问题。
`complete=false` 表示该组未覆盖全部问题。引用结构有效不等于权限/真实 hash 有效：后者由后端 PG
二次鉴权和内容校验测试证明，实际生产仍需核验。输出始终保留 `EXTERNAL_ACCEPTANCE_PENDING`，不自动改发布状态。

## 本地性能 smoke 的预先声明

范围仅 Milvus adapter，不是产品整体 P95 或容量承诺。专用 Milvus 2.6.22，JDK 21 / SDK 2.6.22，
ARM64 Docker Desktop 8 CPU / 8 GiB（共享本机负载），256 条合成 Chunk，128 维非语义向量，
2 tenant（50% scope 选择率），4×64 upsert，3 次预热、20 次串行 dense+BM25 查询，topK=5。
记录每次双通道耗时 P50/P95、吞吐；预先 smoke 门槛 P95 < 5000ms，任何一次超出 SDK 超时为失败。
此极小数据集只检查基本退化，不外推千万向量、并发 SLO 或真实召回质量。
测试入口 `MilvusVectorIndexIntegrationTest.boundedHybridPerformanceSmoke`；打印 `RAG_PERF_FIXTURE` 摘要。

2026-09-14 实际 smoke 结果：P50=4ms、P95=6ms，按毫秒级采样约241查询对/秒；Docker报告8 CPU，
可用内存8,321,994,752 bytes。全量 Maven 中执行并清理随机测试 Collection，未调用模型 Provider。
生产容量、HA、真实语义指标与成本目标仍需要固定真实环境和业务语料后单独验收。
