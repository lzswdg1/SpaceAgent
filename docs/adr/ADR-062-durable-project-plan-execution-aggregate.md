# ADR-062: Durable Project Plan Execution Aggregate

- Status: Accepted
- Date: 2026-09-07
- Milestone: M57-PR1

## Context

`M56-PR1` 实现了可恢复的单步派发：`ProjectPlanExecutionApplicationApi.execute` 触发后，`ProjectCodingCoordinator` 计算当前可执行 Step，并原子化启动第一步、完成后串行推进；但是当前实现并未持久化独立的“计划级执行实例”。

当前持久化仅有 Project-owned `TaskPlan`、`PlanStep` 和 `ProjectCodingJob`（含 directory/conversation/source/agent/reviewer/baseRef），运行时无法持久化保存每次执行会话的状态快照、输入幂等冲突信息、并行扩展投影（active jobs）等；跨重启或并发副本场景仍依赖推断。

后续 `M57-PR1-U02..U08` 需要一个 Runtime-owned 的执行聚合来承接这些边界约束。

## Decision

- 引入 Runtime-owned 的 `ProjectPlanExecution` 聚合，作为 `TaskPlan` 执行会话的权威状态。
- `ProjectPlanExecution` 覆盖状态（至少 `READY/RUNNING/COMPLETED/FAILED/BLOCKED`）并持久化：
  - tenant/owner/project/rootTask/taskPlan
  - directory/conversation/source
  - 默认 agent/reviewer/baseRef
  - inputHash/revision/状态时间戳
- 一次 `TaskPlan` 同时只有一个 `ProjectPlanExecution`（前提下可扩展至按 plan 版本演进）。
- `ProjectCodingJob` 新增可空的 `executionId` 以兼容历史数据；新增执行路径中的 Job 必须绑定 `executionId`，历史 `ProjectCodingJob` 保持兼容可读。
- `ProjectPlanExecution` 接口提供 `start/get/list`，现有 `execute/dispatch` 逐步迁移为基于 execution 的新实现。
- `V1052` 采用前向兼容策略：新增 execution 表与约束/FK，不改造已上线列字段；旧路径仍可回放读取，确保现有审计数据可被安全迁移。
- 所有 `dispatchNext`（首派发与 successor）使用 execution 层级 lock/CAS，确保多副本下同一 plan 的单-flight 控制与稳定重放。

## U06 Implementation Note

- Integration 通过 Runtime Application API 创建并 `begin` execution，不直接访问 Runtime repository。
- 自动创建的 CodingJob 持久化 `executionId`；历史和手动 Job 保持 nullable 兼容。
- 首派发、完成 successor 和失败封闭先取得 execution 锁，再在同一 Coordinator 事务中修改
  Project PlanStep、CodingJob 与后续 Job；HTTP 绑定留到 U07。

## U07 Implementation Note

- Runtime execution 通过 owner-scoped HTTP start/get/list 暴露；认证上下文提供 tenant/owner，
  execution ID 由服务端生成，重复创建使用 Idempotency-Key。
- Organization/User cleanup 先 quiesce execution 与 CodingJob，再删除 Handoff/Job，最后删除
  execution，避免 `platform_project_coding_jobs.execution_id` 外键悬挂。

## Consequences

- 后续实现按 Runtime 边界推进：PlanExecution 完全归 Runtime 所有，Project 仅继续保有 TaskPlan/PlanStep/Task 生命周期。
- `ProjectCodingJob` 可从已有字段平滑迁移到 execution 投影；短期仍保留推断兼容，逐步淘汰。
- 未来并行扩展（`activeJobs` 投影）可直接沿 execution 粒度演进，而无需额外重写 legacy job 索引。
- 此 ADR 为 `M57-PR1-U02-U08` 的实现目标提供统一入口，并要求在对应单元中同步更新：执行域、Flyway、repository、application、coordinator 与 HTTP/cleanup。

## M57-PR2-U05R Corrective Clarification

- Public execution creation must enter the Integration coordinator and validate the Project,
  Directory, Conversation, Source, AgentVersion and Reviewer binding before Runtime persistence.
- Concurrent creation uses exception-free `INSERT ... ON CONFLICT DO NOTHING`; catching a unique
  violation and querying in the same PostgreSQL transaction is not a valid convergence strategy.
- Active CodingJob projection is keyed by `executionId`. Historical/manual null-execution Jobs
  remain readable but never enter a durable execution's pause/resume/cancel scope.
