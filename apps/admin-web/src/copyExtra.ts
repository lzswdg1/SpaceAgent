export const extraMessages: Record<string, [string, string, string]> = {
  "administration": [
    "管理后台",
    "管理画面",
    "Administration"
  ],
  "overviewTitle": [
    "系统总览",
    "システム概要",
    "Overview"
  ],
  "resourceTitle": [
    "资源数量",
    "リソース数",
    "Resource inventory"
  ],
  "resources": [
    "资源统计",
    "リソース集計",
    "Resources"
  ],
  "commands": [
    "操作记录",
    "操作履歴",
    "Operations"
  ],
  "commandsTitle": [
    "操作记录",
    "操作履歴",
    "Operations"
  ],
  "audit": [
    "管理审计",
    "管理監査",
    "Admin audit"
  ],
  "auditTitle": [
    "管理审计",
    "管理監査",
    "Admin audit"
  ],
  "auditCopy": [
    "查看管理员的登录、读取和修改记录。",
    "管理者のログイン・閲覧・変更履歴を確認します。",
    "Administrator sign-in, read and change history."
  ],
  "commandsCopy": [
    "查看操作结果；结果不确定时先核查，不要重复执行。",
    "操作結果を確認します。結果不明の場合は再実行せず照合してください。",
    "Check operation outcomes before retrying uncertain actions."
  ],
  "loading": [
    "加载中…",
    "読み込み中…",
    "Loading…"
  ],
  "loginTitle": [
    "管理员登录",
    "管理者ログイン",
    "Administrator sign-in"
  ],
  "loginCopy": [
    "输入管理员账号密码，然后完成二次验证。",
    "管理者のアカウントとパスワードを入力し、二段階認証を行います。",
    "Enter administrator credentials, then verify your second factor."
  ],
  "securityBoundaryCopy": [
    "普通用户和组织管理员不能登录此后台。",
    "一般ユーザーと組織管理者はこの画面にログインできません。",
    "User and organization administrator accounts cannot access this console."
  ],
  "staleEvidence": [
    "暂时无法刷新，以下为上次获取的数据。",
    "更新できないため、前回取得したデータを表示しています。",
    "Refresh unavailable; showing the previous snapshot."
  ],
  "organizationDeleteCopy": [
    "删除后进入后台清理流程；物理文件清理需单独授权。",
    "削除後はサーバー側で処理します。実ファイルの削除には別途承認が必要です。",
    "Deletion is processed in the background; physical cleanup requires separate authorization."
  ],
  "updatedAt": [
    "更新时间",
    "更新日時",
    "Updated"
  ],
  "refresh": [
    "刷新",
    "更新",
    "Refresh"
  ],
  "dataUnavailable": [
    "数据加载失败，请重试。",
    "データを読み込めません。再試行してください。",
    "Unable to load data. Please retry."
  ],
  "requestFailed": [
    "操作未完成，请重试或查看操作记录。",
    "操作が完了しませんでした。再試行するか操作履歴を確認してください。",
    "Operation failed. Retry or check operation history."
  ],
  "mfaError": [
    "验证失败，请检查密码或当前验证码。",
    "認証に失敗しました。パスワードまたは現在のコードを確認してください。",
    "Verification failed. Check your password or current code."
  ],
  "allRegisteredUsers": [
    "全部注册记录",
    "登録された全レコード",
    "All registered records"
  ],
  "currentSnapshot": [
    "当前数据快照",
    "取得時点のデータ",
    "Current snapshot"
  ],
  "heartbeatOnline": [
    "在线用户（心跳）",
    "オンラインユーザー（ハートビート）",
    "Online users (heartbeat)"
  ],
  "presenceNotCollected": [
    "尚无客户端接入心跳",
    "接続済みのハートビートクライアントなし",
    "No heartbeat clients connected"
  ],
  "presenceUnavailable": [
    "在线统计暂不可用",
    "オンライン集計は利用できません",
    "Presence unavailable"
  ],
  "presencePartial": [
    "仅统计已接入心跳的客户端",
    "ハートビート対応クライアントのみ",
    "Heartbeat clients only"
  ],
  "unfinishedRuns": [
    "未结束运行",
    "未完了の実行",
    "Unfinished runs"
  ],
  "stateNotLiveness": [
    "按保存状态统计，不代表进程存活",
    "保存状態の集計。プロセス稼働の保証ではありません",
    "Stored status, not process liveness"
  ],
  "attention": [
    "需要关注",
    "要確認",
    "Needs attention"
  ],
  "unknownModelCalls": [
    "结果不确定的模型调用",
    "結果不明のモデル呼び出し",
    "Uncertain model calls"
  ],
  "unknownToolCalls": [
    "结果不确定的工具执行",
    "結果不明のツール実行",
    "Uncertain tool executions"
  ],
  "unknownHelp": [
    "先核查结果，避免重复执行",
    "再実行の前に結果を確認",
    "Reconcile before retrying"
  ],
  "providerProblems": [
    "异常或未检测的模型提供商",
    "異常または未確認のモデルプロバイダー",
    "Unhealthy or untested providers"
  ],
  "providerProblemsHelp": [
    "检查连接与凭据配置",
    "接続と認証情報を確認",
    "Check connection and credentials"
  ],
  "failedRunsTotal": [
    "累计失败运行",
    "失敗した実行の累計",
    "Failed runs (all time)"
  ],
  "allTimeCount": [
    "历史累计",
    "全期間の累計",
    "All-time count"
  ],
  "dataDetails": [
    "更多统计与数据说明",
    "詳細集計とデータの説明",
    "More statistics and definitions"
  ],
  "windowScope": [
    "时间范围只影响新增用户和登录人数，其他指标为当前或累计值。",
    "期間指定は新規ユーザー数とログイン人数にのみ適用されます。その他は現在値または累計です。",
    "The time range applies only to new users and sign-ins; other metrics are current or all-time."
  ],
  "newUsers": [
    "新增用户",
    "新規ユーザー",
    "New users"
  ],
  "loginUsers": [
    "登录用户",
    "ログインユーザー",
    "Signed-in users"
  ],
  "recentUsers": [
    "最近 5 分钟活跃用户",
    "直近5分のアクティブユーザー",
    "Active in the last 5 minutes"
  ],
  "refreshSessions": [
    "有效刷新会话（非在线人数）",
    "有効な更新セッション（オンライン人数ではありません）",
    "Valid refresh sessions (not online users)"
  ],
  "release": [
    "服务版本",
    "サービスバージョン",
    "Service release"
  ],
  "schema": [
    "数据结构版本",
    "データベースバージョン",
    "Schema version"
  ],
  "testDataNote": [
    "本地数据库可能包含测试记录；本页不会自动排除或删除这些记录。",
    "ローカルDBにはテスト記録が含まれる場合があります。自動除外・削除は行いません。",
    "Local data may include test records; this view does not silently exclude or delete them."
  ],
  "24h": [
    "近 24 小时",
    "直近24時間",
    "Last 24 hours"
  ],
  "7d": [
    "近 7 天",
    "直近7日",
    "Last 7 days"
  ],
  "30d": [
    "近 30 天",
    "直近30日",
    "Last 30 days"
  ],
  "providers": [
    "模型提供商",
    "モデルプロバイダー",
    "Model providers"
  ],
  "modelPools": [
    "模型池",
    "モデルプール",
    "Model pools"
  ],
  "agents": [
    "智能体",
    "エージェント",
    "Agents"
  ],
  "projects": [
    "项目",
    "プロジェクト",
    "Projects"
  ],
  "workspaces": [
    "工作区",
    "ワークスペース",
    "Workspaces"
  ],
  "conversations": [
    "对话",
    "会話",
    "Conversations"
  ],
  "mcpConnections": [
    "MCP 连接",
    "MCP 接続",
    "MCP connections"
  ],
  "resourceType": [
    "资源类型",
    "リソース種別",
    "Resource type"
  ],
  "total": [
    "总数",
    "合計",
    "Total"
  ],
  "activeCount": [
    "活跃 / 可用",
    "有効 / 利用可能",
    "Active / available"
  ],
  "inventoryHelp": [
    "当前资源数量；按用户查看关联明细请进入用户管理。",
    "現在のリソース数です。ユーザー別の詳細はユーザー管理で確認できます。",
    "Current inventory. User-specific details are available under Users."
  ],
  "inventoryUnavailable": [
    "这里不是消耗统计。CPU、内存、网络和存储用量尚未完整接入。",
    "これは消費量ではありません。CPU・メモリ・通信・ストレージ使用量の集計は未対応です。",
    "Inventory is not consumption. CPU, memory, network and storage usage are not fully integrated."
  ],
  "ACTIVE": [
    "正常",
    "有効",
    "ACTIVE"
  ],
  "PENDING": [
    "待处理",
    "待機中",
    "PENDING"
  ],
  "READY": [
    "可用",
    "利用可能",
    "READY"
  ],
  "UNKNOWN": [
    "结果不确定",
    "結果不明",
    "UNKNOWN"
  ],
  "FAILED": [
    "失败",
    "失敗",
    "FAILED"
  ],
  "SUCCEEDED": [
    "成功",
    "成功",
    "SUCCEEDED"
  ],
  "REVOKED": [
    "已撤销",
    "失効済み",
    "REVOKED"
  ],
  "SUSPENDED": [
    "已暂停",
    "停止中",
    "SUSPENDED"
  ],
  "PENDING_ACTIVATION": [
    "待激活",
    "有効化待ち",
    "PENDING_ACTIVATION"
  ],
  "DELETION_PENDING": [
    "待删除",
    "削除待ち",
    "DELETION_PENDING"
  ],
  "DELETING": [
    "删除中",
    "削除中",
    "DELETING"
  ],
  "DELETED": [
    "已删除",
    "削除済み",
    "DELETED"
  ],
  "CLAIMED": [
    "处理中",
    "処理中",
    "CLAIMED"
  ],
  "RETRY": [
    "等待重试",
    "再試行待ち",
    "RETRY"
  ],
  "BLOCKED": [
    "受阻",
    "ブロック中",
    "BLOCKED"
  ],
  "COMPLETED": [
    "已完成",
    "完了",
    "COMPLETED"
  ],
  "DISPATCHING": [
    "派发中",
    "送信中",
    "DISPATCHING"
  ],
  "RUNNING": [
    "运行中",
    "実行中",
    "RUNNING"
  ],
  "IN_PROGRESS": [
    "进行中",
    "進行中",
    "IN_PROGRESS"
  ],
  "WAITING_FOR_TOOL": [
    "等待工具",
    "ツール待ち",
    "WAITING_FOR_TOOL"
  ],
  "WAITING_FOR_USER": [
    "等待用户",
    "ユーザー待ち",
    "WAITING_FOR_USER"
  ],
  "RECOVERING": [
    "恢复中",
    "復旧中",
    "RECOVERING"
  ],
  "CANCELLED": [
    "已取消",
    "キャンセル済み",
    "CANCELLED"
  ],
  "CHECKPOINTING": [
    "保存进度",
    "進捗保存中",
    "CHECKPOINTING"
  ],
  "PENDING_REVIEW": [
    "待审核",
    "審査待ち",
    "PENDING_REVIEW"
  ],
  "APPROVED": [
    "已批准",
    "承認済み",
    "APPROVED"
  ],
  "REJECTED": [
    "已拒绝",
    "却下済み",
    "REJECTED"
  ],
  "DENIED": [
    "无权限",
    "権限なし",
    "DENIED"
  ],
  "ERROR": [
    "错误",
    "エラー",
    "ERROR"
  ],
  "DISABLED": [
    "已停用",
    "無効",
    "DISABLED"
  ],
  "UNHEALTHY": [
    "异常",
    "異常",
    "UNHEALTHY"
  ],
  "UNTESTED": [
    "未检测",
    "未確認",
    "UNTESTED"
  ],
  "LOCKED": [
    "已锁定",
    "ロック中",
    "LOCKED"
  ],
  "ALL STATUS": [
    "全部状态",
    "すべての状態",
    "ALL STATUS"
  ],
  "ALL STATES": [
    "全部状态",
    "すべての状態",
    "ALL STATES"
  ],
  "ALL": [
    "全部",
    "すべて",
    "ALL"
  ],
  "ALL ROLES": [
    "全部角色",
    "すべてのロール",
    "ALL ROLES"
  ],
  "ALL KINDS": [
    "全部类型",
    "すべての種類",
    "ALL KINDS"
  ],
  "ALL REVIEW STATES": [
    "全部审核状态",
    "すべての審査状態",
    "ALL REVIEW STATES"
  ],
  "ALL JOB STATES": [
    "全部作业状态",
    "すべてのジョブ状態",
    "ALL JOB STATES"
  ],
  "OWNER": [
    "所有者",
    "所有者",
    "OWNER"
  ],
  "ADMIN": [
    "管理员",
    "管理者",
    "ADMIN"
  ],
  "MEMBER": [
    "成员",
    "メンバー",
    "MEMBER"
  ],
  "VIEWER": [
    "只读成员",
    "閲覧者",
    "VIEWER"
  ],
  "PLATFORM_SUPER_ADMIN": [
    "系统管理员",
    "システム管理者",
    "PLATFORM_SUPER_ADMIN"
  ],
  "USER": [
    "用户",
    "ユーザー",
    "USER"
  ],
  "ORGANIZATION": [
    "组织",
    "組織",
    "ORGANIZATION"
  ],
  "SYSTEM": [
    "系统",
    "システム",
    "SYSTEM"
  ],
  "PLATFORM": [
    "平台",
    "プラットフォーム",
    "PLATFORM"
  ],
  "PLATFORM_USER": [
    "平台用户",
    "プラットフォームユーザー",
    "PLATFORM_USER"
  ],
  "GLOBAL INDEX": [
    "记录数",
    "件数",
    "GLOBAL INDEX"
  ],
  "RECORDS": [
    "条记录",
    "件",
    "RECORDS"
  ],
  "COMMANDS": [
    "条操作",
    "件の操作",
    "COMMANDS"
  ],
  "JOBS": [
    "个作业",
    "件のジョブ",
    "JOBS"
  ],
  "REVIEW QUEUE": [
    "审核队列",
    "審査キュー",
    "REVIEW QUEUE"
  ],
  "CANDIDATES": [
    "个候选项",
    "件の候補",
    "CANDIDATES"
  ],
  "GLOBAL QUEUE": [
    "全局队列",
    "全体キュー",
    "GLOBAL QUEUE"
  ],
  "BLOCKED ONLY": [
    "仅受阻",
    "ブロックのみ",
    "BLOCKED ONLY"
  ],
  "COMMAND ID": [
    "操作编号",
    "操作ID",
    "COMMAND ID"
  ],
  "COMMAND": [
    "操作",
    "操作",
    "COMMAND"
  ],
  "OPERATION": [
    "操作类型",
    "操作種別",
    "OPERATION"
  ],
  "Action": [
    "操作类型",
    "操作種別",
    "Action"
  ],
  "Operation": [
    "操作类型",
    "操作種別",
    "Operation"
  ],
  "ACTION": [
    "操作",
    "操作",
    "ACTION"
  ],
  "TARGET": [
    "目标",
    "対象",
    "TARGET"
  ],
  "OUTCOME": [
    "结果",
    "結果",
    "OUTCOME"
  ],
  "ACTOR": [
    "操作人",
    "操作者",
    "ACTOR"
  ],
  "OCCURRED": [
    "发生时间",
    "日時",
    "OCCURRED"
  ],
  "STATE": [
    "状态",
    "状態",
    "STATE"
  ],
  "Status": [
    "状态",
    "状態",
    "Status"
  ],
  "SUBJECT": [
    "对象",
    "対象",
    "SUBJECT"
  ],
  "PROGRESS": [
    "进度",
    "進捗",
    "PROGRESS"
  ],
  "CURRENT STEP": [
    "当前步骤",
    "現在のステップ",
    "CURRENT STEP"
  ],
  "NEXT / UPDATED": [
    "下次执行 / 更新时间",
    "次回実行 / 更新日時",
    "NEXT / UPDATED"
  ],
  "NEXT ATTEMPT": [
    "下次尝试",
    "次の試行",
    "NEXT ATTEMPT"
  ],
  "ATTEMPT": [
    "尝试次数",
    "試行回数",
    "ATTEMPT"
  ],
  "· attempt": [
    "· 尝试",
    "· 試行",
    "· attempt"
  ],
  "ORDERED STEPS": [
    "执行步骤",
    "実行ステップ",
    "ORDERED STEPS"
  ],
  "SAFE ERROR": [
    "错误代码",
    "エラーコード",
    "SAFE ERROR"
  ],
  "PLATFORM REFERENCE": [
    "平台记录编号",
    "プラットフォーム参照ID",
    "PLATFORM REFERENCE"
  ],
  "CREATED": [
    "创建时间",
    "作成日時",
    "CREATED"
  ],
  "UPDATED": [
    "更新时间",
    "更新日時",
    "UPDATED"
  ],
  "FETCHED": [
    "获取时间",
    "取得日時",
    "FETCHED"
  ],
  "SNAPSHOTS": [
    "快照",
    "スナップショット",
    "SNAPSHOTS"
  ],
  "VERSION": [
    "版本",
    "バージョン",
    "VERSION"
  ],
  "MANIFEST SHA256": [
    "清单校验值",
    "マニフェスト検証値",
    "MANIFEST SHA256"
  ],
  "REMOTE TRANSPORTS": [
    "远程连接方式",
    "リモート接続方式",
    "REMOTE TRANSPORTS"
  ],
  "REVIEW EVIDENCE": [
    "审核记录",
    "審査記録",
    "REVIEW EVIDENCE"
  ],
  "COMPATIBILITY": [
    "兼容性",
    "互換性",
    "COMPATIBILITY"
  ],
  "MCP SERVER": [
    "MCP 服务",
    "MCP サーバー",
    "MCP SERVER"
  ],
  "NAME": [
    "名称",
    "名前",
    "NAME"
  ],
  "SLUG": [
    "路径标识",
    "スラッグ",
    "SLUG"
  ],
  "USER ID": [
    "用户编号",
    "ユーザーID",
    "USER ID"
  ],
  "LOGIN NAME": [
    "登录账号",
    "ログイン名",
    "LOGIN NAME"
  ],
  "DISPLAY NAME": [
    "显示名称",
    "表示名",
    "DISPLAY NAME"
  ],
  "PERSONAL ORGANIZATION": [
    "个人组织",
    "個人組織",
    "PERSONAL ORGANIZATION"
  ],
  "ORGANIZATION SLUG": [
    "组织路径标识",
    "組織スラッグ",
    "ORGANIZATION SLUG"
  ],
  "ACTIVE MEMBERS": [
    "活跃成员",
    "有効なメンバー",
    "ACTIVE MEMBERS"
  ],
  "CREATOR": [
    "创建人",
    "作成者",
    "CREATOR"
  ],
  "LAST SEEN": [
    "最近活动",
    "最終アクティビティ",
    "LAST SEEN"
  ],
  "ACTIVE SESSIONS": [
    "有效会话",
    "有効なセッション",
    "ACTIVE SESSIONS"
  ],
  "SUCCESSFUL LOGINS": [
    "成功登录次数",
    "ログイン成功回数",
    "SUCCESSFUL LOGINS"
  ],
  "MUST CHANGE PASSWORD": [
    "需要修改密码",
    "パスワード変更が必要",
    "MUST CHANGE PASSWORD"
  ],
  "LAST LOGIN": [
    "最近登录",
    "最終ログイン",
    "LAST LOGIN"
  ],
  "CREDENTIAL VERSION": [
    "凭据版本",
    "認証情報バージョン",
    "CREDENTIAL VERSION"
  ],
  "TOTAL ·": [
    "合计 ·",
    "合計 ·",
    "TOTAL ·"
  ],
  "OTHER ACTIVE": [
    "其他有效会话",
    "ほかの有効セッション",
    "OTHER ACTIVE"
  ],
  "CONTROLLED ACTIONS": [
    "管理操作",
    "管理操作",
    "CONTROLLED ACTIONS"
  ],
  "RECENT MFA REQUIRED": [
    "需要近期二次验证",
    "直近の二段階認証が必要",
    "RECENT MFA REQUIRED"
  ],
  "HIGH PRIVILEGE COMMAND": [
    "敏感操作确认",
    "重要操作の確認",
    "HIGH PRIVILEGE COMMAND"
  ],
  "HIGH PRIVILEGE ORGANIZATION COMMAND": [
    "组织操作确认",
    "組織操作の確認",
    "HIGH PRIVILEGE ORGANIZATION COMMAND"
  ],
  "HIGH PRIVILEGE MCP REGISTRY COMMAND": [
    "MCP 审核操作确认",
    "MCP 審査操作の確認",
    "HIGH PRIVILEGE MCP REGISTRY COMMAND"
  ],
  "SYSTEM ADMINISTRATOR SECURITY": [
    "管理员安全操作",
    "管理者のセキュリティ操作",
    "SYSTEM ADMINISTRATOR SECURITY"
  ],
  "SYSTEM ADMINISTRATOR / 01": [
    "系统管理员",
    "システム管理者",
    "SYSTEM ADMINISTRATOR / 01"
  ],
  "AUTHORITY BOUNDARY": [
    "权限说明",
    "権限について",
    "AUTHORITY BOUNDARY"
  ],
  "OWNER-FILTERED": [
    "当前用户关联",
    "このユーザーに関連",
    "OWNER-FILTERED"
  ],
  "REDACTED METADATA": [
    "仅显示必要信息",
    "必要な情報のみ表示",
    "REDACTED METADATA"
  ],
  "Endpoint": [
    "服务地址",
    "接続先",
    "Endpoint"
  ],
  "Auth": [
    "认证方式",
    "認証方式",
    "Auth"
  ],
  "Hint": [
    "密钥提示",
    "キーのヒント",
    "Hint"
  ],
  "Key version": [
    "密钥版本",
    "キーバージョン",
    "Key version"
  ],
  "Owner": [
    "所属用户",
    "所有ユーザー",
    "Owner"
  ],
  "SECRET MATERIAL / REDACTED BY OWNER MODULE": [
    "密钥内容不会展示",
    "キーの内容は表示されません",
    "SECRET MATERIAL / REDACTED BY OWNER MODULE"
  ],
  "CONFIGURED": [
    "已配置",
    "設定済み",
    "CONFIGURED"
  ],
  "NOT CONFIGURED": [
    "未配置",
    "未設定",
    "NOT CONFIGURED"
  ],
  "USER PRIVATE": [
    "用户私有",
    "ユーザー専用",
    "USER PRIVATE"
  ],
  "PROVIDER": [
    "模型提供商",
    "モデルプロバイダー",
    "PROVIDER"
  ],
  "AGENT-KEY": [
    "智能体密钥",
    "エージェントキー",
    "AGENT-KEY"
  ],
  "AGENT_KEY": [
    "智能体密钥",
    "エージェントキー",
    "AGENT_KEY"
  ],
  "No audit evidence": [
    "暂无审计记录",
    "監査記録はありません",
    "No audit evidence"
  ],
  "No credential evidence": [
    "暂无凭据记录",
    "認証情報はありません",
    "No credential evidence"
  ],
  "No organizations": [
    "暂无组织",
    "組織はありません",
    "No organizations"
  ],
  "Close": [
    "关闭",
    "閉じる",
    "Close"
  ],
  "Toggle theme": [
    "切换主题",
    "テーマを切り替え",
    "Toggle theme"
  ],
  "Change language": [
    "切换语言",
    "言語を切り替え",
    "Change language"
  ],
  "Audit action": [
    "审计操作类型",
    "監査操作の種別",
    "Audit action"
  ],
  "Audit target ID": [
    "审计目标编号",
    "監査対象ID",
    "Audit target ID"
  ],
  "Target ID": [
    "目标编号",
    "対象ID",
    "Target ID"
  ],
  "Subject ID": [
    "对象编号",
    "対象ID",
    "Subject ID"
  ],
  "YES": [
    "是",
    "はい",
    "YES"
  ],
  "NO": [
    "否",
    "いいえ",
    "NO"
  ],
  "ADMIN ACCESS / 01": [
    "账号验证",
    "アカウント認証",
    "ADMIN ACCESS / 01"
  ],
  "ADMIN ACCESS / 02": [
    "二次验证",
    "二段階認証",
    "ADMIN ACCESS / 02"
  ],
  "ADMIN CREDENTIAL / REQUIRED": [
    "修改密码",
    "パスワード変更",
    "ADMIN CREDENTIAL / REQUIRED"
  ],
  "CONTROL / PRIVATE": [
    "管理后台",
    "管理画面",
    "CONTROL / PRIVATE"
  ],
  "SEPARATE IDENTITY · SEPARATE SESSION · APPEND-ONLY AUDIT": [
    "仅供系统管理员使用",
    "システム管理者専用",
    "SEPARATE IDENTITY · SEPARATE SESSION · APPEND-ONLY AUDIT"
  ],
  "CLEANUP /": [
    "清理 /",
    "クリーンアップ /",
    "CLEANUP /"
  ],
  "ORGANIZATION /": [
    "组织 /",
    "組織 /",
    "ORGANIZATION /"
  ],
  "PLATFORM USER /": [
    "用户 /",
    "ユーザー /",
    "PLATFORM USER /"
  ],
  "MCP REGISTRY / REVIEW": [
    "MCP 审核",
    "MCP 審査",
    "MCP REGISTRY / REVIEW"
  ],
  "· REV": [
    "· 记录版本",
    "· リビジョン",
    "· REV"
  ],
  "MCP": [
    "MCP",
    "MCP",
    "MCP"
  ],
  "TOTP": [
    "动态验证码",
    "ワンタイムコード",
    "TOTP"
  ],
  "/ TOTP": [
    "/ 动态验证码",
    "/ ワンタイムコード",
    "/ TOTP"
  ],
  "APPEND-ONLY EVIDENCE": [
    "操作记录",
    "操作履歴",
    "Operation history"
  ],
  "usersTitle": [
    "用户管理",
    "ユーザー管理",
    "Users"
  ],
  "usersCopy": [
    "查找用户、查看关联资源并管理账户状态。",
    "ユーザーを検索し、関連リソースとアカウント状態を管理します。",
    "Find users, inspect resources and manage account status."
  ],
  "organizationTitle": [
    "组织管理",
    "組織管理",
    "Organizations"
  ],
  "organizationCopy": [
    "查看组织及成员，管理权限和删除流程。",
    "組織とメンバー、権限、削除処理を管理します。",
    "Manage organizations, members, permissions and deletion."
  ],
  "administratorsTitle": [
    "管理员安全",
    "管理者セキュリティ",
    "Administrator security"
  ],
  "administratorsCopy": [
    "管理当前系统管理员的登录会话与恢复码。",
    "システム管理者のログインセッションとリカバリーコードを管理します。",
    "Manage the system administrator's sessions and recovery codes."
  ],
  "credentialsCopy": [
    "查看凭据配置状态，不展示密钥内容。",
    "認証情報の設定状態を確認します。キーの内容は表示されません。",
    "Inspect credential configuration without revealing secrets."
  ],
  "mcpRegistryCopy": [
    "审核 MCP 服务候选项。同步不会自动安装或执行服务。",
    "MCP サービスの候補を審査します。同期だけで導入・実行は行いません。",
    "Review MCP candidates. Sync does not install or execute services."
  ],
  "cleanupTitle": [
    "删除与清理",
    "削除とクリーンアップ",
    "Deletion and cleanup"
  ],
  "cleanupCopy": [
    "查看删除进度和受阻原因。物理数据清理仍需明确授权。",
    "削除の進捗と阻害原因を確認します。実データの削除には明示的な承認が必要です。",
    "Inspect deletion progress and blockers. Physical cleanup requires explicit authorization."
  ],
  "reconcile": [
    "核查结果",
    "結果を照合",
    "Reconcile outcome"
  ],
  "transferOwner": [
    "转移所有权",
    "所有権を移譲",
    "Transfer ownership"
  ],
  "confirm": [
    "确认操作",
    "操作を確認",
    "Confirm operation"
  ],
  "pendingJobs": [
    "待处理",
    "待機中",
    "Pending"
  ],
  "claimedJobs": [
    "处理中",
    "処理中",
    "Processing"
  ],
  "retryJobs": [
    "待重试",
    "再試行待ち",
    "Retry pending"
  ],
  "blockedJobs": [
    "受阻",
    "ブロック中",
    "Blocked"
  ],
  "completedJobs": [
    "已完成",
    "完了",
    "Completed"
  ],
  "USER_CREATE": [
    "创建用户",
    "ユーザー作成",
    "user create"
  ],
  "USER_UPDATE": [
    "修改用户",
    "ユーザー更新",
    "user update"
  ],
  "USER_SUSPEND": [
    "暂停用户",
    "ユーザー停止",
    "user suspend"
  ],
  "USER_RESTORE": [
    "恢复用户",
    "ユーザー復元",
    "user restore"
  ],
  "USER_PASSWORD_RESET": [
    "重置用户密码",
    "ユーザーパスワード再設定",
    "user password reset"
  ],
  "USER_SESSIONS_REVOKE": [
    "撤销用户会话",
    "ユーザーセッション失効",
    "user sessions revoke"
  ],
  "USER_DELETION_PREFLIGHT": [
    "用户删除预检",
    "ユーザー削除の事前確認",
    "user deletion preflight"
  ],
  "USER_DELETION_REQUEST": [
    "申请删除用户",
    "ユーザー削除申請",
    "user deletion request"
  ],
  "ORGANIZATION_CREATE": [
    "创建组织",
    "組織作成",
    "organization create"
  ],
  "ORGANIZATION_UPDATE": [
    "修改组织",
    "組織更新",
    "organization update"
  ],
  "ORGANIZATION_DELETE": [
    "删除组织",
    "組織削除",
    "organization delete"
  ],
  "ORGANIZATION_MEMBER_ADD": [
    "添加成员",
    "メンバー追加",
    "organization member add"
  ],
  "ORGANIZATION_MEMBER_REMOVE": [
    "移除成员",
    "メンバー削除",
    "organization member remove"
  ],
  "ORGANIZATION_MEMBER_ROLE_UPDATE": [
    "修改成员角色",
    "メンバーロール変更",
    "organization member role update"
  ],
  "ORGANIZATION_OWNER_TRANSFER": [
    "转移组织所有权",
    "組織所有権移譲",
    "organization owner transfer"
  ],
  "ADMIN_LOGIN_PASSWORD": [
    "管理员密码验证",
    "管理者パスワード認証",
    "admin login password"
  ],
  "ADMIN_LOGIN_MFA": [
    "管理员二次验证",
    "管理者二段階認証",
    "admin login mfa"
  ],
  "ADMIN_LOGOUT": [
    "管理员退出",
    "管理者ログアウト",
    "admin logout"
  ],
  "ADMIN_DASHBOARD_READ": [
    "查看总览",
    "概要の閲覧",
    "admin dashboard read"
  ],
  "ADMIN_USERS_READ": [
    "查看用户列表",
    "ユーザー一覧の閲覧",
    "admin users read"
  ],
  "ADMIN_USER_READ": [
    "查看用户详情",
    "ユーザー詳細の閲覧",
    "admin user read"
  ],
  "ADMIN_ORGANIZATIONS_READ": [
    "查看组织列表",
    "組織一覧の閲覧",
    "admin organizations read"
  ],
  "ADMIN_COMMANDS_READ": [
    "查看操作记录",
    "操作履歴の閲覧",
    "admin commands read"
  ],
  "ADMIN_PRESENCE_READ": [
    "查看在线统计",
    "オンライン集計の閲覧",
    "admin presence read"
  ],
  "ADMIN_PRINCIPALS_READ": [
    "查看管理员",
    "管理者の閲覧",
    "admin principals read"
  ],
  "ADMIN_PRINCIPAL_SESSIONS_READ": [
    "查看管理员会话",
    "管理者セッションの閲覧",
    "admin principal sessions read"
  ],
  "ADMIN_BREAK_GLASS_RECOVERY": [
    "应急恢复管理员",
    "管理者の緊急復旧",
    "admin break glass recovery"
  ],
  "ADMIN_PASSWORD_CHANGE": [
    "管理员修改密码",
    "管理者パスワード変更",
    "admin password change"
  ],
  "ADMIN_BOOTSTRAP": [
    "初始化管理员",
    "管理者の初期化",
    "admin bootstrap"
  ],
  "MCP_REGISTRY_SYNC_REQUEST": [
    "同步 MCP 目录",
    "MCP カタログ同期",
    "mcp registry sync request"
  ],
  "MCP_REGISTRY_CANDIDATE_APPROVE": [
    "批准 MCP 候选项",
    "MCP 候補の承認",
    "mcp registry candidate approve"
  ],
  "MCP_REGISTRY_CANDIDATE_REJECT": [
    "拒绝 MCP 候选项",
    "MCP 候補の却下",
    "mcp registry candidate reject"
  ]
}
