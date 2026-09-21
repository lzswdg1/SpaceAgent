// Package client 定义了与后端 API 交互时使用的所有数据结构。
//
// 这些结构体（struct）与后端 Java 代码中的 DTO（Data Transfer Object）一一对应。
// Go 使用 struct tag（结构体标签）来指定 JSON 序列化/反序列化时的字段名。
// 例如 `json:"username"` 表示 JSON 中的 "username" 字段映射到 Go 的 Username 字段。
//
// Go 的命名规范：
// - 首字母大写的字段是"导出的"（public），可以被其他包访问
// - 首字母小写的字段是"未导出的"（private），只能在当前包内访问
// - JSON tag 中的 omitempty 表示如果字段为零值（空字符串、0、false），序列化时省略该字段
package client

// ==========================================
// 通用响应包装
// ==========================================

// ApiResponse 是后端所有 API 的统一响应格式。
// 泛型参数 T 表示 Data 字段的具体类型，Go 1.18+ 支持泛型。
// 对应后端 Java 代码中的 ApiResponse<T>。
type ApiResponse[T any] struct {
	Success bool   `json:"success"` // 请求是否成功
	Data    T      `json:"data"`    // 响应数据，类型由 T 决定
	Message string `json:"message"` // 响应消息，通常是 "OK"
}

// ApiError 是后端返回错误时的响应格式。
// 对应后端 Java 代码中的 ApiError。
type ApiError struct {
	Code      string   `json:"code"`      // 错误代码，如 "BUSINESS_ERROR"、"VALIDATION_ERROR"
	Message   string   `json:"message"`   // 人类可读的错误描述
	Details   []string `json:"details"`   // 详细错误信息列表（通常用于字段校验错误）
	Timestamp string   `json:"timestamp"` // 错误发生的时间戳
}

// PageResponse 是分页查询的响应格式。
// 对应后端 Java 代码中的 PageResponse<T>。
type PageResponse[T any] struct {
	Items []T   `json:"items"` // 当前页的数据列表
	Page  int   `json:"page"`  // 当前页码（从 1 开始）
	Size  int   `json:"size"`  // 每页大小
	Total int64 `json:"total"` // 总记录数
}

// ==========================================
// 认证相关（Auth）
// ==========================================

// LoginRequest 是登录请求的参数。
type LoginRequest struct {
	Username string `json:"username"` // 用户名
	Password string `json:"password"` // 密码
}

// RegisterRequest 是注册请求的参数。
type RegisterRequest struct {
	Username    string `json:"username"`    // 用户名（3-32 字符，字母数字下划线）
	Password    string `json:"password"`    // 密码（6-64 字符）
	DisplayName string `json:"displayName"` // 显示名称（可选）
}

// AuthResponse 是登录/注册成功后的响应。
// 包含 JWT 令牌和用户基本信息。
type AuthResponse struct {
	Token            string `json:"token"`            // JWT 令牌，后续请求需要携带
	RefreshToken     string `json:"refreshToken"`     // 单次使用的刷新令牌
	UserID           string `json:"userId"`           // 用户唯一标识（UUID）
	Username         string `json:"username"`         // 用户名
	Role             string `json:"role"`             // 角色："USER" 或 "ADMIN"
	TenantID         string `json:"tenantId"`         // 当前租户/工作区 ID
	TenantRole       string `json:"tenantRole"`       // 当前用户在租户中的角色
	ExpiresAt        string `json:"expiresAt"`        // JWT 过期时间
	RefreshExpiresAt string `json:"refreshExpiresAt"` // 刷新令牌过期时间
}

type RefreshTokenRequest struct {
	RefreshToken string `json:"refreshToken"`
}

// TenantResponse 描述用户有权访问的一个 SaaS 工作区。
type TenantResponse struct {
	ID              string `json:"id"`
	Slug            string `json:"slug"`
	Name            string `json:"name"`
	Type            string `json:"type"`
	Status          string `json:"status"`
	CurrentUserRole string `json:"currentUserRole"`
	CreatedBy       string `json:"createdBy"`
	CreatedAt       string `json:"createdAt"`
	UpdatedAt       string `json:"updatedAt"`
}

type TenantMemberResponse struct {
	UserID      string `json:"userId"`
	Username    string `json:"username"`
	DisplayName string `json:"displayName"`
	Role        string `json:"role"`
	Status      string `json:"status"`
	JoinedAt    string `json:"joinedAt"`
}

type CreateTenantRequest struct {
	Name string `json:"name"`
	Slug string `json:"slug,omitempty"`
}

type UpdateTenantRequest struct {
	Name string `json:"name,omitempty"`
	Slug string `json:"slug,omitempty"`
}

type TransferTenantOwnershipRequest struct {
	TargetUserID string `json:"targetUserId"`
}

type CreateTenantInvitationRequest struct {
	Username string `json:"username"`
	Role     string `json:"role"`
}

type TenantInvitationTokenRequest struct {
	Token string `json:"token"`
}

type TenantInvitationResponse struct {
	ID              string `json:"id"`
	TenantID        string `json:"tenantId"`
	TenantName      string `json:"tenantName"`
	InvitedUserID   string `json:"invitedUserId"`
	InvitedUsername string `json:"invitedUsername"`
	Role            string `json:"role"`
	Status          string `json:"status"`
	InvitedBy       string `json:"invitedBy"`
	ExpiresAt       string `json:"expiresAt"`
	CreatedAt       string `json:"createdAt"`
	RespondedAt     string `json:"respondedAt"`
	Token           string `json:"token"`
}

type TenantAuditResponse struct {
	ID          string         `json:"id"`
	TenantID    string         `json:"tenantId"`
	ActorUserID string         `json:"actorUserId"`
	Action      string         `json:"action"`
	TargetType  string         `json:"targetType"`
	TargetID    string         `json:"targetId"`
	BeforeState map[string]any `json:"beforeState"`
	AfterState  map[string]any `json:"afterState"`
	RequestID   string         `json:"requestId"`
	TraceID     string         `json:"traceId"`
	IPAddress   string         `json:"ipAddress"`
	CreatedAt   string         `json:"createdAt"`
}

type AddTenantMemberRequest struct {
	Username string `json:"username"`
	Role     string `json:"role"`
}

type UpdateTenantMemberRoleRequest struct {
	Role string `json:"role"`
}

// ChangePasswordRequest 是修改密码的请求参数。
type ChangePasswordRequest struct {
	OldPassword string `json:"oldPassword"` // 旧密码
	NewPassword string `json:"newPassword"` // 新密码（6-64 字符）
}

// ==========================================
// 聊天相关（Chat）
// ==========================================

// ChatMessageRequest 是发送聊天消息的请求参数。
type ChatMessageRequest struct {
	// ConversationId 是对话的唯一标识。
	// omitempty 表示如果为空字符串，JSON 中不包含此字段。
	// 不传时后端会自动创建新对话。
	ConversationId string `json:"conversationId,omitempty"`

	// AgentId 指定本轮对话使用的 Agent。
	AgentId string `json:"agentId,omitempty"`

	// Message 是用户发送的消息内容（最多 4000 字符）
	Message string `json:"message"`

	// ModelId 指定使用的模型（可选，不传用默认模型）
	ModelId string `json:"modelId,omitempty"`

	// ImageIds 附带的图片 ID 列表（可选）
	ImageIds []string `json:"imageIds,omitempty"`
}

// ChatMessageResponse 是聊天消息的响应。
// 包含助手的回复以及各种元数据（安全等级、情绪检测、记忆召回等）。
type ChatMessageResponse struct {
	ConversationId      string              `json:"conversationId"`      // 对话 ID
	AssistantMessage    string              `json:"assistantMessage"`    // 助手的回复内容
	MemoryUpdated       bool                `json:"memoryUpdated"`       // 是否更新了记忆
	ReplyMode           string              `json:"replyMode"`           // 回复模式（CHAT_COMPLETIONS/SAFETY_FALLBACK）
	RiskLevel           string              `json:"riskLevel"`           // 安全风险等级：LOW/MEDIUM/HIGH
	RecalledMemoryCount int                 `json:"recalledMemoryCount"` // 召回的记忆数量
	RecalledMemories    []string            `json:"recalledMemories"`    // 召回的记忆内容列表
	DetectedMood        string              `json:"detectedMood"`        // 检测到的情绪
	SuggestedFollowUp   string              `json:"suggestedFollowUp"`   // 建议的跟进话题
	InputTokenCount     int                 `json:"inputTokenCount"`
	OutputTokenCount    int                 `json:"outputTokenCount"`
	TotalTokenCount     int                 `json:"totalTokenCount"`
	UsageSource         string              `json:"usageSource"`
	ReasoningContent    string              `json:"reasoningContent"`
	RagUsed             bool                `json:"ragUsed"`
	RetrievedChunkCount int                 `json:"retrievedChunkCount"`
	Citations           []string            `json:"citations"`
	KnowledgeCitations  []KnowledgeCitation `json:"knowledgeCitations"`
}

// KnowledgeCitation 描述一次 RAG 召回的可追溯来源。
type KnowledgeCitation struct {
	ChunkID         string  `json:"chunkId"`
	DocumentID      string  `json:"documentId"`
	DocumentName    string  `json:"documentName"`
	Content         string  `json:"content"`
	SimilarityScore float64 `json:"similarityScore"`
	VectorScore     float64 `json:"vectorScore"`
	LexicalScore    float64 `json:"lexicalScore"`
	ChunkIndex      int     `json:"chunkIndex"`
}

// ChatDoneEvent 对应流式聊天最后一个 done 事件。
type ChatDoneEvent struct {
	ConversationID      string              `json:"conversationId"`
	MemoryUpdated       bool                `json:"memoryUpdated"`
	RagUsed             bool                `json:"ragUsed"`
	RetrievedChunkCount int                 `json:"retrievedChunkCount"`
	Citations           []string            `json:"citations"`
	KnowledgeCitations  []KnowledgeCitation `json:"knowledgeCitations"`
	InputTokenCount     int                 `json:"inputTokenCount"`
	OutputTokenCount    int                 `json:"outputTokenCount"`
	TotalTokenCount     int                 `json:"totalTokenCount"`
	UsageSource         string              `json:"usageSource"`
}

// ConversationViewResponse 是查看对话历史的响应。
type ConversationViewResponse struct {
	ConversationId string                    `json:"conversationId"` // 对话 ID
	UserID         string                    `json:"userId"`         // 用户 ID
	Status         string                    `json:"status"`         // 对话状态（ACTIVE）
	StartedAt      string                    `json:"startedAt"`      // 对话开始时间
	LastMessageAt  string                    `json:"lastMessageAt"`  // 最后消息时间
	Messages       []ConversationMessageItem `json:"messages"`       // 消息列表
}

// ConversationMessageItem 是对话中的单条消息。
type ConversationMessageItem struct {
	Role      string `json:"role"`      // 消息角色：USER 或 ASSISTANT
	Content   string `json:"content"`   // 消息内容
	CreatedAt string `json:"createdAt"` // 创建时间（ISO 8601 格式）
}

type ConversationSummaryItemResponse struct {
	ConversationId string `json:"conversationId"`
	Title          string `json:"title"`
	Status         string `json:"status"`
	StartedAt      string `json:"startedAt"`
	LastMessageAt  string `json:"lastMessageAt"`
}

type ChatTurnUsageResponse struct {
	UsageID        string `json:"usageId"`
	ConversationId string `json:"conversationId"`
	UserID         string `json:"userId"`
	ProviderID     string `json:"providerId"`
	ModelPublicID  string `json:"modelPublicId"`
	ModelID        string `json:"modelId"`
	UsageSource    string `json:"usageSource"`
	InputTokens    int    `json:"inputTokens"`
	OutputTokens   int    `json:"outputTokens"`
	TotalTokens    int    `json:"totalTokens"`
	CreatedAt      string `json:"createdAt"`
}

// ==========================================
// 学习审查相关（Learning Review）
// ==========================================

// LearningReviewItemResponse 是学习审查项的响应。
type LearningReviewItemResponse struct {
	ReviewID        string `json:"reviewId"` // 审查项 ID
	Category        string `json:"category"` // 分类：SUMMARY/EMOTIONAL_PATTERN/SAFETY_REVIEW
	Title           string `json:"title"`    // 标题
	Details         string `json:"details"`  // 详细信息
	Priority        int    `json:"priority"`
	SourceType      string `json:"sourceType"`
	SourceRef       string `json:"sourceRef"`
	Tags            string `json:"tags"`
	Status          string `json:"status"`          // 状态：PENDING/REVIEWED/DISMISSED
	ReviewedBy      string `json:"reviewedBy"`      // 审查人
	ReviewComment   string `json:"reviewComment"`   // 审查评论
	RejectionReason string `json:"rejectionReason"` // 驳回原因
	CreatedAt       string `json:"createdAt"`       // 创建时间
	ReviewedAt      string `json:"reviewedAt"`      // 审查时间
}

// LearningReviewActionRequest 是审查/驳回操作的请求参数。
type LearningReviewActionRequest struct {
	ReviewComment   string `json:"reviewComment,omitempty"`   // 审查评论（可选）
	RejectionReason string `json:"rejectionReason,omitempty"` // 驳回原因（驳回时使用）
}

// LearningReviewActionResponse 是审查/驳回操作的响应。
// 对应后端 LearningReviewActionResponse，与列表项 DTO 不同。
type LearningReviewActionResponse struct {
	ReviewID        string `json:"reviewId"`        // 审查项 ID
	Status          string `json:"status"`          // 新状态
	ReviewedBy      string `json:"reviewedBy"`      // 审查人
	ReviewComment   string `json:"reviewComment"`   // 审查评论
	RejectionReason string `json:"rejectionReason"` // 驳回原因
	ReviewedAt      string `json:"reviewedAt"`      // 审查时间
}

// ==========================================
// 技能提案相关（Skill Proposal）
// ==========================================

// SkillProposalResponse 是技能提案的响应。
type SkillProposalResponse struct {
	ProposalID        string `json:"proposalId"`        // 提案 ID
	Title             string `json:"title"`             // 标题
	Rationale         string `json:"rationale"`         // 理由说明
	DraftInstructions string `json:"draftInstructions"` // 草案指令
	DedupKey          string `json:"dedupKey"`
	ReviewRequired    bool   `json:"reviewRequired"`  // 是否需要审查
	Status            string `json:"status"`          // 状态：PROPOSED/APPROVED/ACTIVE/REJECTED
	ReviewedBy        string `json:"reviewedBy"`      // 审查人
	ReviewComment     string `json:"reviewComment"`   // 审查评论
	RejectionReason   string `json:"rejectionReason"` // 驳回原因
	CreatedAt         string `json:"createdAt"`       // 创建时间
	ReviewedAt        string `json:"reviewedAt"`      // 审查时间
}

// SkillProposalReviewRequest 是审批/拒绝技能提案的请求参数。
type SkillProposalReviewRequest struct {
	ReviewComment   string `json:"reviewComment,omitempty"`   // 审查评论（可选）
	RejectionReason string `json:"rejectionReason,omitempty"` // 拒绝原因（拒绝时使用）
}

// SkillProposalReviewResponse 是审批/拒绝操作的响应。
// 对应后端 SkillProposalReviewResponse，与列表项 DTO 不同。
type SkillProposalReviewResponse struct {
	ProposalID      string `json:"proposalId"`      // 提案 ID
	Status          string `json:"status"`          // 新状态
	ReviewedBy      string `json:"reviewedBy"`      // 审查人
	ReviewComment   string `json:"reviewComment"`   // 审查评论
	RejectionReason string `json:"rejectionReason"` // 拒绝原因
	ReviewedAt      string `json:"reviewedAt"`      // 审查时间
}

// ==========================================
// 活跃技能相关（Active Skills）
// ==========================================

// CompanionSkillResponse 是活跃技能的响应。
type CompanionSkillResponse struct {
	SkillID          string `json:"skillId"`      // 技能 ID
	Name             string `json:"name"`         // 技能名称
	Purpose          string `json:"purpose"`      // 技能用途
	Instructions     string `json:"instructions"` // 技能指令
	Scope            string `json:"scope"`
	Version          int    `json:"version"`
	Tags             string `json:"tags"`
	Priority         int    `json:"priority"`
	Status           string `json:"status"`           // 状态（ACTIVE）
	SourceProposalID string `json:"sourceProposalId"` // 来源提案 ID
	CreatedAt        string `json:"createdAt"`        // 创建时间
}

// ==========================================
// 健康检查（Health）
// ==========================================

// HealthResponse 是健康检查的响应。
type HealthResponse struct {
	Service string `json:"service"` // 服务名称
	Status  string `json:"status"`  // 服务状态（UP/DOWN）
}

// ==========================================
// Agent 相关（Agent CRUD）
// ==========================================

// SessionPolicy 是 Agent 的会话策略配置。
type SessionPolicy struct {
	IdleTimeoutSeconds  int  `json:"idleTimeoutSeconds"`  // 空闲超时秒数
	MaxTokensPerSession int  `json:"maxTokensPerSession"` // 每个会话最大 Token 数
	MaxTurnsPerSession  int  `json:"maxTurnsPerSession"`  // 每个会话最大轮次
	PersistOnIdle       bool `json:"persistOnIdle"`       // 空闲时是否持久化
}

// AgentResponse 是 Agent 配置的响应（对应后端 AgentConfigResponse）。
type AgentResponse struct {
	ID               string         `json:"id"`               // Agent ID
	Name             string         `json:"name"`             // Agent 名称
	SystemPrompt     string         `json:"systemPrompt"`     // 系统提示词
	ModelProviderId  string         `json:"modelProviderId"`  // 模型提供商 ID
	ModelId          string         `json:"modelId"`          // 模型 ID
	Temperature      *float64       `json:"temperature"`      // 温度参数
	MaxTurns         *int           `json:"maxTurns"`         // 最大轮次
	PermissionMode   string         `json:"permissionMode"`   // 权限模式（auto/ask/deny）
	MemoryEnabled    *bool          `json:"memoryEnabled"`    // 是否启用记忆
	RagEnabled       *bool          `json:"ragEnabled"`       // 是否启用 RAG
	NetworkEnabled   *bool          `json:"networkEnabled"`   // 是否启用网络
	KnowledgeBaseIds []string       `json:"knowledgeBaseIds"` // 关联的知识库 ID 列表
	EnabledToolIds   []string       `json:"enabledToolIds"`   // 启用的工具 ID 列表
	SessionPolicy    *SessionPolicy `json:"sessionPolicy"`    // 会话策略
	ConfigVersion    int64          `json:"configVersion"`    // 配置版本号
	CreatedAt        string         `json:"createdAt"`        // 创建时间
	UpdatedAt        string         `json:"updatedAt"`        // 更新时间
}

// AgentCreateRequest 是创建 Agent 的请求参数。
type AgentCreateRequest struct {
	Name             string            `json:"name"`                       // Agent 名称（必填）
	SystemPrompt     string            `json:"systemPrompt,omitempty"`     // 系统提示词
	ModelProviderId  string            `json:"modelProviderId,omitempty"`  // 模型提供商 ID
	ModelId          string            `json:"modelId,omitempty"`          // 模型 ID
	Temperature      *float64          `json:"temperature,omitempty"`      // 温度参数（0.0-2.0）
	MaxTurns         *int              `json:"maxTurns,omitempty"`         // 最大轮次（1-100）
	PermissionMode   string            `json:"permissionMode,omitempty"`   // 权限模式（auto/ask/deny）
	MemoryEnabled    *bool             `json:"memoryEnabled,omitempty"`    // 是否启用记忆
	RagEnabled       *bool             `json:"ragEnabled,omitempty"`       // 是否启用 RAG
	NetworkEnabled   *bool             `json:"networkEnabled,omitempty"`   // 是否启用网络
	KnowledgeBaseIds []string          `json:"knowledgeBaseIds,omitempty"` // 关联的知识库 ID 列表
	EnabledToolIds   []string          `json:"enabledToolIds,omitempty"`   // 启用的工具 ID 列表
	EnvVars          map[string]string `json:"envVars,omitempty"`          // 环境变量
	SessionPolicy    *SessionPolicy    `json:"sessionPolicy,omitempty"`    // 会话策略
}

// AgentUpdateRequest 是更新 Agent 的请求参数（部分更新，仅非空字段生效）。
type AgentUpdateRequest struct {
	Name             string            `json:"name,omitempty"`             // Agent 名称
	SystemPrompt     string            `json:"systemPrompt,omitempty"`     // 系统提示词
	ModelProviderId  string            `json:"modelProviderId,omitempty"`  // 模型提供商 ID
	ModelId          string            `json:"modelId,omitempty"`          // 模型 ID
	Temperature      *float64          `json:"temperature,omitempty"`      // 温度参数
	MaxTurns         *int              `json:"maxTurns,omitempty"`         // 最大轮次
	PermissionMode   string            `json:"permissionMode,omitempty"`   // 权限模式
	MemoryEnabled    *bool             `json:"memoryEnabled,omitempty"`    // 是否启用记忆
	RagEnabled       *bool             `json:"ragEnabled,omitempty"`       // 是否启用 RAG
	NetworkEnabled   *bool             `json:"networkEnabled,omitempty"`   // 是否启用网络
	KnowledgeBaseIds []string          `json:"knowledgeBaseIds,omitempty"` // 关联的知识库 ID 列表
	EnabledToolIds   []string          `json:"enabledToolIds,omitempty"`   // 启用的工具 ID 列表
	EnvVars          map[string]string `json:"envVars,omitempty"`          // 环境变量
	SessionPolicy    *SessionPolicy    `json:"sessionPolicy,omitempty"`    // 会话策略
}

// AgentChatRequest 是 Agent 聊天的请求参数。
type AgentChatRequest struct {
	Message        string `json:"message"`                  // 消息内容（必填）
	ConversationId string `json:"conversationId,omitempty"` // 对话 ID（可选，继续已有对话）
	Stream         bool   `json:"stream"`                   // 是否使用 SSE 流式
}

// AgentCloneRequest 是克隆 Agent 的请求参数。
type AgentCloneRequest struct {
	Name string `json:"name,omitempty"` // 新 Agent 名称（可选）
}

// ==========================================
// Agent 密钥相关（Agent API Keys）
// ==========================================

// AgentKeyResponse 是 Agent API 密钥列表项的响应。
type AgentKeyResponse struct {
	ID         string   `json:"id"`         // 密钥 ID
	Name       string   `json:"name"`       // 密钥名称
	KeyPrefix  string   `json:"keyPrefix"`  // 密钥前缀（用于辨识）
	Scopes     []string `json:"scopes"`     // 作用域列表
	Enabled    bool     `json:"enabled"`    // 是否启用
	CreatedAt  string   `json:"createdAt"`  // 创建时间
	LastUsedAt string   `json:"lastUsedAt"` // 最后使用时间
	ExpiresAt  string   `json:"expiresAt"`  // 过期时间
}

// AgentKeyCreateRequest 是创建 Agent API 密钥的请求参数。
type AgentKeyCreateRequest struct {
	Name      string   `json:"name"`                // 密钥名称（必填）
	Scopes    []string `json:"scopes,omitempty"`    // 作用域列表（默认 CHAT）
	ExpiresAt string   `json:"expiresAt,omitempty"` // 过期时间（可选）
}

// AgentKeyCreateResponse 是创建 Agent API 密钥的响应（包含原始密钥值，仅显示一次）。
type AgentKeyCreateResponse struct {
	RawKey string           `json:"rawKey"` // 原始密钥值（仅此次返回）
	ApiKey AgentKeyResponse `json:"apiKey"` // 密钥元信息
}

// ==========================================
// Agent 定时任务相关（Agent Schedules）
// ==========================================

// AgentScheduleResponse 是 Agent 定时任务的响应。
type AgentScheduleResponse struct {
	ID             string `json:"id"`             // 任务 ID
	AgentId        string `json:"agentId"`        // Agent ID
	CronExpression string `json:"cronExpression"` // cron 表达式
	Message        string `json:"message"`        // 定时发送的消息
	Enabled        bool   `json:"enabled"`        // 是否启用
	Timezone       string `json:"timezone"`       // 时区
	LastExecutedAt string `json:"lastExecutedAt"` // 最后执行时间
	NextFireAt     string `json:"nextFireAt"`     // 下次触发时间
	CreatedBy      string `json:"createdBy"`      // 创建者
	CreatedAt      string `json:"createdAt"`      // 创建时间
	UpdatedAt      string `json:"updatedAt"`      // 更新时间
}

// AgentScheduleCreateRequest 是创建 Agent 定时任务的请求参数。
type AgentScheduleCreateRequest struct {
	CronExpression string `json:"cronExpression"` // cron 表达式（必填）
	Message        string `json:"message"`        // 定时消息内容（必填）
	Timezone       string `json:"timezone"`       // 时区（默认 UTC）
}

// AgentScheduleUpdateRequest 是更新 Agent 定时任务的请求参数。
type AgentScheduleUpdateRequest struct {
	CronExpression string `json:"cronExpression,omitempty"` // cron 表达式
	Message        string `json:"message,omitempty"`        // 定时消息内容
	Timezone       string `json:"timezone,omitempty"`       // 时区
}

// ==========================================
// Agent 用量统计相关（Agent Usage）
// ==========================================

// AgentUsageSummaryResponse 是 Agent 用量汇总的响应。
type AgentUsageSummaryResponse struct {
	TotalInputTokens  int64  `json:"totalInputTokens"`  // 总输入 Token 数
	TotalOutputTokens int64  `json:"totalOutputTokens"` // 总输出 Token 数
	TotalTokens       int64  `json:"totalTokens"`       // 总 Token 数
	TotalCost         string `json:"totalCost"`         // 总费用（BigDecimal 序列化为字符串）
	CallCount         int64  `json:"callCount"`         // 总调用次数
}

// AgentUsageTimeseriesItem 是 Agent 用量时序数据中的单个数据点。
type AgentUsageTimeseriesItem struct {
	Bucket      string `json:"bucket"`      // 时间桶（ISO 8601）
	TotalTokens int64  `json:"totalTokens"` // 该时段总 Token 数
	CallCount   int64  `json:"callCount"`   // 该时段调用次数
	TotalCost   string `json:"totalCost"`   // 该时段总费用
}

// AgentUsageTodayResponse 是 Agent 当日用量的响应。
type AgentUsageTodayResponse struct {
	TokensToday int64 `json:"tokensToday"` // 今日 Token 消耗量
	CallsToday  int64 `json:"callsToday"`  // 今日调用次数
}

// ==========================================
// Agent 模板相关（Agent Templates）
// ==========================================

// AgentTemplateResponse 是 Agent 模板的响应。
type AgentTemplateResponse struct {
	ID               string         `json:"id"`               // 模板 ID
	SourceAgentId    string         `json:"sourceAgentId"`    // 来源 Agent ID
	Name             string         `json:"name"`             // 模板名称
	Description      string         `json:"description"`      // 模板描述
	SystemPrompt     string         `json:"systemPrompt"`     // 系统提示词
	ModelProviderId  string         `json:"modelProviderId"`  // 模型提供商 ID
	ModelId          string         `json:"modelId"`          // 模型 ID
	Temperature      *float64       `json:"temperature"`      // 温度参数
	MaxTurns         *int           `json:"maxTurns"`         // 最大轮次
	PermissionMode   string         `json:"permissionMode"`   // 权限模式
	MemoryEnabled    *bool          `json:"memoryEnabled"`    // 是否启用记忆
	RagEnabled       *bool          `json:"ragEnabled"`       // 是否启用 RAG
	NetworkEnabled   *bool          `json:"networkEnabled"`   // 是否启用网络
	KnowledgeBaseIds []string       `json:"knowledgeBaseIds"` // 关联的知识库 ID 列表
	EnabledToolIds   []string       `json:"enabledToolIds"`   // 启用的工具 ID 列表
	SessionPolicy    *SessionPolicy `json:"sessionPolicy"`    // 会话策略
	CreatedBy        string         `json:"createdBy"`        // 创建者
	CreatedAt        string         `json:"createdAt"`        // 创建时间
}

// AgentTemplateCreateRequest 是从 Agent 创建模板的请求参数。
type AgentTemplateCreateRequest struct {
	AgentId     string `json:"agentId"`     // 来源 Agent ID（必填）
	Name        string `json:"name"`        // 模板名称（必填）
	Description string `json:"description"` // 模板描述
}

// AgentTemplateInstantiateRequest 是从模板实例化 Agent 的请求参数。
type AgentTemplateInstantiateRequest struct {
	Name string `json:"name,omitempty"` // 新 Agent 名称（可选）
}

// ==========================================
// 知识库相关（Knowledge Base）
// ==========================================

// KBDocumentResponse 是知识库文档的响应。
type KBDocumentResponse struct {
	ID          string `json:"documentId"`  // 文档 ID
	OwnerUserID string `json:"ownerUserId"` // 所属用户 ID
	FileName    string `json:"fileName"`    // 文件名
	ContentType string `json:"contentType"` // 内容类型（MIME）
	FileSize    int64  `json:"size"`        // 文件大小（字节）
	ChunkCount  int    `json:"chunkCount"`  // 分块数量
	Status      string `json:"status"`      // 处理状态（PROCESSING/COMPLETED/FAILED）
	ErrorReason string `json:"errorReason"` // 错误原因（失败时）
	CreatedAt   string `json:"createdAt"`   // 创建时间
	ProcessedAt string `json:"processedAt"` // 处理完成时间
}

type KBSearchRequest struct {
	Query               string   `json:"query"`
	KnowledgeBaseIDs    []string `json:"knowledgeBaseIds,omitempty"`
	TopK                int      `json:"topK,omitempty"`
	SimilarityThreshold *float64 `json:"similarityThreshold,omitempty"`
}

type KBSearchHitResponse struct {
	DocumentID   string  `json:"documentId"`
	ChunkID      string  `json:"chunkId"`
	Content      string  `json:"content"`
	Score        float64 `json:"score"`
	VectorScore  float64 `json:"vectorScore"`
	LexicalScore float64 `json:"lexicalScore"`
	Citation     string  `json:"citation"`
	DocumentName string  `json:"documentName"`
	ChunkIndex   int     `json:"chunkIndex"`
}

type KBSearchResponse struct {
	Chunks []KBSearchHitResponse `json:"chunks"`
}

type KBServiceDiagnosticsResponse struct {
	DocumentCount                 int     `json:"documentCount"`
	CompletedDocumentCount        int     `json:"completedDocumentCount"`
	FailedDocumentCount           int     `json:"failedDocumentCount"`
	ProcessingDocumentCount       int     `json:"processingDocumentCount"`
	ChunkCount                    int     `json:"chunkCount"`
	EmbeddingConfigured           bool    `json:"embeddingConfigured"`
	EmbeddingModel                string  `json:"embeddingModel"`
	EmbeddingAPIBaseURL           string  `json:"embeddingApiBaseUrl"`
	ConfiguredEmbeddingDimensions int     `json:"configuredEmbeddingDimensions"`
	StoredEmbeddingDimensions     *int    `json:"storedEmbeddingDimensions"`
	MaxChunkSize                  int     `json:"maxChunkSize"`
	OverlapSize                   int     `json:"overlapSize"`
	DefaultTopK                   int     `json:"defaultTopK"`
	SimilarityThreshold           float64 `json:"similarityThreshold"`
	RerankEnabled                 bool    `json:"rerankEnabled"`
	RerankCandidateMultiplier     int     `json:"rerankCandidateMultiplier"`
	RerankMaxCandidates           int     `json:"rerankMaxCandidates"`
	RerankVectorWeight            float64 `json:"rerankVectorWeight"`
	RerankLexicalWeight           float64 `json:"rerankLexicalWeight"`
	MaxDocumentsPerUser           int     `json:"maxDocumentsPerUser"`
	MaxChunksPerUser              int     `json:"maxChunksPerUser"`
	MaxChunksPerDocument          int     `json:"maxChunksPerDocument"`
	EmbeddingBatchSize            int     `json:"embeddingBatchSize"`
	EmbeddingCacheEnabled         bool    `json:"embeddingCacheEnabled"`
	EmbeddingCacheMaximumSize     int     `json:"embeddingCacheMaximumSize"`
	EmbeddingCacheTTLSeconds      int64   `json:"embeddingCacheTtlSeconds"`
	HNSWEfSearch                  int     `json:"hnswEfSearch"`
	IngestionPersistenceBatchSize int     `json:"ingestionPersistenceBatchSize"`
}

type KBChunkPreviewResponse struct {
	DocumentID          string `json:"documentId"`
	ChunkID             string `json:"chunkId"`
	ChunkIndex          int    `json:"chunkIndex"`
	StartOffset         int    `json:"startOffset"`
	EndOffset           int    `json:"endOffset"`
	ContentLength       int    `json:"contentLength"`
	ContentPreview      string `json:"contentPreview"`
	EmbeddingDimensions *int   `json:"embeddingDimensions"`
}

type KBDocumentDiagnosticsResponse struct {
	Document            KBDocumentResponse       `json:"document"`
	StoredChunkCount    int                      `json:"storedChunkCount"`
	MinChunkLength      *int                     `json:"minChunkLength"`
	MaxChunkLength      *int                     `json:"maxChunkLength"`
	AvgChunkLength      *float64                 `json:"avgChunkLength"`
	EmbeddingDimensions *int                     `json:"embeddingDimensions"`
	SampleChunks        []KBChunkPreviewResponse `json:"sampleChunks"`
}

type ToolDescriptorResponse struct {
	ID               string `json:"id"`
	Name             string `json:"name"`
	Description      string `json:"description"`
	Category         string `json:"category"`
	Configurable     bool   `json:"configurable"`
	EnabledByDefault bool   `json:"enabledByDefault"`
}

// ==========================================
// MCP 配置相关（MCP Server Config）
// ==========================================

// MCPConfigResponse 是 MCP 服务器配置的响应。
type MCPConfigResponse struct {
	ID            string            `json:"id"`            // 配置 ID
	AgentId       string            `json:"agentId"`       // 关联的 Agent ID
	Name          string            `json:"name"`          // 配置名称
	TransportType string            `json:"transportType"` // 传输类型（STDIO/SSE/HTTP）
	Endpoint      string            `json:"endpoint"`      // 端点地址
	Args          []string          `json:"args"`          // 启动参数列表
	Env           map[string]string `json:"env"`           // 环境变量
	Headers       map[string]string `json:"headers"`       // 请求头
	Enabled       bool              `json:"enabled"`       // 是否启用
	CreatedAt     string            `json:"createdAt"`     // 创建时间
	UpdatedAt     string            `json:"updatedAt"`     // 更新时间
}

// MCPConfigCreateRequest 是创建 MCP 服务器配置的请求参数。
type MCPConfigCreateRequest struct {
	AgentId       string            `json:"agentId"`            // 关联的 Agent ID（必填）
	Name          string            `json:"name"`               // 配置名称（必填）
	TransportType string            `json:"transportType"`      // 传输类型（STDIO/SSE/HTTP）
	Endpoint      string            `json:"endpoint,omitempty"` // 端点地址
	Args          []string          `json:"args,omitempty"`     // 启动参数列表
	Env           map[string]string `json:"env,omitempty"`      // 环境变量
	Headers       map[string]string `json:"headers,omitempty"`  // 请求头
	Enabled       *bool             `json:"enabled,omitempty"`  // 是否启用（默认 true）
}

// MCPConfigUpdateRequest 是更新 MCP 服务器配置的请求参数。
type MCPConfigUpdateRequest struct {
	Name          string            `json:"name,omitempty"`          // 配置名称
	TransportType string            `json:"transportType,omitempty"` // 传输类型
	Endpoint      string            `json:"endpoint,omitempty"`      // 端点地址
	Args          []string          `json:"args,omitempty"`          // 启动参数列表
	Env           map[string]string `json:"env,omitempty"`           // 环境变量
	Headers       map[string]string `json:"headers,omitempty"`       // 请求头
	Enabled       *bool             `json:"enabled,omitempty"`       // 是否启用
}

// MCPServerConfigResponse 是当前微服务后端的 MCP 服务配置响应。
type MCPServerConfigResponse struct {
	ID           string            `json:"id"`
	Name         string            `json:"name"`
	Transport    string            `json:"transport"`
	Command      string            `json:"command"`
	Args         []string          `json:"args"`
	Env          map[string]string `json:"env"`
	URL          string            `json:"url"`
	Headers      map[string]string `json:"headers"`
	AllowedTools []string          `json:"allowedTools"`
	Enabled      bool              `json:"enabled"`
	CreatedAt    string            `json:"createdAt"`
	UpdatedAt    string            `json:"updatedAt"`
}

// MCPServerConfigRequest 是当前微服务后端的 MCP 服务配置请求。
type MCPServerConfigRequest struct {
	Name         string            `json:"name"`
	Transport    string            `json:"transport"`
	Command      string            `json:"command,omitempty"`
	Args         []string          `json:"args,omitempty"`
	Env          map[string]string `json:"env,omitempty"`
	URL          string            `json:"url,omitempty"`
	Headers      map[string]string `json:"headers,omitempty"`
	AllowedTools []string          `json:"allowedTools"`
	Enabled      *bool             `json:"enabled,omitempty"`
}

type MCPToolResponse struct {
	ID          string                 `json:"id"`
	ServerID    string                 `json:"serverId"`
	ServerName  string                 `json:"serverName"`
	Name        string                 `json:"name"`
	Description string                 `json:"description"`
	InputSchema map[string]interface{} `json:"inputSchema"`
}

type MCPServerTestResponse struct {
	OK        bool              `json:"ok"`
	Message   string            `json:"message"`
	ToolCount int               `json:"toolCount"`
	Tools     []MCPToolResponse `json:"tools"`
}

type MCPToolCallRequest struct {
	ToolID    string                 `json:"toolId"`
	Arguments map[string]interface{} `json:"arguments,omitempty"`
}

type MCPToolCallResponse struct {
	ServerID string      `json:"serverId"`
	ToolName string      `json:"toolName"`
	Result   interface{} `json:"result"`
}

type MCPToolAuditResponse struct {
	ID           string `json:"id"`
	ServerID     string `json:"serverId"`
	ToolName     string `json:"toolName"`
	Transport    string `json:"transport"`
	Status       string `json:"status"`
	DurationMs   int64  `json:"durationMs"`
	ErrorMessage string `json:"errorMessage"`
	CreatedAt    string `json:"createdAt"`
}

// SourceRepository / GitHub MCP / Local Workspace Bridge contracts.

type LocalWorkspaceBridgeResponse struct {
	ID          string `json:"id"`
	DisplayName string `json:"displayName"`
	DeviceID    string `json:"deviceId"`
	RootHandle  string `json:"rootHandle"`
	TokenPrefix string `json:"tokenPrefix"`
	State       string `json:"state"`
	LastSeenAt  string `json:"lastSeenAt"`
}

type CreatedLocalWorkspaceBridgeResponse struct {
	BridgeToken string                       `json:"bridgeToken"`
	Bridge      LocalWorkspaceBridgeResponse `json:"bridge"`
}

type SourceRepositoryResponse struct {
	ID                   string `json:"id"`
	ProjectID            string `json:"projectId"`
	McpConnectionID      string `json:"mcpConnectionId"`
	McpInvocationID      string `json:"mcpInvocationId"`
	WorkspaceBridgeID    string `json:"workspaceBridgeId"`
	ProviderRepositoryID string `json:"providerRepositoryId"`
	DisplayName          string `json:"displayName"`
	RemoteURL            string `json:"remoteUrl"`
	LocalRootHandle      string `json:"localRootHandle"`
	DefaultBranch        string `json:"defaultBranch"`
	Type                 string `json:"type"`
	State                string `json:"state"`
	Visibility           string `json:"visibility"`
}

type RegisterLocalWorkspaceBridgeRequest struct {
	DisplayName string `json:"displayName"`
	DeviceID    string `json:"deviceId"`
	RootHandle  string `json:"rootHandle"`
}

type ImportLocalRepositoryRequest struct {
	BridgeID      string `json:"bridgeId"`
	RootHandle    string `json:"rootHandle"`
	DisplayName   string `json:"displayName"`
	DefaultBranch string `json:"defaultBranch"`
}

type ImportGithubMcpRepositoryRequest struct {
	ConnectionID         string `json:"connectionId"`
	ProviderRepositoryID string `json:"providerRepositoryId,omitempty"`
	GithubURL            string `json:"githubUrl"`
}

type BridgeWorkspaceCommandResponse struct {
	ID          string `json:"id"`
	WorkspaceID string `json:"workspaceId"`
	BridgeID    string `json:"bridgeId"`
	RootHandle  string `json:"rootHandle"`
	BaseRef     string `json:"baseRef"`
	BranchName  string `json:"branchName"`
	WorktreeKey string `json:"worktreeKey"`
}

type CompleteBridgeWorkspaceRequest struct {
	OpaqueLocator string `json:"opaqueLocator"`
	HeadCommit    string `json:"headCommit"`
	Success       bool   `json:"success"`
	FailureReason string `json:"failureReason,omitempty"`
}

// ==========================================
// 系统监控相关（Admin Monitoring）
// ==========================================

// MonitoringKpiSummary 是系统监控 KPI 汇总。
type MonitoringKpiSummary struct {
	TotalTokens         int64   `json:"totalTokens"`         // 总 Token 数
	TotalCost           float64 `json:"totalCost"`           // 总费用
	TotalSessions       int     `json:"totalSessions"`       // 总会话数
	ActiveUsers         int     `json:"activeUsers"`         // 活跃用户数
	AvgTokensPerSession float64 `json:"avgTokensPerSession"` // 每会话平均 Token 数
}

// MonitoringTimeSeriesBucket 是监控时序数据桶。
type MonitoringTimeSeriesBucket struct {
	Timestamp string             `json:"timestamp"` // 时间戳
	Value     float64            `json:"value"`     // 值
	Breakdown map[string]float64 `json:"breakdown"` // 细分数据
}

// MonitoringOverviewResponse 是系统监控概览的响应。
type MonitoringOverviewResponse struct {
	KpiSummary            MonitoringKpiSummary         `json:"kpiSummary"`            // KPI 汇总
	TokenTimeSeries       []MonitoringTimeSeriesBucket `json:"tokenTimeSeries"`       // Token 时序数据
	CostTimeSeries        []MonitoringTimeSeriesBucket `json:"costTimeSeries"`        // 费用时序数据
	ConcurrencyTimeSeries []MonitoringTimeSeriesBucket `json:"concurrencyTimeSeries"` // 并发时序数据
}

// MonitoringUsageItem 是系统监控用量排名的单个条目。
type MonitoringUsageItem struct {
	GroupKey     string  `json:"groupKey"`     // 分组键
	GroupLabel   string  `json:"groupLabel"`   // 分组标签
	TotalTokens  int64   `json:"totalTokens"`  // 总 Token 数
	TotalCost    float64 `json:"totalCost"`    // 总费用
	RequestCount int     `json:"requestCount"` // 请求次数
}

// MonitoringUsageResponse 是系统监控用量排名的响应。
type MonitoringUsageResponse struct {
	Entries   []MonitoringUsageItem `json:"entries"`   // 排名条目列表
	Truncated bool                  `json:"truncated"` // 是否截断
}

// MonitoringSessionItem 是系统监控活跃会话的单个条目。
type MonitoringSessionItem struct {
	SessionId    string `json:"sessionId"`    // 会话 ID
	UserId       string `json:"userId"`       // 用户 ID
	IsProcessing bool   `json:"isProcessing"` // 是否正在处理
	LastActivity string `json:"lastActivity"` // 最后活动时间
	CurrentSeq   int    `json:"currentSeq"`   // 当前序列号
}

// MonitoringSessionsResponse 是系统监控活跃会话的响应。
type MonitoringSessionsResponse struct {
	Sessions   []MonitoringSessionItem `json:"sessions"`   // 会话列表
	TotalCount int                     `json:"totalCount"` // 总数
}

// MonitoringRealtimeResponse 是系统监控实时指标的响应。
type MonitoringRealtimeResponse struct {
	ActiveCount     int            `json:"activeCount"`     // 活跃连接数
	ProcessingCount int            `json:"processingCount"` // 正在处理的连接数
	PerUserActive   map[string]int `json:"perUserActive"`   // 每用户活跃数
}

// ==========================================
// Agent 监控相关（Agent Monitoring）
// ==========================================

// AgentMonitoringOverviewResponse 是 Agent 监控概览的响应。
type AgentMonitoringOverviewResponse struct {
	AgentCount         int64   `json:"agentCount"`         // Agent 总数
	ActiveSessionCount int     `json:"activeSessionCount"` // 活跃会话数
	TokensToday        int64   `json:"tokensToday"`        // 今日 Token 消耗
	CallsToday         int64   `json:"callsToday"`         // 今日调用次数
	CostToday          string  `json:"costToday"`          // 今日费用
	MessagesPerMinute  float64 `json:"messagesPerMinute"`  // 每分钟消息数
}

// AgentMonitoringTopItem 是 Agent 用量排名的单个条目。
type AgentMonitoringTopItem struct {
	AgentId     string `json:"agentId"`     // Agent ID
	AgentName   string `json:"agentName"`   // Agent 名称
	TotalTokens int64  `json:"totalTokens"` // 总 Token 数
	CallCount   int64  `json:"callCount"`   // 调用次数
	TotalCost   string `json:"totalCost"`   // 总费用
}

// AgentMonitoringRealtimeResponse 是 Agent 实时监控的响应。
type AgentMonitoringRealtimeResponse struct {
	ActiveSessionCount int            `json:"activeSessionCount"` // 活跃会话总数
	SessionsPerAgent   map[string]int `json:"sessionsPerAgent"`   // 每 Agent 会话数
	MessagesPerMinute  float64        `json:"messagesPerMinute"`  // 每分钟消息数
}

// ==========================================
// 记忆相关（Memory）
// ==========================================

// MemoryEntryResponse 是记忆条目的响应。
type MemoryEntryResponse struct {
	ID                   string `json:"id"`                   // 记忆 ID
	UserId               string `json:"userId"`               // 用户 ID
	Content              string `json:"content"`              // 记忆内容
	SourceConversationId string `json:"sourceConversationId"` // 来源对话 ID
	CreatedAt            string `json:"createdAt"`            // 创建时间
}

// ==========================================
// 文件相关（File）
// ==========================================

// FileUploadResponse 是文件上传的响应。
type FileUploadResponse struct {
	FileId       string `json:"fileId"`       // 文件 ID
	OriginalName string `json:"originalName"` // 原始文件名
	ContentType  string `json:"contentType"`  // 内容类型（MIME）
	Size         int64  `json:"size"`         // 文件大小（字节）
}
