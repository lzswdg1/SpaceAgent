# SpaceAgent JVM 性能基线与诊断

> 历史证据：以下五服务资源值来自已删除代码；只能通过 Git tag
> 历史数据来自未随本公开快照分发的私有归档，不能作为 rollback 或当前平台容量结论。

## 目标与边界

SpaceAgent 的 JVM 配置遵循“先建立资源边界和可观测性，再基于数据调参”的原则。
当前配置是一套适合本地演示和中小并发验证的基线，不代表在所有机器和流量模型下都是最优值。
项目不会在没有压测数据的情况下声称吞吐量提升或 GC 停顿降低。

## 已落地能力

| 能力 | 实现 |
|---|---|
| 容器内存感知 | 五个 Java 服务设置独立 `mem_limit` / `cpus`，JVM 使用 `InitialRAMPercentage=25`、`MaxRAMPercentage=70` |
| GC 策略 | Java 21 + G1 GC，目标停顿时间 `MaxGCPauseMillis=200`，启用并行引用处理、字符串去重和异步 GC 日志 |
| 阻塞 I/O 并发 | 五服务默认启用 Spring Boot 虚拟线程并设置 `spring.main.keep-alive=true` |
| Pinning 防护 | 渠道 Token 刷新使用 `ReentrantLock` 双重检查；SSE 不在外层 JVM monitor 内执行网络写入 |
| HTTP 入口 | 为 Tomcat 设置按服务连接上限、accept backlog、keep-alive 与平台线程回退容量 |
| OOM 现场 | `HeapDumpOnOutOfMemoryError`、持久化 heap dump、`ExitOnOutOfMemoryError` |
| GC 诊断 | 轮转 GC / safepoint 日志，每个服务最多 5 个 10 MB 文件 |
| 数据库连接池 | 五服务显式配置 HikariCP 最大连接数、最小空闲、获取超时、生命周期和 keepalive |
| 业务线程池 | Chat SSE、Knowledge ingestion、Channel webhook 都使用有界线程池和有界队列 |
| 指标 | JVM、HTTP、HikariCP 和业务线程池指标统一暴露到 `/actuator/prometheus` |
| 告警 | 堆占用、GC P99、线程数、连接池等待/耗尽、业务队列饱和度 |
| 诊断 | 支持 `jcmd` 快照、容器资源快照、GC 日志和 heap dump 归档 |
| 压测 | 可预热的并发基线脚本，输出错误率、吞吐和 P50/P95/P99，支持阈值失败与 TSV 留档 |
| JFR 门槛 | 压测期间自动录制 JFR，统计 `VirtualThreadPinned`、提交失败与 GC 事件 |

## 默认资源基线

| 服务 | 内存上限 | CPU 上限 | Hikari 最大连接 | 本地 Jar 最大堆 |
|---|---:|---:|---:|---:|
| identity-service | 512 MB | 1.0 | 10 | 384 MB |
| agent-service | 512 MB | 1.0 | 8 | 384 MB |
| knowledge-service | 1 GB | 1.5 | 10 | 768 MB |
| chat-service | 1 GB | 2.0 | 16 | 768 MB |
| gateway-service | 512 MB | 1.0 | 12 | 512 MB |

容器最大堆按内存上限的 70% 计算，剩余空间留给 Metaspace、线程栈、Direct Buffer、
JIT Code Cache 和本地库。不要把 `-Xmx` 设置成容器内存上限。

## 本地实测基线（2026-08-01）

测试使用 Java 21 本地 Jar 运行五个服务，PostgreSQL/pgvector 与 Redis 运行在 Docker Desktop。
负载生成器与服务位于同一台开发机，因此结果用于代码回归和 JVM 参数 A/B，不作为生产 SLA
或容量承诺。

| 链路 | 口径 | 成功率 | 吞吐 | P50 | P95 | P99 |
|---|---|---:|---:|---:|---:|---:|
| gateway `/actuator/health` | 预热 5000 后连续 3 轮，每轮 5000、并发 50，取中位数 | 100% | 3693 req/s | 13 ms | 20 ms | 31 ms |
| chat 会话列表 + PostgreSQL | 500 请求、并发 25，curl 基线脚本 | 100% | 196.55 req/s | 15.66 ms | 63.00 ms | 89.72 ms |

同规格 A/B 中，gateway `-Xms128m` 三轮吞吐中位数为 3693 req/s、P95 中位数为
20 ms；`-Xms256m` 三轮吞吐中位数为 3004 req/s、P95 中位数为 37 ms。因此保留
128 MB 初始堆，最大堆仍为 512 MB。这个结论只适用于当前本地流量模型；上线前仍应使用
独立压测机、固定数据集和目标实例规格复测。

JFR 采样覆盖 gateway、chat 和完整业务 smoke，均未发现 `VirtualThreadPinned` 或
`VirtualThreadSubmitFailed`。gateway 的一次 5000 请求窗口中 GC 总暂停 92.4 ms、平均
4.02 ms、最大 26.4 ms，无 Full GC；chat 的 500 请求数据库链路只出现 1 次 GC。

`/api/v1/system/health` 受匿名限流保护：100 次预热后继续发送 1000 次请求会返回 429，
这是限流生效，不是 JVM 失败。基础 JVM 压测使用免限流的 `/actuator/health`；业务链路压测
必须控制在配额内，或在隔离测试环境显式覆盖限流参数，不能在生产配置中关闭保护。

## 配置入口

Compose 资源边界：

```bash
CHAT_MEMORY_LIMIT=1536m
CHAT_CPU_LIMIT=2.0
KNOWLEDGE_MEMORY_LIMIT=1536m
KNOWLEDGE_CPU_LIMIT=2.0
```

JVM 参数可按服务覆盖：

```bash
CHAT_JAVA_TOOL_OPTIONS="-XX:+UseG1GC -XX:InitialRAMPercentage=25 -XX:MaxRAMPercentage=70 ..."
```

默认配置还包含：

```text
-XX:+ParallelRefProcEnabled
-XX:+UseStringDeduplication
-XX:+DisableExplicitGC
-Xlog:async
```

`DisableExplicitGC` 防止依赖或业务代码通过 `System.gc()` 触发 Full GC。没有设置 `Xmn`、
`NewRatio` 等年轻代参数，保留 G1 根据停顿目标动态调整的能力。

虚拟线程、入口连接数和 Trace 采样可独立覆盖：

```bash
SPRING_THREADS_VIRTUAL_ENABLED=true
CHAT_HTTP_MAX_CONNECTIONS=2048
GATEWAY_HTTP_MAX_CONNECTIONS=2048
OTEL_TRACING_SAMPLING_PROBABILITY=0.1
```

Tomcat 的 `threads.max` 在虚拟线程开启时不生效，只作为关闭虚拟线程后的平台线程回退值。

HikariCP 通过服务前缀配置。以 Chat 为例：

```bash
CHAT_DB_MAX_POOL_SIZE=16
CHAT_DB_MIN_IDLE=4
CHAT_DB_CONNECTION_TIMEOUT_MS=5000
CHAT_DB_VALIDATION_TIMEOUT_MS=2000
CHAT_DB_IDLE_TIMEOUT_MS=600000
CHAT_DB_MAX_LIFETIME_MS=1800000
CHAT_DB_KEEPALIVE_TIME_MS=300000
CHAT_DB_LEAK_DETECTION_THRESHOLD_MS=0
```

其他服务将 `CHAT` 替换为 `IDENTITY`、`AGENT`、`KNOWLEDGE` 或 `GATEWAY`。
连接池上限应与 PostgreSQL `max_connections`、实例数和业务并发一起计算，不能只看单个服务。

## 可重复验证流程

1. 启动微服务和监控：

```bash
docker compose --profile microservices --profile observability up -d --build
```

2. 执行带预热的健康接口基线，并保存同口径结果：

```bash
JVM_BASELINE_WARMUP_REQUESTS=100 \
JVM_BASELINE_REQUESTS=1000 \
JVM_BASELINE_CONCURRENCY=25 \
JVM_BASELINE_RESULT_FILE=.run/performance/health.tsv \
scripts/jvm-load-baseline.sh
```

3. 设置门槛后，对真实业务接口采样。需要鉴权和请求体时：

```bash
JVM_BASELINE_URL=http://127.0.0.1:8087/api/v1/example \
JVM_BASELINE_METHOD=POST \
JVM_BASELINE_AUTH_TOKEN="<jwt>" \
JVM_BASELINE_BODY='{"example":"value"}' \
JVM_BASELINE_REQUESTS=200 \
JVM_BASELINE_CONCURRENCY=10 \
JVM_BASELINE_MAX_ERROR_RATE_PERCENT=0 \
JVM_BASELINE_MAX_P95_SECONDS=1.0 \
JVM_BASELINE_MAX_P99_SECONDS=2.0 \
JVM_BASELINE_MIN_THROUGHPUT=20 \
scripts/jvm-load-baseline.sh
```

内部服务或其他自定义 Header 使用换行分隔的 `JVM_BASELINE_EXTRA_HEADERS`。脚本只把 2xx/3xx
计为成功，避免某些压测工具把 401/403/429 也统计成“完成请求”：

```bash
JVM_BASELINE_URL=http://127.0.0.1:8083/api/v1/chat/conversations \
JVM_BASELINE_EXTRA_HEADERS=$'X-Internal-Token: <token>\nX-User-Id: <user-id>' \
JVM_BASELINE_REQUESTS=500 \
JVM_BASELINE_CONCURRENCY=25 \
JVM_BASELINE_MAX_ERROR_RATE_PERCENT=0 \
scripts/jvm-load-baseline.sh
```

超过任一门槛时脚本返回非零状态，可直接接入 CI。curl 版本用于低依赖回归与前后对照；
正式容量结论仍应使用固定数据集和独立压测机，避免负载生成器与服务争抢本机 CPU。

4. 压测期间观察 Grafana 的 **JVM Overview**，随后采集现场：

```bash
scripts/collect-jvm-diagnostics.sh
```

采集结果写入 `.run/jvm-snapshots/<timestamp>`，包括 JVM flags、堆概况、线程 dump、
Prometheus 原始指标、容器限制/使用量和持久化 GC 日志。

## JFR 使用

容器启动时可只为目标服务开启一段受控 JFR：

```bash
CHAT_JDK_JAVA_OPTIONS="-XX:StartFlightRecording=name=spaceagent-chat,settings=profile,filename=/app/diagnostics/chat-service.jfr,dumponexit=true,maxage=30m,maxsize=256m" \
docker compose --profile microservices up -d --build chat-service gateway-service
```

本地 Jar 模式可以对运行中的进程采样：

```bash
pid="$(cat .run/microservices/chat-service.pid)"
jcmd "$pid" JFR.start name=spaceagent-chat settings=profile duration=120s filename=.run/jvm/chat-service.jfr
```

也可以把负载和虚拟线程检查合并执行：

```bash
JVM_PROFILE_SERVICE=chat-service \
JVM_PROFILE_MAX_PINNED_EVENTS=0 \
JVM_BASELINE_REQUESTS=500 \
JVM_BASELINE_CONCURRENCY=50 \
scripts/jvm-jfr-profile.sh
```

该脚本面向 `scripts/start-microservices-local.sh` 启动的本地 JVM；它录制负载窗口，
并在出现 `jdk.VirtualThreadPinned` 或 `jdk.VirtualThreadSubmitFailed` 时失败。
优先检查 Allocation、Old Object、Monitor Blocked、Socket I/O、VirtualThreadPinned、
Thread Park 和 GC Pause。

## 调优判断顺序

1. 先确认错误率、P95/P99 和业务吞吐是否满足目标。
2. 若堆长期超过 85%，检查分配速率、缓存上限和对象生命周期，再考虑增大内存。
3. 若 GC P99 高，结合 GC 日志/JFR 判断是分配压力、晋升压力还是 Full GC，避免先换收集器。
4. 若 Hikari pending 持续大于 0，先查慢 SQL 和事务持有时间，再评估连接池与数据库容量。
5. 若业务队列超过 80%，检查下游模型、Embedding、MCP 或渠道延迟；扩线程前确认 CPU 和连接数余量。
6. 每次只调整一组变量，保留预热时长、请求样本、并发度和结果，进行前后对照。

## RAG 性能基线

`knowledge-service` 的默认配置控制外部调用、堆内缓存和数据库批次，不依赖扩大线程池来提高吞吐：

| 参数 | 默认值 | 目的 |
| --- | ---: | --- |
| `AI_EMBEDDING_BATCH_SIZE` | `10` | 文档摄取将 N 个 chunk 的 HTTP 调用上限收敛到 `ceil(N/10)` |
| `AI_EMBEDDING_CACHE_MAXIMUM_SIZE` | `1000` | 限制向量缓存占用，缓存 key 使用 SHA-256 摘要 |
| `AI_EMBEDDING_CACHE_TTL_SECONDS` | `600` | 复用短时间重复查询，避免长期保存无效向量 |
| `KNOWLEDGE_INGESTION_PERSISTENCE_BATCH_SIZE` | `100` | 将逐 chunk INSERT 改为受控 JDBC batch |
| `KNOWLEDGE_DB_REWRITE_BATCHED_INSERTS` | `true` | 允许 PostgreSQL JDBC 将兼容的 INSERT batch 合并发送 |
| `KNOWLEDGE_BASE_HNSW_EF_SEARCH` | `100` | 控制 HNSW 召回宽度，实际值至少等于候选数量 |

调参时同时观察 `embedding.generate`、`rag.retrieve`、Hikari pending、摄取队列和堆占用。
批次过大会放大单次失败重试成本；缓存过大会增加 Old Gen 压力；`ef_search` 过高会增加
查询 CPU 与延迟。性能结论必须基于相同文档、相同查询集和相同并发度的前后对照。

## 关于虚拟线程

五个 Spring MVC 服务默认使用 Java 21 虚拟线程承接请求，适合数据库、内部 HTTP、
模型和 MCP 等阻塞 I/O。Chat SSE、Knowledge ingestion 和 Channel webhook 仍保留
有界平台线程池，因为它们承担并发隔离和背压职责。虚拟线程降低“等待 I/O 时占用平台线程”
的成本，但不会提高 CPU 密集型文本解析、rerank 或向量计算的速度，也不能替代 HikariCP、
远程调用 bulkhead、模型限流和队列上限。

虚拟线程开关必须做 A/B 对照。若 JFR 持续出现 pinning，可临时使用
`SPRING_THREADS_VIRTUAL_ENABLED=false` 回退，再根据事件栈修复持锁阻塞调用。

## 面试表述

可以准确表述为：

> 我为五个 Java 21 微服务建立了容器资源边界和 G1 基线，保留 OOM、GC 与 JFR 诊断现场；
> 使用虚拟线程承接阻塞型 MVC 请求，并保留 Hikari、远程调用 bulkhead 和三个有界业务
> 线程池控制下游并发；通过 Prometheus/Grafana 观察堆、GC P99、HTTP P95、连接池和队列，
> 再使用带阈值的并发脚本与 JFR pinning 门槛做前后对照。

不要在没有保存测试结果时宣称具体百分比提升。

参考：

- [Spring Boot 3.5 Virtual Threads](https://docs.spring.io/spring-boot/3.5/reference/features/spring-application.html)
- [Oracle Java 21 Virtual Threads](https://docs.oracle.com/en/java/javase/21/core/virtual-threads.html)
- [Oracle Java 21 G1 Tuning](https://docs.oracle.com/en/java/javase/21/gctuning/garbage-first-garbage-collector-tuning.html)
