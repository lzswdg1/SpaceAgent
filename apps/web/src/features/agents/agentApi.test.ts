import { describe, expect, it, vi } from 'vitest'
import type { AuthenticatedRequest } from '../overview/types'
import {
  createAgentApiKey,
  decideAgentConfigurationChange,
  deleteAgent,
  emptyAgentDraft,
  listAgentApiKeys,
  listAgentConfigurationChanges,
  loadAgentResources,
  revokeAgentApiKey,
  updateAgent,
} from './agentApi'
import type { AgentDefinition, AgentSaveResult } from './types'

const agent: AgentDefinition = {
  id: 'agent/1',
  ownerId: 'user/1',
  name: 'Release Guardian',
  description: '',
  status: 'ACTIVE',
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
  revision: 3,
  createdAt: '2026-09-12T00:00:00Z',
  updatedAt: '2026-09-12T00:00:00Z',
}

describe('agent API', () => {
  it('loads the Organization Agent access projection and public configuration resources', async () => {
    const request = vi.fn(async (path: string) => {
      if (path === '/api/v1/agents/organization') return [{ agent, writeMode: 'DIRECT' }]
      if (path === '/api/v1/tooling/capabilities') return { tools: [], skills: [] }
      return []
    }) as unknown as AuthenticatedRequest

    const resources = await loadAgentResources(request)

    expect(request).toHaveBeenCalledWith('/api/v1/agents/organization')
    expect(request).not.toHaveBeenCalledWith('/api/v1/agents')
    expect(request).toHaveBeenCalledWith('/api/v1/model-pools')
    expect(request).toHaveBeenCalledWith('/api/v1/knowledge/documents')
    expect(request).toHaveBeenCalledWith('/api/v1/model-providers')
    expect(request).toHaveBeenCalledWith('/api/v1/tooling/capabilities')
    expect(resources.agents).toEqual([agent])
    expect(resources.access).toEqual([{ agent, writeMode: 'DIRECT' }])
  })

  it('preserves backend Tool scope metadata and normalizes the legacy catalog shape', async () => {
    const request = vi.fn(async (path: string) => {
      if (path === '/api/v1/tooling/capabilities') return { tools: [
        { id: 'http_fetch', name: 'HTTP Fetch', description: 'Fetch HTTPS', executionMode: 'BOUNDED_HTTP', inputSchema: { type: 'object' }, available: true, readOnly: true, requiresNetwork: true, requiresWorkspace: false },
        { id: 'echo', name: 'Echo', description: '', executionMode: 'SANDBOX' },
      ], skills: [] }
      return []
    }) as unknown as AuthenticatedRequest

    const resources = await loadAgentResources(request)

    expect(resources.capabilities.tools[0]).toEqual(expect.objectContaining({
      id: 'http_fetch', available: true, readOnly: true, requiresNetwork: true,
    }))
    expect(resources.capabilities.tools[1]).toEqual(expect.objectContaining({
      id: 'echo', available: true, readOnly: true, requiresNetwork: false, requiresWorkspace: false,
    }))
    expect(resources.capabilities.sandbox).toEqual({
      mode: 'IN_PROCESS', isolation: 'PROCESS_COMPATIBILITY',
      containerized: false, codingCommandAvailable: false,
    })
  })

  it('normalizes direct and approval-required saves without calling removed version routes', async () => {
    const pending: AgentSaveResult = {
      outcome: 'PENDING_APPROVAL',
      agent,
      changeRequest: {
        id: 'change/1', approvalId: 'approval/1', tenantId: 'tenant/1', agentId: agent.id,
        agentOwnerId: agent.ownerId, requestedBy: 'user/2', baseAgentRevision: 3,
        baseConfigHash: 'a'.repeat(64), proposalHash: 'b'.repeat(64), proposal: null,
        state: 'PENDING', closedBy: null, decisionNote: null, closedAt: null,
        appliedAgentRevision: null, revision: 1, createdAt: agent.createdAt, updatedAt: agent.updatedAt,
      },
    }
    const request = vi.fn()
      .mockResolvedValueOnce(agent)
      .mockResolvedValueOnce(pending) as unknown as AuthenticatedRequest
    const draft = emptyAgentDraft(agent.name)

    const directResult = await updateAgent(request, agent.id, draft)
    const pendingResult = await updateAgent(request, agent.id, draft)

    expect(directResult).toEqual({ outcome: 'APPLIED', agent, changeRequest: null })
    expect(pendingResult).toEqual(pending)
    expect(request).toHaveBeenCalledTimes(2)
    expect(request).toHaveBeenCalledWith('/api/v1/agents/agent%2F1', {
      method: 'PUT', body: JSON.stringify(draft),
    })
  })

  it('maps configuration-change CAS decisions and Agent deletion with encoded identifiers', async () => {
    const request = vi.fn(async () => []) as unknown as AuthenticatedRequest

    await listAgentConfigurationChanges(request, 'agent/1', 10, 25)
    await decideAgentConfigurationChange(request, 'agent/1', 'change/1', {
      expectedRevision: 4, decision: 'APPROVE', note: 'Looks correct',
    })
    await deleteAgent(request, 'agent/1')

    expect(request).toHaveBeenNthCalledWith(1, '/api/v1/agents/agent%2F1/configuration-changes?offset=10&limit=25')
    expect(request).toHaveBeenNthCalledWith(2, '/api/v1/agents/agent%2F1/configuration-changes/change%2F1/decision', {
      method: 'POST',
      body: JSON.stringify({ expectedRevision: 4, decision: 'APPROVE', note: 'Looks correct' }),
    })
    expect(request).toHaveBeenNthCalledWith(3, '/api/v1/agents/agent%2F1', { method: 'DELETE' })
  })

  it('maps Agent API key metadata, one-time creation and revocation routes', async () => {
    const request = vi.fn(async () => []) as unknown as AuthenticatedRequest
    const input = { name: 'Automation client', scopes: ['CHAT'] as const, expiresAt: null }

    await listAgentApiKeys(request, 'agent/1')
    await createAgentApiKey(request, 'agent/1', { ...input, scopes: [...input.scopes] })
    await revokeAgentApiKey(request, 'agent/1', 'key/1')

    expect(request).toHaveBeenNthCalledWith(1, '/api/v1/agents/agent%2F1/keys')
    expect(request).toHaveBeenNthCalledWith(2, '/api/v1/agents/agent%2F1/keys', {
      method: 'POST', body: JSON.stringify(input),
    })
    expect(request).toHaveBeenNthCalledWith(3, '/api/v1/agents/agent%2F1/keys/key%2F1', {
      method: 'DELETE',
    })
  })

  it('loads autocomplete options only from active Providers', async () => {
    const request = vi.fn(async (path: string) => {
      if (path === '/api/v1/model-providers') return [
        { id: 'active', name: '千问', enabled: true, connectionStatus: 'ACTIVE' },
        { id: 'inactive', name: '旧模型', enabled: true, connectionStatus: 'UNHEALTHY' },
      ]
      if (path === '/api/v1/model-providers/active/models') return [
        { modelId: 'deepseek-v4-pro-0813', displayName: 'DP V4 Pro', maxContextTokens: 1000000 },
      ]
      if (path === '/api/v1/tooling/capabilities') return { tools: [], skills: [] }
      return []
    }) as unknown as AuthenticatedRequest

    const resources = await loadAgentResources(request)

    expect(resources.modelOptions).toEqual([expect.objectContaining({
      providerId: 'active', modelId: 'deepseek-v4-pro-0813',
    })])
    expect(request).not.toHaveBeenCalledWith('/api/v1/model-providers/inactive/models')
  })
})
