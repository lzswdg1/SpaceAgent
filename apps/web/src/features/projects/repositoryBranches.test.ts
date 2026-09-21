import { describe, expect, it, vi } from 'vitest'
import { repositoryBranchOptions, repositoryBranchLabel } from './repositoryBranches'
import { loadRepositoryBranches } from './workbenchApi'
import { createElement } from 'react'
import { renderToStaticMarkup } from 'react-dom/server'
import { RepositoryWorkbench } from './RepositoryWorkbench'
import type { Workspace } from './types'
import type { AuthenticatedRequest } from '../chat/types'

describe('repository branch visibility', () => {
  it('excludes internal refs including legacy current selections without hiding ordinary feature branches', () => {
    expect(repositoryBranchOptions(['main', 'feature/code', 'spaceagent/task/workspace',
      'refs/heads/spaceagent/old', 'origin/spaceagent/old', 'refs/remotes/origin/spaceagent/old',
      'spaceagent-intake', 'main'], 'spaceagent/selected')).toEqual(['main', 'feature/code'])
    expect(repositoryBranchLabel('spaceagent/selected')).toBe('—')
    expect(repositoryBranchLabel('feature/code')).toBe('feature/code')
  })
  it('filters older backend responses at the API client boundary', async () => {
    const request = vi.fn(async () => ['main', 'spaceagent/task/workspace']) as unknown as AuthenticatedRequest
    expect(await loadRepositoryBranches(request, 'project', 'source')).toEqual(['main'])
  })
  it('does not render an internal branch from a historical workspace selection', () => {
    const html=renderToStaticMarkup(createElement(RepositoryWorkbench, {
      request: vi.fn() as unknown as AuthenticatedRequest, projectId:'p', sourceId:'s', sources:[],
      workspace:{id:'w',baseRef:'spaceagent/legacy',branchName:'spaceagent/task/workspace'} as Workspace,
      busy:false,refreshKey:0,onBranch:vi.fn(),onPrepare:vi.fn(),tx:key=>key,
    }))
    expect(html).not.toContain('spaceagent/')
  })
})
