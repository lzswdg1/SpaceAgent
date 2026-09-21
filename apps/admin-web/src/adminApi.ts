import type {
  AdminEnvelope,
  AdminIdentity,
  AdminSession,
  PresenceSummary,
  AdministratorCommandResult,
  AdministratorSession,
  AdministratorSummary,
  AgentSummary,
  AuditPage,
  CleanupProjection,
  CommandResult,
  CommandView,
  CleanupJobDetail,
  CleanupJobSummary,
  CleanupOverview,
  CredentialItem,
  CredentialKind,
  DashboardView,
  McpRegistryCandidate,
  McpRegistryCandidateDetail,
  McpRegistrySyncJob,
  OrganizationSummary,
  OrganizationMemberSummary,
  Page,
  UserDetail,
  UserResourceKind,
  UserResourceOverview,
  UserResourceSummary,
  UserSummary,
  WindowRange,
} from './types'

export type Fetcher = (input: RequestInfo | URL, init?: RequestInit) => Promise<Response>

export class AdminApiError extends Error {
  constructor(readonly status: number, readonly code: string, message: string) {
    super(message)
    this.name = 'AdminApiError'
  }
}

const requestId = () => globalThis.crypto?.randomUUID?.() ?? `admin-${Date.now()}-${Math.random().toString(16).slice(2)}`
const csrfCookieName = 'spaceagent_admin_csrf'

export const readAdminCsrfCookie = (cookieHeader: string) => {
  const encoded = cookieHeader.split(';').map((part) => part.trim())
    .find((part) => part.startsWith(`${csrfCookieName}=`))?.slice(csrfCookieName.length + 1)
  if (!encoded) return null
  try { return decodeURIComponent(encoded) } catch { return null }
}

const parseEnvelope = async <T>(response: Response): Promise<T> => {
  let envelope: AdminEnvelope<T> | null = null
  try { envelope = await response.json() as AdminEnvelope<T> } catch { /* bounded generic error below */ }
  if (!response.ok || !envelope?.success) {
    const message = envelope?.message || (response.status === 401 ? 'Administrator authentication is required' : `Request failed (${response.status})`)
    throw new AdminApiError(response.status, envelope?.code || 'ADMIN_REQUEST_FAILED', message)
  }
  return envelope.data
}

export class AdminApi {
  private session: AdminSession | null = null
  private refreshPromise: Promise<AdminSession> | null = null
  private readonly fetcher: Fetcher
  private readonly csrfCookie: () => string | null

  constructor(fetcher?: Fetcher, csrfCookie?: () => string | null) {
    this.fetcher = fetcher ?? window.fetch.bind(window)
    this.csrfCookie = csrfCookie ?? (() => readAdminCsrfCookie(globalThis.document?.cookie ?? ''))
  }

  currentSession() { return this.session }

  async login(loginName: string, password: string) {
    const session = await this.publicRequest<AdminSession>('/admin/v1/auth/login', {
      method: 'POST', body: JSON.stringify({ loginName, password }),
    })
    if (!session?.accessToken || !session.administrator?.id || !session.csrfToken)
      throw new AdminApiError(503, 'ADMIN_LOGIN_CONTRACT_MISMATCH', 'Administrator login service needs updating')
    this.session = session
    return session
  }

  async restoreSession(): Promise<AdminSession | null> {
    if (this.session) return this.session
    const csrfToken = this.csrfCookie()
    if (!csrfToken) return null
    try { return await this.rotateSession(csrfToken) }
    catch (error) {
      if (error instanceof AdminApiError && error.status === 401) return null
      throw error
    }
  }

  async me() { return this.request<AdminIdentity>('/admin/v1/me') }


  async logout() {
    try {
      if (this.session) await this.publicRequest<void>('/admin/v1/auth/logout', {
        method: 'POST', headers: { 'X-Admin-CSRF': this.session.csrfToken },
      })
    } finally {
      this.session = null
      this.refreshPromise = null
    }
  }

  dashboard(window: WindowRange) { return this.request<DashboardView>(`/admin/v1/dashboard?window=${window}`) }

  presence(init: RequestInit = {}) { return this.request<PresenceSummary>('/admin/v1/presence', init) }

  administrators(input: { page: number; pageSize: number; query?: string; status?: string }) {
    const query = new URLSearchParams({ page: String(input.page), pageSize: String(input.pageSize) })
    if (input.query) query.set('query', input.query)
    if (input.status) query.set('status', input.status)
    return this.request<Page<AdministratorSummary>>(`/admin/v1/administrators?${query}`)
  }

  administratorSessions(principalId: string, page = 0, pageSize = 25) {
    const query = new URLSearchParams({ page: String(page), pageSize: String(pageSize) })
    return this.request<Page<AdministratorSession>>(`/admin/v1/administrators/${encodeURIComponent(principalId)}/sessions?${query}`)
  }


  revokeAdministratorSession(principalId: string, sessionId: string, reason: string) {
    return this.localAdminCommand(`/admin/v1/administrators/${encodeURIComponent(principalId)}/sessions/${encodeURIComponent(sessionId)}/revocations`, { reason })
  }

  users(input: { page: number; pageSize: number; query?: string; status?: string; sort?: string }) {
    const query = new URLSearchParams({ page: String(input.page), pageSize: String(input.pageSize), sort: input.sort || 'createdAt' })
    if (input.query) query.set('query', input.query)
    if (input.status) query.set('status', input.status)
    return this.request<Page<UserSummary>>(`/admin/v1/users?${query}`)
  }

  user(userId: string) { return this.request<UserDetail>(`/admin/v1/users/${encodeURIComponent(userId)}`) }

  userProviders(userId: string, page = 0, pageSize = 10) {
    const query = new URLSearchParams({ page: String(page), pageSize: String(pageSize) })
    return this.request<Page<CredentialItem>>(`/admin/v1/users/${encodeURIComponent(userId)}/providers?${query}`)
  }

  userAgents(userId: string, page = 0, pageSize = 10) {
    const query = new URLSearchParams({ page: String(page), pageSize: String(pageSize) })
    return this.request<Page<AgentSummary>>(`/admin/v1/users/${encodeURIComponent(userId)}/agents?${query}`)
  }

  userResourceOverview(userId: string) {
    return this.request<UserResourceOverview>(`/admin/v1/users/${encodeURIComponent(userId)}/resource-overview`)
  }

  userResources(userId: string, kind: UserResourceKind, page = 0, pageSize = 10) {
    const query = new URLSearchParams({ kind, page: String(page), pageSize: String(pageSize) })
    return this.request<Page<UserResourceSummary>>(`/admin/v1/users/${encodeURIComponent(userId)}/resources?${query}`)
  }

  organizations(input: { page: number; pageSize: number; query?: string; status?: string }) {
    const query = new URLSearchParams({ page: String(input.page), pageSize: String(input.pageSize) })
    if (input.query) query.set('query', input.query)
    if (input.status) query.set('status', input.status)
    return this.request<Page<OrganizationSummary>>(`/admin/v1/organizations?${query}`)
  }

  organizationMembers(organizationId: string, input: { page: number; pageSize: number; query?: string; status?: string; role?: string }) {
    const query = new URLSearchParams({ page: String(input.page), pageSize: String(input.pageSize) })
    if (input.query) query.set('query', input.query)
    if (input.status) query.set('status', input.status)
    if (input.role) query.set('role', input.role)
    return this.request<Page<OrganizationMemberSummary>>(`/admin/v1/organizations/${encodeURIComponent(organizationId)}/members?${query}`)
  }

  credentials(kind: CredentialKind, page = 0, pageSize = 25) {
    const query = new URLSearchParams({ kind, page: String(page), pageSize: String(pageSize) })
    return this.request<Page<CredentialItem>>(`/admin/v1/credential-inventory?${query}`)
  }

  auditEvents(input: { page: number; pageSize: number; actor?: string; action?: string; target?: string }) {
    const query = new URLSearchParams({ page: String(input.page), pageSize: String(input.pageSize) })
    if (input.actor) query.set('actor', input.actor)
    if (input.action) query.set('action', input.action)
    if (input.target) query.set('target', input.target)
    return this.request<AuditPage>(`/admin/v1/audit-events?${query}`)
  }

  command(commandId: string) { return this.request<CommandView>(`/admin/v1/commands/${encodeURIComponent(commandId)}`) }
  commands(input: { page: number; pageSize: number; state?: string; operation?: string; target?: string }) {
    const query = new URLSearchParams({ page: String(input.page), pageSize: String(input.pageSize) })
    if (input.state) query.set('state', input.state)
    if (input.operation) query.set('operation', input.operation)
    if (input.target) query.set('target', input.target)
    return this.request<Page<CommandView>>(`/admin/v1/commands?${query}`)
  }
  reconcile(commandId: string) { return this.request<CommandResult>(`/admin/v1/commands/${encodeURIComponent(commandId)}/reconcile`, { method: 'POST' }) }

  cleanupJobs(input: { page: number; pageSize: number; kind?: string; state?: string; query?: string }) {
    const query = new URLSearchParams({ page: String(input.page), pageSize: String(input.pageSize) })
    if (input.kind) query.set('kind', input.kind)
    if (input.state) query.set('state', input.state)
    if (input.query) query.set('query', input.query)
    return this.request<Page<CleanupJobSummary>>(`/admin/v1/cleanup-jobs?${query}`)
  }

  cleanupJobDetail(kind: string, subjectId: string) {
    return this.request<CleanupJobDetail>(`/admin/v1/cleanup-jobs/${encodeURIComponent(kind)}/${encodeURIComponent(subjectId)}`)
  }

  cleanupOverview() { return this.request<CleanupOverview>('/admin/v1/cleanup-jobs/overview') }

  mcpRegistrySyncJobs(input: { page: number; pageSize: number; state?: string }) {
    const query = new URLSearchParams({ page: String(input.page), pageSize: String(input.pageSize) })
    if (input.state) query.set('state', input.state)
    return this.request<Page<McpRegistrySyncJob>>(`/admin/v1/mcp-registry/sync-jobs?${query}`)
  }

  mcpRegistryCandidates(input: { page: number; pageSize: number; state?: string; query?: string }) {
    const query = new URLSearchParams({ page: String(input.page), pageSize: String(input.pageSize) })
    if (input.state) query.set('state', input.state)
    if (input.query) query.set('query', input.query)
    return this.request<Page<McpRegistryCandidate>>(`/admin/v1/mcp-registry/candidates?${query}`)
  }

  mcpRegistryCandidate(candidateId: string) {
    return this.request<McpRegistryCandidateDetail>(`/admin/v1/mcp-registry/candidates/${encodeURIComponent(candidateId)}`)
  }

  requestMcpRegistrySync(reason: string) {
    return this.commandRequest('/admin/v1/mcp-registry/sync-jobs', { reason })
  }

  approveMcpRegistryCandidate(candidateId: string, reason: string) {
    return this.commandRequest(`/admin/v1/mcp-registry/candidates/${encodeURIComponent(candidateId)}/approvals`, { reason })
  }

  rejectMcpRegistryCandidate(candidateId: string, reason: string) {
    return this.commandRequest(`/admin/v1/mcp-registry/candidates/${encodeURIComponent(candidateId)}/rejections`, { reason })
  }

  createUser(input: { loginName: string; displayName: string; organizationName: string; organizationSlug: string; reason: string }) {
    return this.commandRequest('/admin/v1/users', input)
  }

  createOrganization(input: { ownerUserId: string; name: string; slug: string; reason: string }) {
    return this.commandRequest('/admin/v1/organizations', input)
  }

  updateOrganization(organizationId: string, input: { name: string; slug: string; reason: string }) {
    return this.commandRequest(`/admin/v1/organizations/${encodeURIComponent(organizationId)}`, input, 'PATCH')
  }

  deleteOrganization(organizationId: string, reason: string) {
    return this.commandRequest(`/admin/v1/organizations/${encodeURIComponent(organizationId)}/deletion-jobs`, { reason })
  }


  addOrganizationMember(organizationId: string, input: { userId: string; role: string; reason: string }) {
    return this.commandRequest(`/admin/v1/organizations/${encodeURIComponent(organizationId)}/members`, input)
  }

  updateOrganizationMemberRole(organizationId: string, userId: string, input: { role: string; reason: string }) {
    return this.commandRequest(`/admin/v1/organizations/${encodeURIComponent(organizationId)}/members/${encodeURIComponent(userId)}`, input, 'PATCH')
  }

  removeOrganizationMember(organizationId: string, userId: string, reason: string) {
    return this.commandRequest(`/admin/v1/organizations/${encodeURIComponent(organizationId)}/members/${encodeURIComponent(userId)}`, { reason }, 'DELETE')
  }

  transferOrganizationOwnership(organizationId: string, newOwnerUserId: string, reason: string) {
    return this.commandRequest(`/admin/v1/organizations/${encodeURIComponent(organizationId)}/ownership-transfers`, { newOwnerUserId, reason })
  }

  suspendUser(userId: string, reason: string) { return this.commandRequest(`/admin/v1/users/${encodeURIComponent(userId)}/suspend`, { reason }) }
  restoreUser(userId: string, reason: string) { return this.commandRequest(`/admin/v1/users/${encodeURIComponent(userId)}/restore`, { reason }) }
  deletionPreflight(userId: string, reason: string) { return this.commandRequest(`/admin/v1/users/${encodeURIComponent(userId)}/deletion-requests`, { reason }) }
  deleteUser(userId: string, reason: string) { return this.commandRequest(`/admin/v1/users/${encodeURIComponent(userId)}/deletion-jobs`, { reason }) }
  cleanupJob(userId: string) { return this.request<CleanupProjection>(`/admin/v1/users/${encodeURIComponent(userId)}/deletion-jobs/current`) }

  private localAdminCommand(path: string, body: object) {
    return this.request<AdministratorCommandResult>(path, {
      method: 'POST', headers: { 'Idempotency-Key': requestId() }, body: JSON.stringify(body),
    })
  }

  private commandRequest(path: string, body: object, method = 'POST') {
    return this.request<CommandResult>(path, {
      method,
      headers: { 'Idempotency-Key': requestId() },
      body: JSON.stringify(body),
    })
  }

  private async request<T>(path: string, init: RequestInit = {}, retry = true): Promise<T> {
    if (!this.session) throw new AdminApiError(401, 'ADMIN_UNAUTHORIZED', 'Administrator authentication is required')
    try {
      return await this.send<T>(path, init, this.session.accessToken)
    } catch (error) {
      if (!(error instanceof AdminApiError) || error.status !== 401 || !retry || !this.session.csrfToken) throw error
      await this.refresh()
      return this.request<T>(path, init, false)
    }
  }

  private refresh() {
    if (!this.session?.csrfToken) return Promise.reject(new AdminApiError(401, 'ADMIN_SESSION_EXPIRED', 'Administrator session expired'))
    return this.rotateSession(this.session.csrfToken)
  }

  private rotateSession(csrfToken: string) {
    if (this.refreshPromise) return this.refreshPromise
    this.refreshPromise = this.publicRequest<AdminSession>('/admin/v1/auth/refresh', {
      method: 'POST', headers: { 'X-Admin-CSRF': csrfToken },
    }).then((session) => {
      this.session = session
      return session
    }).catch((error) => {
      this.session = null
      throw error
    }).finally(() => { this.refreshPromise = null })
    return this.refreshPromise
  }

  private publicRequest<T>(path: string, init: RequestInit = {}) { return this.send<T>(path, init) }

  private async send<T>(path: string, init: RequestInit, accessToken?: string): Promise<T> {
    const headers = new Headers(init.headers)
    headers.set('Accept', 'application/json')
    headers.set('X-Request-ID', requestId())
    if (init.body !== undefined && !headers.has('Content-Type')) headers.set('Content-Type', 'application/json')
    if (accessToken) headers.set('Authorization', `Bearer ${accessToken}`)
    let response: Response
    try { response = await this.fetcher(path, { ...init, headers, credentials: 'same-origin' }) }
    catch { throw new AdminApiError(0, 'ADMIN_NETWORK_ERROR', 'Administration service is unreachable') }
    return parseEnvelope<T>(response)
  }
}
