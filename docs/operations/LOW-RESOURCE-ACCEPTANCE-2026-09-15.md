# 可选低配部署收敛验收

M81-PR1。仅配置/基础设施资源入口收敛，无前端、功能/数据删除、新迁移；私有源码标识不随公开快照分发。
模型APIKey读取、付费调用或现有栈更新。使用方法：[低配部署手册](LOW-RESOURCE-DEPLOYMENT.md)。

## 范围与结果

- 原基础拓扑/默认限制保留；新增通用镜像引用overlay，低配预算与镜像选择分离，可去掉预算
  后继续使用预构建镜像/原扩展清单。Milvus/pgvector/Tika/独立Worker/监控/编排均未删除。
- 原SSE256/每用户4和Worker四槽/最多2GiB/1CPU默认值保持。可配置较小上限；每用户SSE
  增减删除使用同一map原子操作，修复最后release与新acquire的计数竞态。
- low merged Compose真实选择8项：6常驻服务与2串行initializer。常驻2464MiB＋执行512MiB＋
  host/proxy768MiB＝3744MiB；再保守预留并存initializer64MiB，peak3808MiB。CPU是上限不是预留。
  低配配置固定pgvector与私有OCI，禁用重组件和自动探测，不禁用权限/审计/评审/恢复。
- 配置/负例覆盖超进程预算、额外重服务、错误子槽/内存、隐式改Milvus、JVM覆盖、安全降级、
  不安全入口/特权/子进程root、runtime占位符。原normal组件配置仍存在。

## 实际检查

完整 `node scripts/run-local-dependency-acceptance.mjs --full` exit0：新报告Shared25 / Platform862 /
Admin17，总904/904，零错误/跳过。实际隔离Milvus/Tika/PG执行，模型模拟；没有启用真实Provider。
Worker全部29/29，新增operator caps/非法值；HTTP夹具首次因sandbox端口权限失败，授权本机随机
端口后重跑通过。Java admission4/4，包含全局/每用户/幂等release与并发反复获取释放。
`./mvnw -o -q -DskipTests package`、架构、merged YAML/配置、Python/shell与diff检查PASS。
merge/guard独立测试5/5（含安全负例），不调用Docker daemon或模型。

真实OCI：当前Python源码控制面＋缓存Node镜像，单槽/最多512MiB/.75CPU/128PID/每tmpfs32MiB；
daemon参数检查、良性命令、实际只读拒写、timeout kill、egress派发前拒绝、清理均PASS。
最后CPU采样1,810,015,000ns / max observed cgroup43,409,408bytes / apparent workspace655,955bytes，
不冒充精确CPU、peak RSS或allocated disk。

当前已打包Jar的受限boot smoke：缓存JRE/PG镜像、专用临时bridge和loopback测试端口、无模型/URL
任务。business1152MiB/1CPU/heap640MiB、PG512MiB/.5CPU。实际readiness、注册、pgvector知识库创建
PASS，单次观察cgroup549,662,720bytes（约524MiB）。这是**business＋PG**，为了scope不执行编码任务，
测试时编码/恢复Worker关闭；不是完整low profile/Admin/mTLS/所有操作的联合压力验收。
原Jar命令/限制来自merged low manifest，不另外使用高配堆。

该smoke先遇到两处夹具问题：max-file1的local日志驱动需compress=false，以及Docker internal
network没有宿主测试port binding。只修正测试夹具；后改专用bridge但不创建Provider/URL任务/
真实Key或外部调用。已清理第一次created但未启动的精确临时容器，以及后续所有专用容器/卷/
network/临时文件；未重启或删原业务服务。没有为此再做全Maven或业务镜像构建。

## 证据/边界

- 全 Maven、focused 与 package 原始日志是本机临时证据，不随仓库分发；本页保留脱敏结果摘要。
- OCI/boot实测只保留上面的脱敏数值/结果，生成fixture已清理。

源码已验证、隔离fixture执行；现有栈未更新，Worker镜像也未重建/激活。部署时必须在CI重建含
新资源CLI的Worker镜像，旧镜像不能当成已接受这些参数。完整TLS/mTLS部署仍由操作者提供和验收。
实际2CPU/4GiB宿主和混合并发/长回答/索引/大型编译未测试；100注册用户不等于100并发。
二进制知识库解析默认关闭，须外置Tika或另行扩容，功能未删。单child slot不是编程Task全局队列，
资源拒绝/失败按原业务处理。OS硬磁盘配额、生产HA/runsc仍未完成。原output/未跟踪文件保留。
