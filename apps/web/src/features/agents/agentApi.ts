import type { AuthenticatedRequest } from '../overview/types'
import { normalizedAgentBinding } from './agentBinding'
import type {
  AgentApiKey,
  AgentApiKeyScope,
  AgentConfigurationChange,
  AgentDefinition,
  AgentDraft,
  AgentResources,
  AgentSaveResult,
  CreatedAgentApiKey,
  KnowledgeDocument,
  ModelPool,
  OrganizationAgentAccess,
  RuntimeCapabilityCatalog,
} from './types'

const normalizeCapabilities = (catalog: RuntimeCapabilityCatalog): RuntimeCapabilityCatalog => ({
  tools: (catalog.tools ?? []).map((capability) => ({
    ...capability,
    inputSchema: capability.inputSchema ?? {},
    available: capability.available ?? true,
    readOnly: capability.readOnly ?? true,
    requiresNetwork: capability.requiresNetwork ?? false,
    requiresWorkspace: capability.requiresWorkspace ?? false,
  })),
  skills: (catalog.skills ?? []).map((capability) => ({
    ...capability,
    inputSchema: capability.inputSchema ?? {},
    available: capability.available ?? true,
    readOnly: capability.readOnly ?? true,
    requiresNetwork: capability.requiresNetwork ?? false,
    requiresWorkspace: capability.requiresWorkspace ?? false,
  })),
  sandbox: catalog.sandbox ?? {
    mode: 'IN_PROCESS',
    isolation: 'PROCESS_COMPATIBILITY',
    containerized: false,
    codingCommandAvailable: false,
  },
})

export const emptyAgentDraft = (name = ''): AgentDraft => ({
  name,
  description: '',
  systemPrompt: '',
  modelPoolId: null,
  modelProviderId: null,
  modelId: null,
  temperature: 0.2,
  maxTokens: 4096,
  maxTurns: 40,
  permissionMode: 'ask',
  memoryEnabled: true,
  ragEnabled: false,
  networkEnabled: false,
  knowledgeBaseIds: [],
  enabledToolIds: [],
  skillIds: [],
})

export const toAgentDraft = (agent: AgentDefinition): AgentDraft => ({
  name: agent.name,
  description: agent.description ?? '',
  systemPrompt: agent.systemPrompt ?? '',
  modelPoolId: agent.modelPoolId,
  modelProviderId: agent.modelProviderId,
  modelId: agent.modelId,
  temperature: agent.temperature ?? 0.2,
  maxTokens: agent.maxTokens ?? 4096,
  maxTurns: agent.maxTurns ?? 40,
  permissionMode: agent.permissionMode ?? 'ask',
  memoryEnabled: agent.memoryEnabled ?? true,
  ragEnabled: agent.ragEnabled ?? false,
  networkEnabled: agent.networkEnabled ?? false,
  knowledgeBaseIds: agent.knowledgeBaseIds ?? [],
  enabledToolIds: agent.enabledToolIds ?? [],
  skillIds: agent.skillIds ?? [],
})

export async function loadAgentResources(request: AuthenticatedRequest): Promise<AgentResources> {
  const [access, modelPools, knowledgeDocuments, providers, capabilities] = await Promise.all([
    request<OrganizationAgentAccess[]>('/api/v1/agents/organization'),
    request<ModelPool[]>('/api/v1/model-pools'),
    request<KnowledgeDocument[]>('/api/v1/knowledge/documents'),
    request<Array<{ id: string; name: string; enabled: boolean; connectionStatus: string }>>('/api/v1/model-providers'),
    request<RuntimeCapabilityCatalog>('/api/v1/tooling/capabilities'),
  ])
  const activeProviders = providers.filter((provider) => provider.enabled && provider.connectionStatus === 'ACTIVE')
  const modelGroups = await Promise.all(activeProviders.map(async (provider) => ({
    provider,
    models: await request<Array<{ modelId: string; displayName: string; maxContextTokens: number }>>(
      `/api/v1/model-providers/${encodeURIComponent(provider.id)}/models`,
    ),
  })))
  const modelOptions = modelGroups.flatMap(({ provider, models }) => models.map((model) => ({
    providerId: provider.id,
    providerName: provider.name,
    modelId: model.modelId,
    displayName: model.displayName || model.modelId,
    maxContextTokens: model.maxContextTokens,
  })))
  return {
    agents: access.map(({ agent }) => agent),
    access,
    modelPools,
    knowledgeDocuments,
    modelOptions,
    capabilities: normalizeCapabilities(capabilities),
  }
}

export const listAgentConfigurationChanges = (
  request: AuthenticatedRequest,
  agentId: string,
  offset = 0,
  limit = 50,
) => request<AgentConfigurationChange[]>(
  `/api/v1/agents/${encodeURIComponent(agentId)}/configuration-changes?offset=${offset}&limit=${limit}`,
)

export const decideAgentConfigurationChange = (
  request: AuthenticatedRequest,
  agentId: string,
  requestId: string,
  input: { expectedRevision: number; decision: 'APPROVE' | 'REJECT'; note: string | null },
) => request<AgentConfigurationChange>(
  `/api/v1/agents/${encodeURIComponent(agentId)}/configuration-changes/${encodeURIComponent(requestId)}/decision`,
  { method: 'POST', body: JSON.stringify(input) },
)

export const listAgentApiKeys = (request: AuthenticatedRequest, agentId: string) => request<AgentApiKey[]>(
  `/api/v1/agents/${encodeURIComponent(agentId)}/keys`,
)

export const createAgentApiKey = (
  request: AuthenticatedRequest,
  agentId: string,
  input: { name: string; scopes: AgentApiKeyScope[]; expiresAt: string | null },
) => request<CreatedAgentApiKey>(`/api/v1/agents/${encodeURIComponent(agentId)}/keys`, {
  method: 'POST', body: JSON.stringify(input),
})

export const revokeAgentApiKey = (
  request: AuthenticatedRequest,
  agentId: string,
  keyId: string,
) => request<void>(`/api/v1/agents/${encodeURIComponent(agentId)}/keys/${encodeURIComponent(keyId)}`, {
  method: 'DELETE',
})

export const createAgent = (request: AuthenticatedRequest, draft: AgentDraft) => request<AgentDefinition>(
  '/api/v1/agents',
  { method: 'POST', body: JSON.stringify(normalizedAgentBinding(draft)) },
)

export async function updateAgent(
  request: AuthenticatedRequest,
  agentId: string,
  draft: AgentDraft,
): Promise<AgentSaveResult> {
  const response = await request<AgentDefinition | Omit<AgentSaveResult, 'outcome'> & { outcome: 'PENDING_APPROVAL' }>(
    `/api/v1/agents/${encodeURIComponent(agentId)}`,
    { method: 'PUT', body: JSON.stringify(normalizedAgentBinding(draft)) },
  )
  if ('outcome' in response) return response
  return { outcome: 'APPLIED', agent: response, changeRequest: null }
}

export const deleteAgent = (request: AuthenticatedRequest, agentId: string) => request<void>(
  `/api/v1/agents/${encodeURIComponent(agentId)}`,
  { method: 'DELETE' },
)
