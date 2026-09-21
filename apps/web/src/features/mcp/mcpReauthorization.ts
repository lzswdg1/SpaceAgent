import { ApiError } from '../../lib/api'
import type { McpConnection } from './types'

export function oauthActionLabel(connection: Pick<McpConnection, 'state' | 'authType'> | null | undefined, slug: string): string | null {
  if (!connection || connection.authType !== 'OAUTH2' || connection.state === 'REVOKED') return null
  if (connection.state === 'PENDING_AUTH') return 'continueOAuth'
  return slug === 'github' && ['ACTIVE', 'ERROR', 'DEGRADED', 'PENDING_VALIDATION'].includes(connection.state)
    ? 'reauthorizeGithub' : null
}

export const isGithubReauthorizationRequired = (error: unknown): boolean => error instanceof ApiError
  && ['GITHUB_MCP_REAUTH_REQUIRED', 'GITHUB_MCP_REPOSITORY_REAUTH_REQUIRED', 'MCP_CHECKOUT_REAUTH_REQUIRED'].includes(error.code ?? '')
