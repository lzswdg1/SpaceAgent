# 本地业务后端 V1095 迁移与激活

日期：2026-09-15。用户明确授权备份、同时迁移并重启业务后端。

## 已完成

- 仅停止、更新和启动 `spaceagent-platform-server`；管理员、前端、PostgreSQL 及其他容器未重启。
- 从当时受控源码打包 JAR，复用旧运行时基础镜像，仅替换应用并准备知识库持久目录；私有提交号不随公开快照分发。
- 现有配置密钥未变化；目标期望 schema 为1095，知识索引 intake/worker 均保持 false，向量与 reranker mode=none。
- 停止业务写入后生成 custom-format pg_dump，权限600、Git忽略。
- 恢复到唯一临时数据库，核对1085版本与核心数量，再在副本中事务预演10条待执行SQL；全部通过。
- 正式启动由 Flyway 完成 V1086–V1095，当前版本1095，成功迁移总数107。
- Readiness=`UP`，容器=`healthy`；8080 `/app/project` 返回200。
- 容器 JAR 与打包文件 SHA-256 相同：
  `c87df325aee0679818baa97be2dc94c499d398b54e1b1e6f18654063dd62103f`。

## 数据与恢复证据

迁移前后：users10、agents11、conversations8、runs50；model calls104、tool calls181、knowledge documents3。
两个8月历史 IN_PROGRESS Run 未手工改写或重跑。迁移不代表这些旧 Run 已完成。

备份：`.run/backups/spaceagent-platform-20260915T044910Z.dump`，校验清单为同名 `.manifest.json`。
旧镜像保留为 `spaceagent-m10-pr1-platform-server:before-v1095-20260915`。
临时恢复验证数据库已删除；正式备份保留。工作区代码卷、Provider/MCP密钥来源和用户数据未清理。
需要回退时先停止业务入口，基于备份与旧镜像按恢复手册处理，禁止在新写入发生后盲目覆盖数据库。

## 生效范围

业务后端已包含仓库正文伪工具调用纠正、统计时间范围和 Skill 后端证据等当前源码能力。
没有自动重发用户问题、执行付费模型测试或宣称外部验收通过。
Admin V5密码登录及后续未发布的前端源码不属于本次激活范围，不能因业务后端升级而宣称其已上线。
本次只激活本地环境，不推送远程，不变更 M78 后续任务顺序。
