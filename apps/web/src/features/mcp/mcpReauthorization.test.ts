import { describe, expect, it } from 'vitest'
import { ApiError } from '../../lib/api'
import { isGithubReauthorizationRequired, oauthActionLabel } from './mcpReauthorization'

describe('GitHub authorization recovery', () => {
  it('permits existing GitHub OAuth connections to restart authorization', () => {
    for (const state of ['ACTIVE', 'DEGRADED', 'ERROR', 'PENDING_VALIDATION']) {
      expect(oauthActionLabel({ state, authType: 'OAUTH2' }, 'github')).toBe('reauthorizeGithub')
    }
    expect(oauthActionLabel({ state: 'PENDING_AUTH', authType: 'OAUTH2' }, 'github')).toBe('continueOAuth')
  })
  it('does not offer OAuth for revoked, missing, or non-OAuth connections', () => {
    expect(oauthActionLabel(null, 'github')).toBeNull()
    expect(oauthActionLabel({ state: 'REVOKED', authType: 'OAUTH2' }, 'github')).toBeNull()
    expect(oauthActionLabel({ state: 'ACTIVE', authType: 'BEARER' }, 'github')).toBeNull()
    expect(oauthActionLabel({ state: 'ACTIVE', authType: 'OAUTH2' }, 'custom')).toBeNull()
  })
  it('shows recovery only for authoritative reauthorization error codes', () => {
    for (const code of ['GITHUB_MCP_REAUTH_REQUIRED', 'GITHUB_MCP_REPOSITORY_REAUTH_REQUIRED', 'MCP_CHECKOUT_REAUTH_REQUIRED']) {
      expect(isGithubReauthorizationRequired(new ApiError('renew', 409, code))).toBe(true)
    }
    expect(isGithubReauthorizationRequired(new ApiError('unknown', 409, 'MCP_CHECKOUT_OUTCOME_UNKNOWN'))).toBe(false)
    expect(isGithubReauthorizationRequired(new Error('GitHub MCP authorization must be renewed'))).toBe(false)
  })
})
