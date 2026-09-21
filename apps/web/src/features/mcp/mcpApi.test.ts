import { describe, expect, it, vi } from 'vitest'
import type { AuthenticatedRequest } from '../overview/types'
import {
  beginGithubOAuth,
  beginMcpOAuth,
  completeMcpOAuth,
  connectMcp,
  disableMcp,
  installMcp,
  loadMcpObservations,
  loadMcpQualification,
  loadMcpRegistry,
  loadMcpVersions,
  qualifyMcp,
  revokeMcp,
} from './mcpApi'

describe('MCP registry API', () => {
  it('loads catalog, installations and connections together', async () => {
    const request = vi.fn(async () => []) as unknown as AuthenticatedRequest
    await loadMcpRegistry(request)
    expect(request).toHaveBeenCalledWith('/api/v1/mcp-marketplace/catalog')
    expect(request).toHaveBeenCalledWith('/api/v1/mcp-marketplace/installations')
    expect(request).toHaveBeenCalledWith('/api/v1/mcp-marketplace/connections')
  })

  it('encodes MCP and OAuth resource identifiers', async () => {
    const request = vi.fn(async () => ({})) as unknown as AuthenticatedRequest
    await connectMcp(request, { installationId: 'install/1', endpointUrl: 'https://mcp.example.test', authType: 'NONE', auth: {} })
    await disableMcp(request, 'install/1')
    await revokeMcp(request, 'connection/1')
    await beginGithubOAuth(request, 'connection/1', 'https://app.example.test/app/mcp')
    await beginMcpOAuth(request, 'connection/1', 'https://app.example.test/app/mcp')
    await qualifyMcp(request, 'connection/1')
    await loadMcpQualification(request, 'connection/1')
    await loadMcpObservations(request, 'connection/1', 250)
    expect(request).toHaveBeenNthCalledWith(2, '/api/v1/mcp-marketplace/installations/install%2F1/disable', { method: 'POST' })
    expect(request).toHaveBeenNthCalledWith(3, '/api/v1/mcp-marketplace/connections/connection%2F1/revoke', { method: 'POST' })
    expect(request).toHaveBeenNthCalledWith(4, '/api/v1/github-mcp/connections/connection%2F1/oauth/begin', expect.objectContaining({ method: 'POST' }))
    expect(request).toHaveBeenNthCalledWith(5, '/api/v1/mcp-marketplace/connections/connection%2F1/oauth/begin', expect.objectContaining({ method: 'POST' }))
    expect(request).toHaveBeenNthCalledWith(6, '/api/v1/mcp-marketplace/connections/connection%2F1/qualification', { method: 'POST' })
    expect(request).toHaveBeenNthCalledWith(7, '/api/v1/mcp-marketplace/connections/connection%2F1/qualification')
    expect(request).toHaveBeenNthCalledWith(8, '/api/v1/mcp-marketplace/connections/connection%2F1/health-observations?limit=100')
  })

  it('pins installations to a selected version and completes generic OAuth without storing grants', async () => {
    const fetcher = vi.fn(async (_input: RequestInfo | URL, _init?: RequestInit) => ({}))
    const request = fetcher as unknown as AuthenticatedRequest
    await loadMcpVersions(request, 'entry/1')
    await installMcp(request, { entryId: 'entry/1', serverVersionId: 'version/1', scope: 'USER', displayName: 'Filesystem' })
    await completeMcpOAuth(request, { state: 'opaque', code: null, error: 'access_denied' })

    expect(request).toHaveBeenNthCalledWith(1, '/api/v1/mcp-marketplace/catalog/entry%2F1/versions')
    expect(JSON.parse(String((fetcher.mock.calls[1]?.[1] as RequestInit).body))).toEqual({
      entryId: 'entry/1', serverVersionId: 'version/1', scope: 'USER', displayName: 'Filesystem',
    })
    expect(request).toHaveBeenNthCalledWith(3, '/api/v1/mcp-marketplace/oauth/complete', expect.objectContaining({ method: 'POST' }))
  })
})
