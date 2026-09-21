export type AgentDefinition = {
  id: string
  ownerId: string
  name: string
  description: string | null
  status: string
  systemPrompt: string | null
  modelPoolId: string | null
  modelProviderId: string | null
  modelId: string | null
  temperature: number | null
  maxTokens: number | null
  maxTurns: number | null
  permissionMode: 'private' | 'auto' | 'ask' | 'deny' | null
  memoryEnabled: boolean | null
  ragEnabled: boolean | null
  networkEnabled: boolean | null
  knowledgeBaseIds: string[]
  enabledToolIds: string[]
  skillIds: string[]
  revision: number
  createdAt: string
  updatedAt: string
}

export type AgentDraft = Omit<AgentDefinition, 'id' | 'ownerId' | 'status' | 'revision' | 'createdAt' | 'updatedAt'>

export type AgentWriteMode = 'DIRECT' | 'OWNER_APPROVAL_REQUIRED' | 'READ_ONLY'

export type OrganizationAgentAccess = {
  agent: AgentDefinition
  writeMode: AgentWriteMode
}

export type AgentConfigurationProposal = {
  name: string
  description: string | null
  systemPrompt: string | null
  modelPoolId: string | null
  modelProviderId: string | null
  modelId: string | null
  temperature: number
  maxContextTokens: number
  maxOutputTokens: number
  maxTurns: number
  permissionMode: string
  memoryEnabled: boolean
  ragEnabled: boolean
  networkEnabled: boolean
  knowledgeBaseIds: string[]
  enabledToolIds: string[]
  skillIds: string[]
  configHash: string
}

export type AgentConfigurationChangeState =
  | 'PENDING'
  | 'APPLIED'
  | 'REJECTED'
  | 'STALE'
  | 'EXPIRED'
  | 'SUPERSEDED'

export type AgentConfigurationChange = {
  id: string
  approvalId: string
  tenantId: string
  agentId: string
  agentOwnerId: string
  requestedBy: string
  baseAgentRevision: number
  baseConfigHash: string
  proposalHash: string
  proposal: AgentConfigurationProposal | null
  state: AgentConfigurationChangeState
  closedBy: string | null
  decisionNote: string | null
  closedAt: string | null
  appliedAgentRevision: number | null
  revision: number
  createdAt: string
  updatedAt: string
}

export type AgentSaveResult = {
  outcome: 'APPLIED' | 'PENDING_APPROVAL'
  agent: AgentDefinition
  changeRequest: AgentConfigurationChange | null
}

export type ModelPool = {
  id: string
  tenantId: string
  ownerId: string
  name: string
  visibility: string
  routingStrategy: string
  fallbackEnabled: boolean
  status: string
  createdAt: string
  updatedAt: string
}

export type KnowledgeDocument = {
  id: string
  ownerId: string
  name: string
  contentType: string
  storageLocation: string
  status: string
  errorReason: string | null
  createdAt: string
  updatedAt: string
}

export type AgentApiKeyScope = 'CHAT' | 'ADMIN'

export type AgentApiKey = {
  id: string
  agentId: string
  name: string
  keyPrefix: string
  scopes: AgentApiKeyScope[]
  enabled: boolean
  createdAt: string
  lastUsedAt: string | null
  expiresAt: string | null
  revokedAt: string | null
}

export type CreatedAgentApiKey = {
  rawKey: string
  apiKey: AgentApiKey
}

export type AgentResources = {
  agents: AgentDefinition[]
  access: OrganizationAgentAccess[]
  modelPools: ModelPool[]
  knowledgeDocuments: KnowledgeDocument[]
  modelOptions: AgentModelOption[]
  capabilities: RuntimeCapabilityCatalog
}

export type RuntimeCapability = {
  id: string
  name: string
  description: string
  executionMode: string
  inputSchema: Record<string, unknown>
  available: boolean
  readOnly: boolean
  requiresNetwork: boolean
  requiresWorkspace: boolean
}

export type RuntimeCapabilityCatalog = {
  tools: RuntimeCapability[]
  skills: RuntimeCapability[]
  sandbox: SandboxCapability
}

export type SandboxCapability = {
  mode: 'IN_PROCESS' | 'HTTP'
  isolation: 'PROCESS_COMPATIBILITY' | 'OCI_CONTAINER'
  containerized: boolean
  codingCommandAvailable: boolean
}

export type AgentModelOption = {
  providerId: string
  providerName: string
  modelId: string
  displayName: string
  maxContextTokens: number
}
