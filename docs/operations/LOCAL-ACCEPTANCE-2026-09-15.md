# 系统整改后的补充本地验收

日期：2026-09-15。本报告对应当时的受控产品源码；私有提交号不随公开快照分发。本轮只新增测试、验收脚本和报告，没有修改产品实现、页面或迁移，也没有更新运行中的业务栈。

结论：本轮选择的本地验收全部通过。此前因本地依赖未配置而跳过的 6 项已补跑通过；不能由此宣称生产上线条件全部满足。

## 结果与证据边界

| 验收面 | 本轮结果 | 证明了什么 | 不证明什么 |
| --- | --- | --- | --- |
| Tika/Milvus 真实本地依赖 | 6/6，无跳过 | PDF/DOCX 解析、真实 PDF 页码、索引发布/修复/删除、中文/英文检索、租户及 generation 过滤 | 真实付费 Embedding、生产数据规模和召回质量 |
| 资源统计 HTTP/管理员契约 | 13/13，无跳过，含 6 个新增用例 | 生产 Spring 过滤器/路由/SQL、精确管理员权限、个人统计隔离、null/数值精度、失败与成功审计 | 当前部署版本已升级；完整双服务上线联调 |
| 当前源码 OCI Executor/ResourceSampler | 3 个真实执行容器及派发前拒绝均符合预期 | 非 root、只读根、无网络、能力移除、只读 Workspace 拒写、超时杀死、可信采样、子容器清理 | Linux/runsc 验收、硬磁盘配额、完整 CPU 计量或峰值 RSS |
| 当前共享消息组件与 Markdown/Viewport | 3 个视口，各 11 条断言，共 33 条通过 | 长历史 DOM 窗口、流式消息在完成前实际渲染、不自动下滑、独立滚动、无页面横向溢出、显式最新消息按钮 | 全平台设备/GPU 性能、所有页面导航性能、真实后端 SSE 全链路 |

本轮未重复已通过的完整 Maven/Web/Admin Web 套件与打包；没有改动那些测试所覆盖的产品实现。前次完整回归依据见 [系统整改记录](SYSTEM-AUDIT-REMEDIATION-2026-09-15.md)。本轮数字仅统计实际新执行的集合，不把旧报告并成一次新的全量测试。

## 功能与隔离

新增 `PlatformResourceObservationHttpPostgresTest` 使用自己的随机 PostgreSQL 容器与完整生产 Spring 配置：

- 匿名访问个人及管理员资源接口被拒绝；租户 JWT 无法访问私有管理员接口。
- 管理员必须持有 `system-admin:resources:observations:read` 精确权限；其他统计权限、带租户身份声明的服务 JWT 均被拒绝。
- 个人统计使用当前认证的 Organization/User；URL 中指定别人的 `organizationId`/`userId` 不会改变范围。
- 外租户用户看不到记录；切换到相同 Organization 的另一个 MEMBER 仍看不到该用户的个人统计。
- 数据库夹具中两次 CPU 观察合计 246；同一 Workspace 文件快照取最新值 200，而不是相加为 300；缺失网络观察保留 null，不伪造 0。
- 无效时间格式、反向时间范围、超过 90 天的范围被拒绝。
- 管理员读取只返回汇总 DTO，不暴露命令、环境、物理路径或密钥。

管理员侧补充了真实本地 HTTP 客户端序列化测试、生产管理员登录/路由/数据库审计测试，以及上游不可用测试。`9000000000000000001` 数值在 Java 客户端中保持 BigDecimal 精度；上游失败不会返回缓存或零指标。管理员登录测试的上游客户端是明确的 mock，因此这里是分段契约验收，不冒充两个实际服务的完整 E2E。

## 资源与性能实测

真实 OCI 验收使用缓存 `node:22-alpine` 的不可变镜像 ID、独立临时 Workspace，运行正常写文件、只读拒写、超时三个子容器，另验证不支持的网络权限在创建容器前被拒绝。当前 Python 控制面源码运行于宿主；不是宣称已重建 Sandbox Worker 部署镜像。

最后一次实测：

- CPU 累积采样：1,866,407,000 ns。
- 最大已观察 cgroup 内存：43,311,104 bytes，不是进程峰值 RSS。
- Workspace 表观文件字节：655,955，与本次夹具文件合计一致。
- 无网络接口计数时 Rx/Tx 为 null。
- 子命令输出伪造的 `resourceMetrics` 数字没有进入可信采样结果。

Milvus 检索烟测：256 行、128 维、20 次向量+词法查询对，P50 7ms、P95 12ms、约 131.58 查询对/秒。仅是固定小夹具、串行请求，不是生产容量承诺。

浏览器使用生产 React 构建、真实共享消息组件/CSS、120 条合成历史记录（301,020 字符），增量追加 26,736 字符。增加了可见历史确实挂载、当前 live 消息确实已渲染超过 20,000 字符的断言，防止测空占位或把历史内容误判为流式渲染成功。

| 视口/语言/主题 | 流式帧间隔 P95 | 滚动帧间隔 P95 | 最后一次观察的 >25ms 帧/Long Task |
| --- | --- | --- | --- |
| 1440 / 英文 / 浅色 | 16.7ms | 16.7ms | 0 / 0 |
| 1100 / 中文 / 深色 | 16.7ms | 16.7ms | 0 / 0 |
| 390 / 日文 / 浅色 | 16.8ms | 16.7ms | 0 / 0 |

每种场景采集 90 个流式与 90 个滚动帧间隔，DPR 2。早期复跑曾观察到个别 33–66ms 帧，最后一次无长帧；这说明短时采样和宿主负载存在波动，不意味着“前端卡顿已彻底消失”。历史卸载后初始 DOM 为 363–371 节点，全部消息数据仍保留。语言/主题参数应用于实际 Shell；合成正文固定中文，不是完整国际化验收。

## 可复跑入口

```sh
# 本地解析服务已可用；启动一个无持久卷的独立缓存 Milvus，结束自动移除。
SPACEAGENT_TIKA_TEST_URI=http://127.0.0.1:19998 node scripts/run-local-dependency-acceptance.mjs

# Docker SDK 与 worker 依赖应已安装；macOS 须指定当前 Unix Docker socket。
DOCKER_HOST=unix:///path/to/docker.sock PYTHONPATH=workers/sandbox-worker \
  python3 scripts/run-local-oci-acceptance.py

DOCKER_HOST=unix:///path/to/docker.sock \
TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock TESTCONTAINERS_HOST_OVERRIDE=127.0.0.1 \
  ./mvnw -o -q -Dsurefire.failIfNoSpecifiedTests=false \
  '-Dtest=PlatformResourceObservationHttpPostgresTest,AdminPlatformClientTest,AdminPlatformReadServiceTest,PlatformAdminAuthenticationPostgresTest#resourceObservationRouteRequiresAdministratorPreservesUnknownsAndPersistsReadAudit' test

WEB_TEST_FIXTURE=conversation-performance node scripts/verify-web-interactions.mjs
```

依赖脚本仅允许本地 HTTP 解析器和 Unix Docker socket；远程 Docker 配置的拒绝已验证，未产生远程连接。密码在内存生成、不输出、不写入 .env。测试只删除自己随机创建的 collection/容器和专用临时文件，未删除用户数据。

本机证据：

- 原始依赖、HTTP 与浏览器日志是本机临时证据，不随仓库分发；对应测试结果摘要保留在本页。
- OCI 实测输出记录于本报告；临时 Workspace 与三个子容器已移除。

## 仍待验收的上线条件

1. 独立 Linux/runsc、非受信代码运行边界、硬磁盘配额、生产 mTLS/私有入口。
2. 真实 Provider 的流式/续写/计费、非默认 Embedding 维度及模型批次兼容性、真实 GitHub MCP/OAuth/分支工作流。需要明确授权外部与付费验收，当前为 LIVE NOT RUN。
3. 生产规模数据、并发租户/Run、资源统计查询容量、低性能设备/GPU 与真实网络下的页面和 SSE 性能。
4. 发布时实际镜像/迁移升级、真实双服务管理员统计联调、生产备份恢复与可观测告警/故障演练。

原有服务在本轮后仍运行；新增 Milvus、Testcontainers 和 OCI 子容器已退出/移除。`output/` 中原有未跟踪文件保留且不纳入提交。运行栈未更新，因此这里只能得出“源码和隔离本地验收通过”，不能得出“当前部署已获得所有整改”或“生产可上线”。
