import type { AuthenticatedRequest } from '../overview/types'
import type {
  GithubAccount,
  GithubOAuth,
  McpConnection,
  McpConnectionObservation,
  McpEntry,
  McpInstallation,
  McpOAuth,
  McpOAuthGrant,
  McpQualification,
  McpServerVersion,
} from './types'

export async function loadMcpRegistry(request: AuthenticatedRequest) {
  const [catalog, installations, connections] = await Promise.all([
    request<McpEntry[]>('/api/v1/mcp-marketplace/catalog'),
    request<McpInstallation[]>('/api/v1/mcp-marketplace/installations'),
    request<McpConnection[]>('/api/v1/mcp-marketplace/connections'),
  ])
  return { catalog, installations, connections }
}

export const installMcp = (
  request: AuthenticatedRequest,
  input: { entryId: string; serverVersionId: string; scope: McpInstallation['scope']; displayName: string },
) => request<McpInstallation>('/api/v1/mcp-marketplace/installations', { method: 'POST', body: JSON.stringify(input) })

export const loadMcpVersions = (request: AuthenticatedRequest, entryId: string) => request<McpServerVersion[]>(
  `/api/v1/mcp-marketplace/catalog/${encodeURIComponent(entryId)}/versions`,
)

export const connectMcp = (
  request: AuthenticatedRequest,
  input: { installationId: string; endpointUrl: string; authType: McpEntry['authType']; auth: Record<string, string> },
) => request<McpConnection>('/api/v1/mcp-marketplace/connections', { method: 'POST', body: JSON.stringify(input) })

export const disableMcp = (request: AuthenticatedRequest, installationId: string) => request<McpInstallation>(
  `/api/v1/mcp-marketplace/installations/${encodeURIComponent(installationId)}/disable`, { method: 'POST' },
)

export const revokeMcp = (request: AuthenticatedRequest, connectionId: string) => request<McpConnection>(
  `/api/v1/mcp-marketplace/connections/${encodeURIComponent(connectionId)}/revoke`, { method: 'POST' },
)

export const beginGithubOAuth = (request: AuthenticatedRequest, connectionId: string, redirectUri: string) => request<GithubOAuth>(
  `/api/v1/github-mcp/connections/${encodeURIComponent(connectionId)}/oauth/begin`,
  { method: 'POST', body: JSON.stringify({ redirectUri }) },
)

export const completeGithubOAuth = (request: AuthenticatedRequest, state: string, code: string) => request<GithubAccount>(
  '/api/v1/github-mcp/oauth/complete', { method: 'POST', body: JSON.stringify({ state, code }) },
)

export const beginMcpOAuth = (request: AuthenticatedRequest, connectionId: string, redirectUri: string) => request<McpOAuth>(
  `/api/v1/mcp-marketplace/connections/${encodeURIComponent(connectionId)}/oauth/begin`,
  { method: 'POST', body: JSON.stringify({ redirectUri }) },
)

export const completeMcpOAuth = (
  request: AuthenticatedRequest,
  input: { state: string; code: string | null; error: string | null },
) => request<McpOAuthGrant>('/api/v1/mcp-marketplace/oauth/complete', { method: 'POST', body: JSON.stringify(input) })

export const qualifyMcp = (request: AuthenticatedRequest, connectionId: string) => request<McpQualification>(
  `/api/v1/mcp-marketplace/connections/${encodeURIComponent(connectionId)}/qualification`, { method: 'POST' },
)

export const loadMcpQualification = (request: AuthenticatedRequest, connectionId: string) => request<McpQualification>(
  `/api/v1/mcp-marketplace/connections/${encodeURIComponent(connectionId)}/qualification`,
)

export const loadMcpObservations = (request: AuthenticatedRequest, connectionId: string, limit = 25) => request<McpConnectionObservation[]>(
  `/api/v1/mcp-marketplace/connections/${encodeURIComponent(connectionId)}/health-observations?limit=${Math.max(1, Math.min(limit, 100))}`,
)
