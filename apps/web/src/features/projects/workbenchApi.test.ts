import { describe, expect, it, vi } from 'vitest'
import type { AuthenticatedRequest } from '../chat/types'
import { loadRepositoryBranches, loadRepositoryEnvironment, loadRepositoryFile, loadRepositoryFiles } from './workbenchApi'
import { launchCodingConversation } from './projectCodingConversation'
describe('repository workbench contracts', () => {
  it('encodes repository and workspace paths without exposing server filesystem paths', async () => {
    const request=vi.fn(async()=>[]) as unknown as AuthenticatedRequest
    await loadRepositoryBranches(request,'p/1','s/1')
    await loadRepositoryEnvironment(request,'p/1','w/1')
    await loadRepositoryFiles(request,'p/1','w/1','src/main')
    await loadRepositoryFile(request,'p/1','w/1','src/a #1.ts')
    expect(request).toHaveBeenCalledWith('/api/v1/projects/p%2F1/sources/s%2F1/branches')
    expect(request).toHaveBeenCalledWith('/api/v1/projects/p%2F1/workspaces/w%2F1/file?path=src%2Fa%20%231.ts')
  })
  it('requires a distinct reviewer before any coding mutation', async () => {
    const request=vi.fn() as unknown as AuthenticatedRequest
    await expect(launchCodingConversation(request,{projectId:'p',directoryId:'d',sourceId:'s',conversationId:'c',agentId:'a',reviewerAgentId:'a',baseRef:'main',goal:'implement'},key=>key)).rejects.toThrow('distinctReviewerRequired')
    expect(request).not.toHaveBeenCalled()
  })
  it('submits one idempotent backend startup request instead of a browser-owned write chain', async () => {
    const request=vi.fn(async () => ({root:{id:'root'},child:{id:'child'},plan:{id:'plan'},execution:{id:'execution',state:'RUNNING'}}))
    const input={projectId:'p',directoryId:'d',sourceId:'s',conversationId:'c',agentId:'a',reviewerAgentId:'reviewer',baseRef:'feature/code',goal:'Implement feature',idempotencyKey:'same-intent-key'}
    const result=await launchCodingConversation(request as unknown as AuthenticatedRequest,input,key=>key)
    expect(result.execution.id).toBe('execution')
    expect(request).toHaveBeenCalledTimes(1)
    expect(request).toHaveBeenCalledWith('/api/v1/projects/p/coding-starts',expect.objectContaining({headers:{'Idempotency-Key':'same-intent-key'}}))
    await launchCodingConversation(request as unknown as AuthenticatedRequest,input,key=>key)
    expect(request).toHaveBeenLastCalledWith('/api/v1/projects/p/coding-starts',expect.objectContaining({headers:{'Idempotency-Key':'same-intent-key'}}))
  })
})
