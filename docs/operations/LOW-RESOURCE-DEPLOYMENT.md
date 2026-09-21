# 可选2CPU/4GiB低配部署

目标是降低常驻资源并保持可拓展，**没有删除功能/组件，也没有合并管理员和租户数据**。
这是一份资源预算与部署选择，不是100人并发、所有项目编译、生产HA的容量承诺。

## 配置收敛与组件选择

| 清单 | 责任 |
| --- | --- |
| `docker-compose.yml` | 原基础拓扑与原默认行为 |
| `docker-compose.images.yml` | 可复用的预构建镜像引用，与资源预算分离 |
| `docker-compose.release.yml` | 保留正式环境密钥/受信代码边界 |
| `docker-compose.low-resource.yml` | 可选资源预算、池大小、并发和关闭重组件的运行参数 |
| 原rag/parser/knowledge-worker/observability配置 | 全部保留，大规格部署可显式启用 |

低配默认启动：PostgreSQL、业务后端、独立管理员后端、两个静态客户端、私有OCI控制服务及
两个串行初始化任务。选择pgvector，没有Milvus进程。代码文件读写、编程Agent/独立评审、Git
隔离/分支和原业务流程仍存在；资源不足的构建/测试会真实失败，不伪造完成。

默认不运行：Milvus、Tika、独立Knowledge Worker JVM、Grafana/Prometheus/Loki/Tempo/Alloy、
本地reranker、TypeScript自动规划、搜索服务和自动Registry/Provider探测。代码/清单仍在。
手动Provider/MCP配置和既有Registry条目没有删除；运行能力继续由后台配置/权限决定。
审计、在线心跳、账本/资源观察、必要恢复/清理任务保留；轮询/运营统计降低频率。

## 显式预算

| 进程/容器 | 上限 | 参数 |
| --- | --- | --- |
| 业务Java | 1152MiB /1CPU | 堆640MiB，连接池6，HTTP线程32，SSE总8/每用户2 |
| Admin Java | 512MiB /0.3CPU | 堆256MiB，连接池2，独立身份/数据库不变 |
| PostgreSQL | 512MiB /0.5CPU | shared_buffers128MiB、work_mem2MiB、维护64MiB、40连接、单autovacuum worker |
| OCI控制服务 | 160MiB /0.25CPU | 不拥有业务状态，私有Docker入口 |
| web/admin-web | 各64MiB /0.1CPU | 预构建静态文件 |
| 每个执行容器 | 最多512MiB /0.75CPU | 全worker单槽、128PID、两处各32MiB tmpfs、仍禁网/禁止swap |
| 初始化任务 | 各64MiB /0.25CPU | 依赖顺序保证两初始化任务不并行 |

常驻上限2464MiB＋执行512MiB＋宿主/反代等预留768MiB＝3744MiB；再保守计入一个初始化任务
可能与恢复业务并存，峰值3808MiB。CPU数是各进程上限，不是独占预留；总需求超过2核时会竞争。
控制服务的mem_limit**不覆盖**通过Docker创建的兄弟执行容器，故单独配置/验证执行CPU/内存。
SSE限额不是普通HTTP/所有后台模型请求的全局容量表；单执行槽也不等于全平台编程任务排队器。
多任务忙时仍有原容量拒绝/失败语义，不能承诺自动排队。需少量用户/受控使用与实际压力测试。

低配缩小Artifact对象/暂存额度并启用Docker日志轮转；这是业务额度，不是操作系统硬磁盘配额。
原文件、Git镜像/worktree、向量、数据库WAL和备份仍占磁盘，必须规划容量和保留期，备份另存。
不能通过禁用autovacuum、关闭审计/恢复或手动删用户代码节省资源。

## 启用步骤（操作者在目标服务器执行）

1. 在本地/CI使用原Dockerfile构建并发布或load镜像。不要在2核4GiB服务器上跑Maven/npm/Docker build。
   **业务镜像需要此次配置化代码；Worker镜像必须含新增资源CLI。旧Worker不能假定认识这些环境变量。**
2. 用 `.env.release.example`、`.env.low-resource.example` 准备两份受保护配置；填入真实镜像引用、
   所有独立密钥/管理员密码，chmod0600，禁止提交到Git。不要让JVM选项覆盖低配堆/CPU预算。
3. 配置原生产TLS/mTLS/私有管理员入口、只读证书/信任材料挂载、数据卷权限和备份。低配方案没有
   自动搭建TLS，资源预检也不证明证书/私有握手已验收。不要为启动而打开insecure-local或暴露Docker。
   如需操作者的TLS overlay，显式export绝对路径，不从env文件执行shell代码。
4. 只适用新部署或已绑定pgvector的数据库。Milvus-bound旧数据库会被原持久后端绑定拒绝，不能
   删除绑定/索引或只改.env切换；先单独规划验证迁移。

```sh
export LOW_RESOURCE_TLS_OVERLAY=/absolute/operator-tls.yml  # 按需要；保持预算和核心服务集合
bash scripts/deploy-low-resource.sh /absolute/release.env /absolute/low-resource.env config
# 这里只输出脱敏预算，不输出Compose完整环境变量；样例仅可结构预览，不是上线配置。
bash scripts/deploy-low-resource.sh /absolute/release.env /absolute/low-resource.env pull
bash scripts/deploy-low-resource.sh /absolute/release.env /absolute/low-resource.env up
bash scripts/deploy-low-resource.sh /absolute/release.env /absolute/low-resource.env status
```

Wrapper清空继承的COMPOSE_PROFILES，只选web/admin/sandbox；合并配置后预检会拒绝额外重组件、
超预算、JVM覆盖、错误子配额和安全降级。`up`检查宿主实际可见RAM/CPU，并使用`--no-build --pull never`。
`pull`是明确的镜像下载动作；Wrapper不提供build/down/prune/删卷。预检不代替完整发布安全/网络验收。
低配清单不自动停止此前已启动的重组件。旧部署须先由操作者核对并停止对应重服务，保留其数据卷；
否则这些旧进程仍消耗宿主资源，不能按本预算验收。
不能在已有满载服务器上靠增加Swap承诺性能；执行容器仍按原策略禁Swap。

### 知识库和可选功能

模板保守保持intake/index worker关闭。要对新文本/Markdown执行索引，确认Pending任务/预算后，
显式设置 `PLATFORM_KNOWLEDGE_INDEX_INTAKE_ENABLED=true` 与 `PLATFORM_KNOWLEDGE_INDEX_WORKER_ENABLED=true`。
该Worker与API同JVM，不再启动额外knowledge-worker；会按原协议继续 eligible Pending工作，
不会盲目重试UNKNOWN。既有已发布知识库仍通过真实pgvector/原权限查询，Embedding/模型走外部API。

PDF/DOCX知识库解析需要Tika。低配默认不启动本地解析器，可显式配置受保护外部HTTPS解析端点；
未配置时真实报告不可用，不能声称该部署支持所有二进制格式。Workspace内原有文件工具没有删除。

### 拓展/恢复正常部署

扩容后去掉low-resource overlay，保留base/images/release，并显式选择原parser/rag/observability/
knowledge-worker等清单/配置；每项重新预算/验收。需要独立Knowledge Worker时使用原Worker overlay，
API关闭其内置索引Worker，确保同库/同后端/同对象存储，并给Worker显式配置/准备对应预构建业务镜像。
已有backend选择不自动改变。外部orchestrator/搜索/重排配置和端点在正常方案中仍可启用。
不调用down-v、不删除卷或用户逻辑/物理代码，保留任务/审计/权限和原恢复协议。

## 本轮验证范围

原默认SSE256/每用户4、Worker2GiB/1CPU/四执行槽保持；新限额仅明确配置后变化。Java新增了
原每用户SSE计数的并发增删收敛，避免最后一个release误删正在新增的计数。
完整Maven904/904、Worker29/29、merged config5/5、低配真实OCI和package/架构通过；没有新迁移，
没有读取真实APIKey、模型调用、前端修改或当前业务栈激活。
受限业务启动smoke与具体观测结果单列在低配验收记录；仅business+PG，不冒充全栈/100并发。
实际2CPU/4GiB宿主、完整Admin/mTLS、混合长回答/索引/编译压力、磁盘硬配额、生产HA/runsc仍待验收。
