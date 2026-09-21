import { ApiError, apiRequest, type Fetcher } from '../../lib/api'
import { SessionStore } from './sessionStore'
import type {
  AuthenticatedSession,
  AuthToken,
  CurrentUser,
  LoginInput,
  OrganizationSummary,
  RegisterInput,
  SessionPersistence,
} from './types'

export class AuthSessionManager {
  private token: AuthToken | null = null
  private refreshPromise: Promise<AuthToken> | null = null
  private scopeVersion = 0
  private scopeAbort = new AbortController()
  private switching = false

  constructor(
    private readonly fetcher: Fetcher,
    private readonly store: SessionStore,
  ) {}

  async restore(): Promise<AuthenticatedSession | null> {
    const stored = this.store.load()
    if (stored) this.token = stored.token
    try {
      if (!this.token) await this.refresh()
      return await this.loadContext()
    } catch (error) {
      if (this.token === null) return null
      throw error
    }
  }

  login(input: LoginInput, persistence: SessionPersistence): Promise<AuthenticatedSession> {
    return this.issue('/api/v1/web/auth/login', input, persistence)
  }

  register(input: RegisterInput, persistence: SessionPersistence): Promise<AuthenticatedSession> {
    return this.issue('/api/v1/web/auth/register', input, persistence)
  }

  async switchOrganization(organizationId: string): Promise<AuthenticatedSession> {
    if (this.switching) throw new ApiError('Organization switch is in progress', 409, 'ORGANIZATION_SWITCH_IN_PROGRESS')
    this.switching = true
    try {
      if (this.refreshPromise) await this.refreshPromise
      this.invalidateScope()
      const token = await this.authorized<AuthToken>(`/api/v1/web/organizations/${encodeURIComponent(organizationId)}/switch`, { method: 'POST' })
      this.acceptToken(token)
      return await this.loadContext()
    } finally { this.switching = false }
  }

  request<T>(path: string, init: RequestInit = {}): Promise<T> {
    if (this.switching) return Promise.reject(new DOMException('Organization context is changing', 'AbortError'))
    return this.authorized<T>(path, init)
  }

  heartbeat(signal: AbortSignal): Promise<unknown> {
    if (this.switching) return Promise.reject(new DOMException('Organization context is changing', 'AbortError'))
    return this.authorized('/api/v1/presence/heartbeat', {
      method: 'POST', body: JSON.stringify({ clientType: 'WEB' }), signal,
    }, true, false)
  }

  openStream(path: string, init: RequestInit = {}): Promise<Response> {
    if (this.switching) return Promise.reject(new DOMException('Organization context is changing', 'AbortError'))
    return this.authorizedResponse(path, init)
  }

  async logout(): Promise<void> {
    try {
      if (this.token) {
        await this.authorized<void>('/api/v1/web/auth/logout', {
          method: 'POST',
        })
      }
    } finally {
      this.clear()
    }
  }

  private async issue(
    path: string,
    input: LoginInput | RegisterInput,
    _persistence: SessionPersistence,
  ): Promise<AuthenticatedSession> {
    const token = await apiRequest<AuthToken>(this.fetcher, path, {
      method: 'POST',
      body: JSON.stringify(input),
    })
    this.acceptToken(token)
    try {
      return await this.loadContext()
    } catch (error) {
      this.clear()
      throw error
    }
  }

  private async loadContext(): Promise<AuthenticatedSession> {
    const [user, organizations] = await Promise.all([
      this.authorized<CurrentUser>('/api/v1/users/me'),
      this.authorized<OrganizationSummary[]>('/api/v1/organizations'),
    ])
    if (!this.token) throw new ApiError('Session expired', 401, 'SESSION_EXPIRED')
    return { token: this.token, user, organizations }
  }

  private async authorized<T>(path: string, init: RequestInit = {}, retry = true, clearOnRefreshFailure = true): Promise<T> {
    if (!this.token) throw new ApiError('Authentication is required', 401, 'UNAUTHORIZED')
    const scope = this.scopeVersion
    try {
      const result = await apiRequest<T>(this.fetcher, path, this.scoped(init), this.token.token)
      this.requireScope(scope)
      return result
    } catch (error) {
      this.requireScope(scope)
      if (!(error instanceof ApiError) || error.status !== 401 || !retry) throw error
      try {
        await this.refresh()
      } catch (refreshError) {
        if (scope === this.scopeVersion && (clearOnRefreshFailure
          || refreshError instanceof ApiError && [401, 403].includes(refreshError.status))) this.clear()
        throw refreshError
      }
      this.requireScope(scope)
      return this.authorized<T>(path, init, false, clearOnRefreshFailure)
    }
  }

  private async authorizedResponse(path: string, init: RequestInit = {}, retry = true): Promise<Response> {
    if (!this.token) throw new ApiError('Authentication is required', 401, 'UNAUTHORIZED')
    const scope = this.scopeVersion
    const headers = new Headers(init.headers)
    headers.set('Accept', 'text/event-stream')
    if (init.body !== undefined && !headers.has('Content-Type')) headers.set('Content-Type', 'application/json')
    headers.set('Authorization', `Bearer ${this.token.token}`)
    let response: Response
    try {
      response = await this.fetcher(path, { ...this.scoped(init), headers })
    } catch (error) {
      this.requireScope(scope)
      if (error instanceof DOMException && error.name === 'AbortError') throw error
      throw new ApiError(error instanceof Error ? error.message : 'Network request failed', 0, 'NETWORK_ERROR')
    }
    this.requireScope(scope)
    if (response.status === 401 && retry) {
      try {
        await this.refresh()
      } catch (refreshError) {
        if (scope === this.scopeVersion) this.clear()
        throw refreshError
      }
      this.requireScope(scope)
      return this.authorizedResponse(path, init, false)
    }
    if (!response.ok) {
      let message = `Request failed (${response.status})`
      try {
        const body = await response.json() as { message?: string }
        if (body.message) message = body.message
      } catch { /* response may not be JSON */ }
      throw new ApiError(message, response.status)
    }
    return response
  }

  private refresh(): Promise<AuthToken> {
    if (this.refreshPromise) return this.refreshPromise
    const scope = this.scopeVersion
    this.refreshPromise = apiRequest<AuthToken>(this.fetcher, '/api/v1/web/auth/refresh', {
      method: 'POST',
    }).then((token) => {
      this.requireScope(scope)
      if (this.token && (this.token.userId !== token.userId || this.token.tenantId !== token.tenantId)) {
        throw new ApiError('Session context changed; reload the workspace', 401, 'SESSION_SCOPE_CHANGED')
      }
      this.acceptToken(token)
      return token
    }).finally(() => {
      this.refreshPromise = null
    })
    return this.refreshPromise
  }

  private acceptToken(token: AuthToken): void {
    if (this.token && (this.token.userId !== token.userId || this.token.tenantId !== token.tenantId)) this.invalidateScope()
    this.token = token
    this.store.save(token, 'session')
  }

  private clear(): void {
    this.invalidateScope()
    this.token = null
    this.refreshPromise = null
    this.store.clear()
  }

  private invalidateScope(): void {
    this.scopeVersion++
    this.scopeAbort.abort()
    this.scopeAbort = new AbortController()
  }

  private requireScope(version: number): void {
    if (version !== this.scopeVersion) throw new DOMException('Organization context changed', 'AbortError')
  }

  private scoped(init: RequestInit): RequestInit {
    return { ...init, signal: init.signal ? AbortSignal.any([init.signal, this.scopeAbort.signal]) : this.scopeAbort.signal }
  }
}
