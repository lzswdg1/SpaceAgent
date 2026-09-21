# SpaceAgent 外部验收待办

> Updated: 2026-09-14
> State: EXTERNAL_ACCEPTANCE_PENDING
> Mainline-Blocking: NO_FOR_DETERMINISTIC_REMEDIATION
> Release-Blocking: YES

本文件只保存必须依赖真实环境、账户或凭据的验收。迁入此处不表示完成、取消或降低标准；主线可继续
修复并运行确定性验证，但整个后端和公开上线验收不得在本清单关闭前宣称 COMPLETE。

## M65-PR1-U01/U02 Linux + gVisor runsc

M79 企业 RAG 的真实模型/TEI/生产容量验收见本文末尾独立条目；不改变已有 M65 等待条件。

- U01: `IMPLEMENTED_PENDING_TEST`; fixed manifest and guarded scripts exist, static proof passed.
- U02: `EXTERNAL_ACCEPTANCE_PENDING`, never COMPLETE.
- Evidence: `scripts/verify-runsc-environment.sh --probe` passed static checks then failed with
  `Linux host is required`. Current host is Darwin arm64. Docker Desktop exposes Linux Engine 29.2.1/API 1.53,
  only `runc`/`io.containerd.runc.v2`, `live-restore=false`, and no host `runsc`. No acceptance container ran.
- Recovery: resume this exact repository on Linux 6.1+ matching `ops/acceptance/runsc-environment.json`, register
  gVisor `runsc release-20260817.0`/OCI 1.2.1, enable live-restore, set immutable
  `SANDBOX_EXECUTION_IMAGE=sha256:...`, keep `PLATFORM_PUBLIC_UNTRUSTED_CODE_ENABLED=false`, rerun
  `scripts/verify-runsc-environment.sh --probe`, then execute all seven isolation cases only after exact PASS.
- Release limit: public-untrusted execution remains unsupported and startup-disabled.

## M65-PR1-U03 Admin private ingress

- State: `EXTERNAL_ACCEPTANCE_PENDING`.
- Required: real HTTPS/mTLS, VPN/IP allowlist, certificate rotation and service-JWT positive/negative evidence.
- Release limit: only already-proven local/private Admin boundaries may be claimed; internet-facing Admin is unsupported.

## M65-PR1-U04/U05 telemetry and alert delivery

- State: `EXTERNAL_ACCEPTANCE_PENDING`.
- Required: Java/TypeScript/Python OTLP through real Collector/Tempo, redaction and TTFC correlation, Prometheus to
  Alertmanager to a real receiver, receiver failure isolation, restart/chaos and no business-authority feedback.
- Release limit: local deterministic telemetry behavior may be claimed; production receiver delivery may not.

## Credential-bound final acceptance

- State: `EXTERNAL_ACCEPTANCE_PENDING`.
- Required when separately authorized: real Provider/Embedding/SearXNG, GitHub MCP/generic OAuth, live S3-compatible
  endpoint and any other credential-bearing integration listed by the release support matrix.
- Restrictions: isolated temporary environment only, no production operation, paid calls require explicit permission,
  no secret in Git/log/trace, cleanup evidence required, and no automatic push.

## M79 Milvus RAG 真实语义质量与生产运维

- State: `EXTERNAL_ACCEPTANCE_PENDING`；不阻断本地确定性代码收口，阻断生产效果/容量声明。
- 2026-09-14 用户授权的百炼默认1024维小文本 smoke 已 PASS：真实 Embedding4/4、检索引用/hash、文档口令问答、
  SSE、重启保持和删除隐藏；临时密钥/数据环境已清理。见 [脱敏报告](../docs/evaluation/rag/BAILIAN-LIVE-2026-09-14.md)。
  这不是所有场景验收；静态检查发现 dimensions 未透传、批次64与百炼上限10不匹配，修复与边界复测仍待安排。
- 已验证范围：专用本地 Milvus 2.6.22、Tika 3.3.0、临时 PG；TEI 1.9.0 只验证 HTTP fixture 契约，
  未部署真实 Reranker 模型；合成向量只证明工程管线、隔离与恢复，不证明语义召回。
- 待后续授权：扩大真实 Embedding/批次/非默认维度验收与固定模型 revision、实际自部署 TEI/模型权重、组织共享 Provider 用量，
  固定业务语料的 dense/hybrid/rerank Recall@K/MRR/nDCG 与真实引用/ACL、预算和失败恢复。
- 生产：Milvus TLS/非默认认证、分布式 HA、容量/P95/并发、共享 POSIX 存储、离线 PG+对象备份恢复、
  监控告警接收端与删除队列实机验证。当前 Standalone/小型数据测试不代表这些完成。
- 恢复入口：[运行手册](../docs/operations/KNOWLEDGE-RAG-RUNBOOK.md)、[评估语料/工具](../docs/evaluation/rag/README.md)。
  未获授权不读取真实凭据、不执行付费模型、不迁移或删除用户数据、不修改前端和推送。
