export type Language = 'zh' | 'ja' | 'en'
export type WindowRange = '24h' | '7d' | '30d'
export type Section = 'overview' | 'administrators' | 'users' | 'organizations' | 'resources' | 'mcpRegistry' | 'cleanup' | 'credentials' | 'commands' | 'audit'

export type AdminEnvelope<T> = {
  success: boolean
  code: string
  message: string
  data: T
}

export type AdminIdentity = {
  id: string
  loginName: string
  displayName: string
  role: string
  status: string
  mustChangePassword: boolean
  lastSuccessfulLoginAt: string | null
}

export type AdministratorSummary = {
  id: string
  loginName: string
  displayName: string
  status: 'ACTIVE' | 'SUSPENDED' | 'LOCKED' | 'DELETED'
  role: 'PLATFORM_SUPER_ADMIN'
  mustChangePassword: boolean
  credentialVersion: number
  lastSuccessfulLoginAt: string | null
  createdAt: string
  updatedAt: string
  activeSessions: number
  remainingRecoveryCodes: number
}

export type AdministratorSession = {
  id: string
  principalId: string
  active: boolean
  current: boolean
  credentialVersion: number
  authenticatedAt: string
  expiresAt: string
  revokedAt: string | null
  createdAt: string
  lastRotatedAt: string
}

export type ProvisioningMaterial = {
  temporaryPassword: string
  totpSecret: string
  recoveryCodes: string[]
}

export type AdministratorCommandResult = {
  commandId: string
  operation: string
  state: string
  administrator: AdministratorSummary
  provisioning: ProvisioningMaterial | null
  revokedSessions: number
  createdAt: string
  updatedAt: string
  completedAt: string | null
}

export type AdminSession = {
  accessToken: string
  accessTokenExpiresAt: string
  csrfToken: string
  administrator: AdminIdentity
}

export type CountObject = Record<string, number>

export type PlatformOverview = {
  window: WindowRange
  generatedAt: string
  releaseVersion: string
  schemaVersion: number
  platformReadiness: string
  identity: {
    totalUsers: number
    pendingUsers: number
    activeUsers: number
    suspendedUsers: number
    deletionPendingUsers: number
    deletedUsers: number
    registeredInWindow: number
    uniqueSuccessfulLoginsInWindow: number
    recentlyActiveUsers: number
    activeRefreshSessions: number
    activeOrganizations: number
    deletingOrganizations: number
  }
  inference: {
    providers: number
    activeProviders: number
    unhealthyProviders: number
    untestedProviders: number
    disabledProviders: number
    modelPools: number
    activeModelPools: number
    unknownModelCalls?: number | null
  }
  agent: { agents: number; activeAgents: number; activeApiKeys: number }
  project: { projects: number; activeProjects: number; workspaces: number; activeWorkspaces: number }
  conversation: { conversations: number; activeConversations: number; messages: number }
  runtime: {
    runs: number
    activeRuns: number
    completedRuns: number
    failedRuns: number
    cancelledRuns: number
    recoveringRuns: number
    unknownRuns: number | null
  }
  tooling: { mcpConnections: number; activeMcpConnections: number; unknownToolExecutions: number }
}

export type DashboardView = {
  projection: PlatformOverview
  stale: boolean
  staleReasonCode: string | null
  servedAt: string
}

export type PresenceSummary = {
  onlineUsers: number | null
  onlineSessions: number | null
  observedSessionCount: number
  coverage: 'NO_CLIENT_HEARTBEATS' | 'HEARTBEAT_CLIENTS_ONLY'
  measuredAt: string
}

export type Page<T> = {
  items: T[]
  page: number
  pageSize: number
  total: number
  generatedAt: string
}

export type UserSummary = {
  id: string
  primaryOrganizationId: string | null
  loginName: string
  displayName: string | null
  status: string
  mustChangePassword: boolean
  createdAt: string
  updatedAt: string
  lastLoginAt: string | null
  lastSeenAt: string | null
  successfulLoginCount: number
  activeRefreshSessions: number
  membershipCount: number
}

export type UserMembership = {
  organizationId: string
  organizationName: string
  organizationStatus: string
  role: string
  membershipStatus: string
  joinedAt: string
}

export type UserDetail = { user: UserSummary; memberships: UserMembership[] }

export type OrganizationSummary = {
  id: string
  name: string
  slug: string
  status: string
  creatorUserId: string | null
  creatorDisplayName: string | null
  activeMembers: number
  createdAt: string
  updatedAt: string
  deletionRequestedAt: string | null
}

export type OrganizationMemberSummary = {
  organizationId: string
  userId: string
  loginName: string
  displayName: string | null
  userStatus: string
  role: 'OWNER' | 'ADMIN' | 'MEMBER' | 'VIEWER'
  membershipStatus: 'ACTIVE' | 'SUSPENDED'
  joinedAt: string
  updatedAt: string
}

export type AgentSummary = {
  id: string
  organizationId: string
  ownerUserId: string
  name: string
  description: string | null
  status: string
  currentAgentVersionId: string | null
  revision: number
  activeKeyCount: number
  createdAt: string
  updatedAt: string
  archivedAt: string | null
}

export type UserResourceKind = 'MODEL_POOL' | 'PROJECT' | 'TASK' | 'WORKSPACE'
  | 'CONVERSATION' | 'MCP_CONNECTION' | 'KNOWLEDGE_DOCUMENT' | 'MEMORY'
  | 'AUTOMATION' | 'RUN' | 'MODEL_EFFECT' | 'TOOL_EFFECT'

export type UserResourceSummary = {
  kind: UserResourceKind
  id: string
  organizationId: string | null
  parentId: string | null
  displayName: string | null
  state: string
  relation: string | null
  createdAt: string
  updatedAt: string
  safeErrorCode: string | null
  primaryCount: number
  secondaryCount: number
}

export type UserResourceOverview = {
  modelPools: number
  projects: number
  tasks: number
  workspaces: number
  conversations: number
  mcpConnections: number
  knowledgeDocuments: number
  memories: number
  automations: number
  runs: number
  modelEffects: number
  toolEffects: number
}

export type CredentialKind = 'provider' | 'agent-key' | 'mcp'
export type CredentialItem = {
  kind: string
  id: string
  organizationId: string | null
  ownerUserId: string | null
  subjectId: string | null
  name: string
  baseUrl: string | null
  endpointHost: string | null
  authType: string | null
  secretConfigured: boolean
  secretHint: string | null
  secretFingerprint: string | null
  secretKeyVersion: string | null
  status: string
  observedAt: string
  metadata: Record<string, unknown>
}

export type CommandState = 'RECEIVED' | 'DISPATCHING' | 'ACCEPTED' | 'SUCCEEDED' | 'FAILED' | 'UNKNOWN'
export type CommandResult = {
  commandId: string
  operation: string
  targetUserId: string | null
  targetType?: string | null
  targetId?: string | null
  state: CommandState
  result: Record<string, unknown>
  safeErrorCode: string | null
  activationToken: string | null
  createdAt: string
  updatedAt: string
  completedAt: string | null
}

export type CommandView = {
  id: string
  operation: string
  targetType: string
  targetId: string | null
  state: CommandState
  platformReference: string | null
  safeErrorCode: string | null
  createdBy: string
  createdAt: string
  updatedAt: string
  completedAt: string | null
}

export type McpRegistrySyncJob = {
  id: string
  sourceKey: string
  requestedBy: string
  state: string
  updatedSince: string | null
  watermarkAt: string | null
  fetchedCount: number
  snapshotCount: number
  candidateCount: number
  safeErrorCode: string | null
  attempt: number
  createdAt: string
  startedAt: string | null
  updatedAt: string
  completedAt: string | null
}

export type McpRegistryCandidate = {
  id: string
  sourceKey: string
  registryName: string
  registryVersion: string
  reviewState: string
  registryStatus: string
  compatibility: string
  compatibilityReason: string | null
  manifestSha256: string
  publishedEntryId: string | null
  publishedVersionId: string | null
  revision: number
  sourceUpdatedAt: string | null
  createdAt: string
  updatedAt: string
}

export type McpRegistryTransport = {
  endpointUrl: string
  variablesJson: string | null
  headersJson: string | null
  secretHeaders: boolean
}

export type McpRegistryCandidateDetail = {
  candidate: McpRegistryCandidate
  title: string
  description: string
  statusMessage: string | null
  manifestSchemaUri: string | null
  repositoryUri: string | null
  manifestJson: string
  sourcePublishedAt: string | null
  transports: McpRegistryTransport[]
  reviewedBy: string | null
  reviewReason: string | null
  reviewedAt: string | null
}

export type CleanupJobSummary = {
  kind: 'USER' | 'ORGANIZATION'
  subjectId: string
  state: string
  commandId: string | null
  requestedBy: string | null
  retentionNotBefore: string
  nextAttemptAt: string
  attempt: number
  maxAttempts: number
  lastErrorCode: string | null
  lastErrorSummary: string | null
  revision: number
  completedSteps: number
  totalSteps: number
  currentStepKey: string | null
  createdAt: string
  updatedAt: string
  completedAt: string | null
}

export type CleanupStepSummary = {
  stepKey: string
  sequence: number
  state: string
  attempt: number
  lastErrorCode: string | null
  lastErrorSummary: string | null
  createdAt: string
  updatedAt: string
  completedAt: string | null
}

export type CleanupJobDetail = { job: CleanupJobSummary; steps: CleanupStepSummary[] }
export type CleanupOverview = {
  pending: number
  claimed: number
  retry: number
  blocked: number
  completed: number
  blockers: Array<{ code: string; count: number }>
}

export type AuditView = {
  id: string
  actorId: string | null
  sessionId: string | null
  action: string
  targetType: string | null
  targetId: string | null
  requestId: string | null
  commandId: string | null
  inputHash: string | null
  outcome: string
  safeErrorCode: string | null
  occurredAt: string
}

export type AuditPage = { items: AuditView[]; page: number; pageSize: number; total: number; generatedAt: string }

export type CleanupStep = {
  userId: string
  stepKey: string
  sequence: number
  state: string
  attempt: number
  lastErrorCode: string | null
  lastErrorSummary: string | null
  createdAt: string
  updatedAt: string
  completedAt: string | null
}

export type CleanupProjection = {
  job: {
    userId: string
    commandId: string
    requestedBy: string
    state: string
    retentionNotBefore: string | null
    nextAttemptAt: string | null
    attempt: number
    maxAttempts: number
    lastErrorCode: string | null
    revision: number
    createdAt: string
    updatedAt: string
    completedAt: string | null
  }
  steps: CleanupStep[]
}
