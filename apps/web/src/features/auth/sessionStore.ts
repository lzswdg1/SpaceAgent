import type { AuthToken, SessionPersistence } from './types'

export type StorageLike = Pick<Storage, 'getItem' | 'setItem' | 'removeItem'>

type StoredAuth = {
  token: AuthToken
  persistence: 'session'
}

const STORAGE_KEY = 'spaceagent.auth.v1'

export class SessionStore {
  private current: StoredAuth | null = null

  constructor(
    private readonly sessionStorage: StorageLike,
    private readonly localStorage: StorageLike,
  ) {
    // M37 migration: remove all legacy browser-stored credentials.
    this.sessionStorage.removeItem(STORAGE_KEY)
    this.localStorage.removeItem(STORAGE_KEY)
  }

  load(): StoredAuth | null {
    return this.current
  }

  save(token: AuthToken, _persistence: SessionPersistence): void {
    this.clear()
    this.current = { token, persistence: 'session' }
  }

  clear(): void {
    this.current = null
    this.sessionStorage.removeItem(STORAGE_KEY)
    this.localStorage.removeItem(STORAGE_KEY)
  }
}

export const createBrowserSessionStore = () => new SessionStore(window.sessionStorage, window.localStorage)
