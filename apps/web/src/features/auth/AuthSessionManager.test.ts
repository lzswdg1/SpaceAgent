import { describe, expect, it, vi } from 'vitest'
import { AuthSessionManager } from './AuthSessionManager'
import { SessionStore, type StorageLike } from './sessionStore'
import type { AuthToken } from './types'

class MemoryStorage implements StorageLike {
  private values = new Map<string, string>()
  getItem(key: string) { return this.values.get(key) ?? null }
  setItem(key: string, value: string) { this.values.set(key, value) }
  removeItem(key: string) { this.values.delete(key) }
}

const token = (value: string): AuthToken => ({
  token: value,
  userId: 'user-1',
  username: 'owner@example.com',
  role: 'USER',
  tenantId: 'org-1',
  tenantRole: 'OWNER',
  expiresAt: '2026-08-24T13:00:00Z',
  refreshExpiresAt: '2026-09-23T13:00:00Z',
})

const ok = <T>(data: T) => new Response(JSON.stringify({ success: true, data, message: 'OK' }), {
  status: 200,
  headers: { 'content-type': 'application/json' },
})

const unauthorized = () => new Response(JSON.stringify({
  success: false,
  code: 'UNAUTHORIZED',
  message: 'Authentication is required',
}), { status: 401, headers: { 'content-type': 'application/json' } })

const user = {
  userId: 'user-1', username: 'owner@example.com', displayName: 'Owner', role: 'USER',
  tenantId: 'org-1', tenantRole: 'OWNER', createdAt: '2026-08-24T12:00:00Z',
}
const organizations = [{
  organization: {
    id: 'org-1', name: 'Orbit Lab', slug: 'orbit-lab', creatorUserId: 'user-1', status: 'ACTIVE',
    createdAt: '2026-08-24T12:00:00Z', updatedAt: '2026-08-24T12:00:00Z', deletionRequestedAt: null,
  },
  membership: {
    organizationId: 'org-1', userId: 'user-1', role: 'OWNER', status: 'ACTIVE',
    joinedAt: '2026-08-24T12:00:00Z', updatedAt: '2026-08-24T12:00:00Z',
  },
}]

describe('AuthSessionManager', () => {
  it('uses the current bearer and normal refresh for heartbeat, without client identity fields', async () => {
    const store = new SessionStore(new MemoryStorage(), new MemoryStorage())
    const heartbeats: RequestInit[] = []
    const fetcher = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const path = String(input)
      if (path.endsWith('/auth/login')) return ok(token('a'))
      if (path.endsWith('/users/me')) return ok(user)
      if (path.endsWith('/organizations')) return ok(organizations)
      if (path.endsWith('/auth/refresh')) return ok(token('b'))
      if (path.endsWith('/presence/heartbeat')) {
        heartbeats.push(init!)
        return new Headers(init?.headers).get('Authorization') === 'Bearer a' ? unauthorized() : ok({})
      }
      throw Error(path)
    })
    const manager = new AuthSessionManager(fetcher, store)
    await expect(manager.heartbeat(new AbortController().signal)).rejects.toMatchObject({ status: 401 })
    expect(fetcher).not.toHaveBeenCalled()
    await manager.login({ username: 'owner@example.com', password: 'password' }, 'session')
    await manager.heartbeat(new AbortController().signal)
    await manager.heartbeat(new AbortController().signal)
    expect(heartbeats.map(init => new Headers(init.headers).get('Authorization'))).toEqual(['Bearer a', 'Bearer b', 'Bearer b'])
    for (const init of heartbeats) {
      expect(init.method).toBe('POST')
      expect(JSON.parse(init.body as string)).toEqual({ clientType: 'WEB' })
    }
  })

  it('does not clear the session when background heartbeat refresh has a transient failure', async () => {
    const store = new SessionStore(new MemoryStorage(), new MemoryStorage())
    const fetcher = vi.fn(async (input: RequestInfo | URL) => {
      const path = String(input)
      if (path.endsWith('/auth/login')) return ok(token('a'))
      if (path.endsWith('/users/me')) return ok(user)
      if (path.endsWith('/organizations')) return ok(organizations)
      if (path.endsWith('/presence/heartbeat')) return unauthorized()
      if (path.endsWith('/auth/refresh')) return new Response(JSON.stringify({ success: false, message: 'Unavailable' }), { status: 503, headers: { 'Content-Type': 'application/json' } })
      throw Error(path)
    })
    const manager = new AuthSessionManager(fetcher, store)
    await manager.login({ username: 'owner@example.com', password: 'password' }, 'session')
    await expect(manager.heartbeat(new AbortController().signal)).rejects.toMatchObject({ status: 503 })
    expect(store.load()?.token.token).toBe('a')
    await expect(manager.request('/api/v1/users/me')).resolves.toMatchObject({ userId: 'user-1' })
  })

  it('aborts old organization requests and never retries their late 401 under the new organization',async()=>{
    let resolveSlow!:(value:Response)=>void
    let slowSignal:AbortSignal|null|undefined
    let current='org-1', refreshCalls=0
    const fetcher=vi.fn(async(input:RequestInfo|URL,init?:RequestInit)=>{
      const path=String(input)
      if(path.endsWith('/auth/login'))return ok(token('a'))
      if(path.endsWith('/users/me'))return ok({...user,tenantId:current})
      if(path.endsWith('/organizations'))return ok(organizations)
      if(path==='/slow'){slowSignal=init?.signal;return new Promise<Response>(resolve=>resolveSlow=resolve)}
      if(path.endsWith('/org-2/switch')){current='org-2';return ok({...token('b'),tenantId:current})}
      if(path.endsWith('/auth/refresh')){refreshCalls++;return ok(token('unexpected'))}
      throw Error(path)
    })
    const manager=new AuthSessionManager(fetcher,new SessionStore(new MemoryStorage(),new MemoryStorage()))
    await manager.login({username:'owner@example.com',password:'password'},'session')
    const pending=manager.request('/slow')
    const assertion=expect(pending).rejects.toMatchObject({name:'AbortError'})
    const switched=await manager.switchOrganization('org-2')
    expect(switched.user.tenantId).toBe('org-2');expect(slowSignal?.aborted).toBe(true)
    resolveSlow(unauthorized());await assertion;expect(refreshCalls).toBe(0)
  })
  it('restores a browser session through the HttpOnly refresh cookie when storage is empty', async () => {
    const store = new SessionStore(new MemoryStorage(), new MemoryStorage())
    const fetcher = vi.fn(async (input: RequestInfo | URL) => {
      const path = String(input)
      if (path.endsWith('/web/auth/refresh')) return ok(token('cookie-restored'))
      if (path.endsWith('/users/me')) return ok(user)
      if (path.endsWith('/organizations')) return ok(organizations)
      throw new Error(`Unexpected request: ${path}`)
    })

    const session = await new AuthSessionManager(fetcher, store).restore()

    expect(session?.token.token).toBe('cookie-restored')
    expect(store.load()?.token.token).toBe('cookie-restored')
  })

  it('logs in, loads the native user and organization context, and stores a session token', async () => {
    const sessionStorage = new MemoryStorage()
    const localStorage = new MemoryStorage()
    const store = new SessionStore(sessionStorage, localStorage)
    const fetcher = vi.fn(async (input: RequestInfo | URL) => {
      const path = String(input)
      if (path.endsWith('/auth/login')) return ok(token('access-1'))
      if (path.endsWith('/users/me')) return ok(user)
      if (path.endsWith('/organizations')) return ok(organizations)
      throw new Error(`Unexpected request: ${path}`)
    })

    const manager = new AuthSessionManager(fetcher, store)
    const session = await manager.login({ username: 'owner@example.com', password: 'password' }, 'session')

    expect(session.user.displayName).toBe('Owner')
    expect(session.organizations[0].organization.name).toBe('Orbit Lab')
    expect(store.load()?.token.token).toBe('access-1')
    expect(fetcher).toHaveBeenCalledTimes(3)
  })

  it('uses one refresh request for concurrent 401 responses and retries with the new token', async () => {
    const store = new SessionStore(new MemoryStorage(), new MemoryStorage())
    store.save(token('expired'), 'session')
    let refreshCalls = 0
    const fetcher = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const path = String(input)
      const authorization = new Headers(init?.headers).get('Authorization')
      if (path.endsWith('/auth/refresh')) {
        refreshCalls++
        await new Promise((resolve) => setTimeout(resolve, 0))
        return ok(token('renewed'))
      }
      if (authorization === 'Bearer expired') return unauthorized()
      if (path.endsWith('/users/me')) return ok(user)
      if (path.endsWith('/organizations')) return ok(organizations)
      throw new Error(`Unexpected request: ${path}`)
    })

    const session = await new AuthSessionManager(fetcher, store).restore()

    expect(session?.token.token).toBe('renewed')
    expect(refreshCalls).toBe(1)
    expect(store.load()?.token.token).toBe('renewed')
  })

  it('clears a terminal session when refresh is rejected', async () => {
    const store = new SessionStore(new MemoryStorage(), new MemoryStorage())
    store.save(token('expired'), 'local')
    const fetcher = vi.fn(async (input: RequestInfo | URL) => {
      if (String(input).endsWith('/auth/refresh')) return unauthorized()
      return unauthorized()
    })

    const session = await new AuthSessionManager(fetcher, store).restore()

    expect(session).toBeNull()
    expect(store.load()).toBeNull()
  })

  it('never persists browser credentials in local storage', async () => {
    const sessionStorage = new MemoryStorage()
    const localStorage = new MemoryStorage()
    const store = new SessionStore(sessionStorage, localStorage)
    const fetcher = vi.fn(async (input: RequestInfo | URL) => {
      const path = String(input)
      if (path.endsWith('/auth/login')) return ok(token('persistent'))
      if (path.endsWith('/users/me')) return ok(user)
      if (path.endsWith('/organizations')) return ok(organizations)
      throw new Error(`Unexpected request: ${path}`)
    })

    await new AuthSessionManager(fetcher, store).login(
      { username: 'owner@example.com', password: 'password' },
      'local',
    )

    expect(sessionStorage.getItem('spaceagent.auth.v1')).toBeNull()
    expect(localStorage.getItem('spaceagent.auth.v1')).toBeNull()
  })

  it('replaces the session token and reloads context when switching organization', async () => {
    const store = new SessionStore(new MemoryStorage(), new MemoryStorage())
    store.save(token('org-one'), 'session')
    const switchedToken = { ...token('org-two'), tenantId: 'org-2', tenantRole: 'MEMBER' }
    const switchedUser = { ...user, tenantId: 'org-2', tenantRole: 'MEMBER' }
    const switchedOrganizations = [
      ...organizations,
      {
        organization: { ...organizations[0].organization, id: 'org-2', name: 'Research', slug: 'research' },
        membership: { ...organizations[0].membership, organizationId: 'org-2', role: 'MEMBER' },
      },
    ]
    let switched = false
    const fetcher = vi.fn(async (input: RequestInfo | URL) => {
      const path = String(input)
      if (path.endsWith('/organizations/org-2/switch')) {
        switched = true
        return ok(switchedToken)
      }
      if (path.endsWith('/users/me')) return ok(switched ? switchedUser : user)
      if (path.endsWith('/organizations')) return ok(switched ? switchedOrganizations : organizations)
      throw new Error(`Unexpected request: ${path}`)
    })
    const manager = new AuthSessionManager(fetcher, store)
    await manager.restore()

    const session = await manager.switchOrganization('org-2')

    expect(session.user.tenantId).toBe('org-2')
    expect(session.organizations).toHaveLength(2)
    expect(store.load()?.token.token).toBe('org-two')
  })
})
