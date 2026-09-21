import { describe, expect, it, vi } from 'vitest'
import { needsRepositoryPreparation, prepareRepositoryChat, repositoryChatWorkspace, resolveWorkbenchSourceId } from './projectRepositoryChat'
import type { AuthenticatedRequest } from '../chat/types'
import type { Workspace } from './types'

describe('repository chat preparation', () => {
  it('resolves repository context from default directories without guessing among multiple repositories', () => {
    expect(resolveWorkbenchSourceId(null, null, null, [{ id: 'repo' }])).toBe('repo')
    expect(resolveWorkbenchSourceId(null, null, null, [{ id: 'a' }, { id: 'b' }])).toBeNull()
    expect(resolveWorkbenchSourceId('b', 'a', 'a', [{ id: 'a' }, { id: 'b' }])).toBe('b')
    expect(resolveWorkbenchSourceId('old-tenant', null, null, [{ id: 'new-tenant' }])).toBe('new-tenant')
    expect(resolveWorkbenchSourceId(null, 'b', null, [{ id: 'a' }, { id: 'b' }])).toBe('b')
    expect(resolveWorkbenchSourceId(null, null, 'b', [{ id: 'a' }, { id: 'b' }])).toBe('b')
    expect(resolveWorkbenchSourceId(null, null, null, [])).toBeNull()
  })
  const ready = { id: 'w', projectDirectoryId: 'd', taskId: 't', state: 'READY', mode: 'MANAGED_GIT' } as Workspace
  it('requires preparation from a default directory when a repository has already been imported', () => {
    expect(needsRepositoryPreparation({ sourceRepositoryId: null }, true, null)).toBe(true)
    expect(needsRepositoryPreparation({ sourceRepositoryId: 'source' }, false, null)).toBe(true)
    expect(needsRepositoryPreparation({ sourceRepositoryId: null }, false, null)).toBe(false)
    expect(needsRepositoryPreparation({ sourceRepositoryId: 'source' }, true, ready)).toBe(false)
  })
  it('selects only one ready workspace in the current directory and task after reload', () => {
    expect(repositoryChatWorkspace([ready], 'd', 't')?.id).toBe('w')
    expect(repositoryChatWorkspace([ready], 'other-directory', 't')).toBeNull()
    expect(repositoryChatWorkspace([ready], 'd', 'other-task')).toBeNull()
    expect(repositoryChatWorkspace([{ ...ready, state: 'ARCHIVED' }], 'd', 't')).toBeNull()
    expect(repositoryChatWorkspace([ready, { ...ready, id: 'w2' }], 'd', 't')).toBeNull()
  })
  it('stops at pending Agent approval before creating a directory, task or workspace', async () => {
    const request = vi.fn(async (path: string, init?: RequestInit) => {
      if (path === '/api/v1/tooling/capabilities') return { tools: ['file_list', 'file_read'].map((id) => ({ id, available: true })) }
      if (path === '/api/v1/agents/a') return init?.method === 'PATCH' ? { outcome: 'PENDING_APPROVAL' }
        : { id: 'a', enabledToolIds: ['knowledge_search'], ragEnabled: false, knowledgeBaseIds: [] }
      if (path.endsWith('/sources')) return [{ id: 's', state: 'READY', type: 'GITHUB' }]
      if (path.includes('run-handoffs')) return { items: [] }
      return []
    })
    await expect(prepareRepositoryChat(request as unknown as AuthenticatedRequest,
      { projectId: 'p', sourceId: 's', agentId: 'a', conversationId: null }, (key) => key))
      .rejects.toThrow('repositoryToolApprovalRequired')
    const writes = request.mock.calls.filter(([, init]) => init?.method)
    expect(writes).toHaveLength(1)
    expect(JSON.parse(String(writes[0][1]?.body))).toEqual({ enabledToolIds: ['file_list', 'file_read'] })
  })
  it('reuses a prepared conversation and workspace without further mutations', async () => {
    const request = vi.fn(async (path: string) => {
      if (path === '/api/v1/tooling/capabilities') return { tools: ['file_list', 'file_read'].map((id) => ({ id, available: true })) }
      if (path === '/api/v1/agents/a') return { id: 'a', enabledToolIds: ['file_list', 'file_read'] }
      if (path.endsWith('/sources')) return [{ id: 's', state: 'READY', type: 'GITHUB' }]
      if (path.endsWith('/directories')) return [{ id: 'd', sourceRepositoryId: 's', state: 'ACTIVE', relativePath: '.' }]
      if (path.endsWith('/tasks')) return [{ id: 't', state: 'PENDING' }]
      if (path.endsWith('/workspaces')) return [ready]
      if (path.includes('run-handoffs')) return { items: [] }
      if (path.endsWith('/conversations/c')) return { conversationId: 'c', projectId: 'p', projectDirectoryId: 'd', agentId: 'a', activeTaskId: 't', status: 'ACTIVE' }
      throw new Error(path)
    })
    const result = await prepareRepositoryChat(request as unknown as AuthenticatedRequest,
      { projectId: 'p', sourceId: 's', agentId: 'a', conversationId: 'c' }, (key) => key)
    expect(result.workspace.id).toBe('w')
    expect(result.conversation.conversationId).toBe('c')
    expect(request.mock.calls.every((call) => call.length === 1)).toBe(true)
  })
})
