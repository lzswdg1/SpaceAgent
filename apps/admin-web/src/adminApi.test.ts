import { describe, expect, it } from 'vitest'
import { AdminApi, AdminApiError, readAdminCsrfCookie } from './adminApi'
import type { AdminEnvelope, AdminSession } from './types'

const response = <T>(data: T, status = 200) => new Response(JSON.stringify({ success: status < 400, code: status < 400 ? 'OK' : 'FAILED', message: status < 400 ? 'success' : 'failed', data } satisfies AdminEnvelope<T>), { status, headers: { 'Content-Type': 'application/json' } })
const session: AdminSession = { accessToken: 'access', accessTokenExpiresAt: '2026-08-26T12:10:00Z', csrfToken: 'csrf', administrator: { id: 'admin', loginName: 'root', displayName: 'Root', role: 'PLATFORM_SUPER_ADMIN', status: 'ACTIVE', mustChangePassword: false, lastSuccessfulLoginAt: null } }

describe('AdminApi', () => {
  it('recovers the double-submit CSRF value without reading the HttpOnly refresh cookie', () => {
    expect(readAdminCsrfCookie('theme=dark; spaceagent_admin_csrf=csrf%2Dvalue; other=x'))
      .toBe('csrf-value')
    expect(readAdminCsrfCookie('spaceagent_admin_refresh=secret-refresh')).toBeNull()
    expect(readAdminCsrfCookie('spaceagent_admin_csrf=%E0%A4%A')).toBeNull()
  })

  it('restores one memory-only session after a hard reload using the CSRF cookie', async () => {
    let refreshCalls = 0
    const headers: Headers[] = []
    const api = new AdminApi(async (input, init) => {
      expect(String(input)).toBe('/admin/v1/auth/refresh')
      refreshCalls += 1
      headers.push(new Headers(init?.headers))
      await Promise.resolve()
      return response({ ...session, accessToken: 'restored-access', csrfToken: 'rotated-csrf' })
    }, () => 'cookie-csrf')

    const [first, second] = await Promise.all([api.restoreSession(), api.restoreSession()])

    expect(refreshCalls).toBe(1)
    expect(first?.accessToken).toBe('restored-access')
    expect(second?.csrfToken).toBe('rotated-csrf')
    expect(headers[0].get('X-Admin-CSRF')).toBe('cookie-csrf')
    expect(api.currentSession()?.accessToken).toBe('restored-access')
  })

  it('falls back to login when no CSRF cookie exists or the durable session expired', async () => {
    const withoutCookie = new AdminApi(async () => { throw new Error('must not call') }, () => null)
    await expect(withoutCookie.restoreSession()).resolves.toBeNull()

    const expired = new AdminApi(async () => response(null, 401), () => 'stale-csrf')
    await expect(expired.restoreSession()).resolves.toBeNull()
    expect(expired.currentSession()).toBeNull()
  })

  it('performs password-only login with one request and memory-only session', async () => {
    const calls: Array<[string, RequestInit | undefined]> = []
    const api = new AdminApi(async (input, init) => { calls.push([String(input), init]); return response(session) })
    const authenticated = await api.login('root', 'password')
    expect(authenticated.administrator.role).toBe('PLATFORM_SUPER_ADMIN')
    expect(api.currentSession()?.accessToken).toBe('access')
    expect(calls.map(([path]) => path)).toEqual(['/admin/v1/auth/login'])
    expect(JSON.parse(String(calls[0][1]?.body))).toEqual({ loginName: 'root', password: 'password' })
  })

  it('rejects a retired MFA challenge response instead of treating it as a session', async () => {
    const api = new AdminApi(async () => response({ challengeToken: 'old-challenge' }))
    await expect(api.login('root', 'password')).rejects.toMatchObject({ code: 'ADMIN_LOGIN_CONTRACT_MISMATCH' })
    expect(api.currentSession()).toBeNull()
  })

  it('encodes user paths and sends mutation evidence headers', async () => {
    const calls: Array<[string, RequestInit | undefined]> = []
    const api = new AdminApi(async (input, init) => {
      calls.push([String(input), init])
      if (String(input).endsWith('/login')) return response(session)
      return response({ commandId: 'command', operation: 'USER_SUSPEND', targetUserId: 'user/id', state: 'SUCCEEDED', result: {}, safeErrorCode: null, activationToken: null, createdAt: '', updatedAt: '', completedAt: '' })
    })
    await api.login('root', 'password')
    await api.suspendUser('user/id', 'security review')
    expect(calls[1][0]).toBe('/admin/v1/users/user%2Fid/suspend')
    const headers = new Headers(calls[1][1]?.headers)
    expect(headers.get('Authorization')).toBe('Bearer access')
    expect(headers.get('Idempotency-Key')).toBeTruthy()
    expect(JSON.parse(String(calls[1][1]?.body))).toEqual({ reason: 'security review' })
  })

  it('uses bounded server-side pagination and filters', async () => {
    const calls: string[] = []
    const api = new AdminApi(async (input) => {
      calls.push(String(input))
      if (String(input).endsWith('/login')) return response(session)
      if (String(input).endsWith('/cleanup-jobs/overview')) return response({ pending: 0, claimed: 0, retry: 0, blocked: 0, completed: 0, blockers: [] })
      return response({ items: [], page: 2, pageSize: 25, total: 0, generatedAt: '' })
    })
    await api.login('root', 'password')
    await api.users({ page: 2, pageSize: 25, query: 'Example User', status: 'ACTIVE' })
    expect(calls[1]).toContain('page=2')
    expect(calls[1]).toContain('pageSize=25')
    expect(calls[1]).toContain('query=Example+User')
    expect(calls[1]).toContain('status=ACTIVE')
  })

  it('reads nullable heartbeat presence instead of removed governance APIs', async () => {
    const calls: string[] = []
    const api = new AdminApi(async (input) => {
      calls.push(String(input))
      if (String(input).endsWith('/login')) return response(session)
      return response({
        onlineUsers: null, onlineSessions: null, observedSessionCount: 0,
        coverage: 'NO_CLIENT_HEARTBEATS', measuredAt: '2026-08-26T08:00:00Z',
      })
    })
    await api.login('root', 'password')

    const evidence = await api.presence()

    expect(calls[1]).toBe('/admin/v1/presence')
    expect(evidence.onlineUsers).toBeNull()
    expect(evidence.coverage).toBe('NO_CLIENT_HEARTBEATS')
  })

  it('loads global Cleanup and filtered Command queues without client retries', async () => {
    const calls: string[] = []
    const api = new AdminApi(async (input) => {
      calls.push(String(input))
      if (String(input).endsWith('/login')) return response(session)
      if (String(input).includes('/cleanup-jobs/USER/')) return response({ job: { kind: 'USER', subjectId: 'user/id' }, steps: [] })
      return response({ items: [], page: 0, pageSize: 25, total: 0, generatedAt: '' })
    })
    await api.login('root', 'password')
    await api.cleanupJobs({ page: 0, pageSize: 25, kind: 'USER', state: 'BLOCKED', query: 'user/id' })
    await api.cleanupOverview()
    await api.cleanupJobDetail('USER', 'user/id')
    await api.commands({ page: 0, pageSize: 25, state: 'UNKNOWN', operation: 'USER_CREATE', target: 'user/id' })
    expect(calls[1]).toBe('/admin/v1/cleanup-jobs?page=0&pageSize=25&kind=USER&state=BLOCKED&query=user%2Fid')
    expect(calls[2]).toBe('/admin/v1/cleanup-jobs/overview')
    expect(calls[3]).toBe('/admin/v1/cleanup-jobs/USER/user%2Fid')
    expect(calls[4]).toBe('/admin/v1/commands?page=0&pageSize=25&state=UNKNOWN&operation=USER_CREATE&target=user%2Fid')
  })

  it('maps official MCP Registry review reads and commands', async () => {
    const calls: Array<[string, RequestInit | undefined]> = []
    const api = new AdminApi(async (input, init) => {
      calls.push([String(input), init])
      if (String(input).endsWith('/login')) return response(session)
      if (String(input).includes('/candidates/candidate%2Fid') && init?.method !== 'POST') return response({ candidate: { id: 'candidate/id' }, transports: [] })
      if (String(input).includes('/sync-jobs?') || String(input).includes('/candidates?')) return response({ items: [], page: 0, pageSize: 25, total: 0, generatedAt: '' })
      return response({ commandId: 'command', operation: 'MCP_REGISTRY_REVIEW', state: 'SUCCEEDED', result: {}, safeErrorCode: null })
    })
    await api.login('root', 'password')
    await api.mcpRegistrySyncJobs({ page: 1, pageSize: 25, state: 'FAILED' })
    await api.mcpRegistryCandidates({ page: 2, pageSize: 25, state: 'PENDING_REVIEW', query: 'filesystem' })
    await api.mcpRegistryCandidate('candidate/id')
    await api.requestMcpRegistrySync('operator request')
    await api.approveMcpRegistryCandidate('candidate/id', 'reviewed')
    await api.rejectMcpRegistryCandidate('candidate/id', 'unsupported')

    expect(calls[1][0]).toBe('/admin/v1/mcp-registry/sync-jobs?page=1&pageSize=25&state=FAILED')
    expect(calls[2][0]).toBe('/admin/v1/mcp-registry/candidates?page=2&pageSize=25&state=PENDING_REVIEW&query=filesystem')
    expect(calls[3][0]).toBe('/admin/v1/mcp-registry/candidates/candidate%2Fid')
    expect(calls.slice(4).every(([, init]) => new Headers(init?.headers).has('Idempotency-Key'))).toBe(true)
    expect(calls[5][0]).toBe('/admin/v1/mcp-registry/candidates/candidate%2Fid/approvals')
    expect(calls[6][0]).toBe('/admin/v1/mcp-registry/candidates/candidate%2Fid/rejections')
  })

  it('uses owner-filtered Provider, Agent and full resource pages for user detail', async () => {
    const calls: string[] = []
    const api = new AdminApi(async (input) => {
      calls.push(String(input))
      if (String(input).endsWith('/login')) return response(session)
      return response({ items: [], page: 1, pageSize: 10, total: 0, generatedAt: '' })
    })
    await api.login('root', 'password')
    await api.userProviders('user/id', 1, 10)
    await api.userAgents('user/id', 1, 10)
    await api.userResourceOverview('user/id')
    await api.userResources('user/id', 'MODEL_EFFECT', 2, 10)
    expect(calls[1]).toBe('/admin/v1/users/user%2Fid/providers?page=1&pageSize=10')
    expect(calls[2]).toBe('/admin/v1/users/user%2Fid/agents?page=1&pageSize=10')
    expect(calls[3]).toBe('/admin/v1/users/user%2Fid/resource-overview')
    expect(calls[4]).toBe('/admin/v1/users/user%2Fid/resources?kind=MODEL_EFFECT&page=2&pageSize=10')
  })

  it('maps Organization CRUD to audited command routes', async () => {
    const calls: Array<[string, RequestInit | undefined]> = []
    const api = new AdminApi(async (input, init) => {
      calls.push([String(input), init])
      if (String(input).endsWith('/login')) return response(session)
      return response({ commandId: 'command', operation: 'ORGANIZATION_UPDATE', targetUserId: null, targetType: 'ORGANIZATION', targetId: 'org/id', state: 'SUCCEEDED', result: {}, safeErrorCode: null, activationToken: null, createdAt: '', updatedAt: '', completedAt: '' })
    })
    await api.login('root', 'password')
    await api.createOrganization({ ownerUserId: 'owner', name: 'Orbit', slug: 'orbit', reason: 'requested' })
    await api.updateOrganization('org/id', { name: 'Orbit 2', slug: 'orbit-2', reason: 'rename' })
    await api.deleteOrganization('org/id', 'retired')
    expect(calls[1][0]).toBe('/admin/v1/organizations')
    expect(calls[2][0]).toBe('/admin/v1/organizations/org%2Fid')
    expect(calls[2][1]?.method).toBe('PATCH')
    expect(calls[3][0]).toBe('/admin/v1/organizations/org%2Fid/deletion-jobs')
    expect(calls.slice(1).every(([, init]) => new Headers(init?.headers).has('Idempotency-Key'))).toBe(true)
  })

  it('maps bounded Organization member and explicit OWNER workflows', async () => {
    const calls: Array<[string, RequestInit | undefined]> = []
    const api = new AdminApi(async (input, init) => {
      calls.push([String(input), init])
      if (String(input).endsWith('/login')) return response(session)
      if (String(input).includes('/members?')) return response({ items: [], page: 1, pageSize: 20, total: 0, generatedAt: '' })
      return response({ commandId: 'command', operation: 'ORGANIZATION_MEMBER_ADD', targetUserId: null, targetType: 'ORGANIZATION', targetId: 'org/id', state: 'SUCCEEDED', result: {}, safeErrorCode: null, activationToken: null, createdAt: '', updatedAt: '', completedAt: '' })
    })
    await api.login('root', 'password')
    await api.organizationMembers('org/id', { page: 1, pageSize: 20, query: 'Example User', status: 'ACTIVE', role: 'ADMIN' })
    await api.addOrganizationMember('org/id', { userId: 'user/id', role: 'MEMBER', reason: 'add' })
    await api.updateOrganizationMemberRole('org/id', 'user/id', { role: 'ADMIN', reason: 'promote' })
    await api.removeOrganizationMember('org/id', 'user/id', 'remove')
    await api.transferOrganizationOwnership('org/id', 'user/id', 'transfer')

    expect(calls[1][0]).toBe('/admin/v1/organizations/org%2Fid/members?page=1&pageSize=20&query=Example+User&status=ACTIVE&role=ADMIN')
    expect(calls[2][0]).toBe('/admin/v1/organizations/org%2Fid/members')
    expect(calls[3][0]).toBe('/admin/v1/organizations/org%2Fid/members/user%2Fid')
    expect(calls[3][1]?.method).toBe('PATCH')
    expect(calls[4][1]?.method).toBe('DELETE')
    expect(calls[5][0]).toBe('/admin/v1/organizations/org%2Fid/ownership-transfers')
    expect(calls.slice(2).every(([, init]) => new Headers(init?.headers).has('Idempotency-Key'))).toBe(true)
  })

  it('maps singleton sessions without MFA or recovery-code calls', async () => {
    const calls: Array<[string, RequestInit | undefined]> = []
    const api = new AdminApi(async (input, init) => {
      calls.push([String(input), init])
      return String(input).endsWith('/login') ? response(session) : response({ items: [] })
    })
    await api.login('root', 'password')
    await api.administrators({ page: 0, pageSize: 25, query: 'root', status: 'ACTIVE' })
    await api.administratorSessions('admin/id')
    await api.revokeAdministratorSession('admin/id', 'session/id', 'unrecognized')
    expect(calls[1][0]).toBe('/admin/v1/administrators?page=0&pageSize=25&query=root&status=ACTIVE')
    expect(calls[2][0]).toBe('/admin/v1/administrators/admin%2Fid/sessions?page=0&pageSize=25')
    expect(calls[3][0]).toBe('/admin/v1/administrators/admin%2Fid/sessions/session%2Fid/revocations')
    expect(new Headers(calls[3][1]?.headers).has('Idempotency-Key')).toBe(true)
    expect(calls.some(([path]) => path.includes('mfa') || path.includes('recovery-codes'))).toBe(false)
  })

  it('does not hide bounded API failures', async () => {
    const api = new AdminApi(async () => response(null, 401))
    await expect(api.login('root', 'wrong')).rejects.toEqual(expect.objectContaining<Partial<AdminApiError>>({ status: 401, code: 'FAILED' }))
  })

  it('encodes durable deletion and cleanup routes without exposing command identity in URLs', async () => {
    const calls: Array<[string, RequestInit | undefined]> = []
    const api = new AdminApi(async (input, init) => {
      calls.push([String(input), init])
      if (String(input).endsWith('/login')) return response(session)
      if (String(input).endsWith('/current')) return response({ job: { userId: 'user/id', state: 'PENDING' }, steps: [] })
      return response({ commandId: 'command', operation: 'USER_DELETION_REQUEST', targetUserId: 'user/id', state: 'SUCCEEDED', result: {}, safeErrorCode: null, activationToken: null, createdAt: '', updatedAt: '', completedAt: '' })
    })
    await api.login('root', 'password')
    await api.deleteUser('user/id', 'approved erasure')
    await api.cleanupJob('user/id')
    expect(calls[1][0]).toBe('/admin/v1/users/user%2Fid/deletion-jobs')
    expect(calls[2][0]).toBe('/admin/v1/users/user%2Fid/deletion-jobs/current')
    expect(new Headers(calls[1][1]?.headers).get('Idempotency-Key')).toBeTruthy()
  })

  it('uses one refresh for concurrent unauthorized reads and keeps access tokens memory-only', async () => {
    let refreshCalls = 0
    const authorizations: string[] = []
    const api = new AdminApi(async (input, init) => {
      const path = String(input)
      if (path.endsWith('/login')) return response(session)
      if (path.endsWith('/refresh')) {
        refreshCalls += 1
        await Promise.resolve()
        return response({ ...session, accessToken: 'rotated', csrfToken: 'csrf-2' })
      }
      const authorization = new Headers(init?.headers).get('Authorization') || ''
      authorizations.push(authorization)
      if (authorization === 'Bearer access') return response(null, 401)
      return response(session.administrator)
    })
    await api.login('root', 'password')
    await Promise.all([api.me(), api.me()])
    expect(refreshCalls).toBe(1)
    expect(authorizations.filter((value) => value === 'Bearer rotated')).toHaveLength(2)
    expect(api.currentSession()?.accessToken).toBe('rotated')
  })
})
