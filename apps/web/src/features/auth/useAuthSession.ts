import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { ApiError } from '../../lib/api'
import { AuthSessionManager } from './AuthSessionManager'
import { createBrowserSessionStore } from './sessionStore'
import { PresenceHeartbeat, attachPresenceEvents } from './PresenceHeartbeat'
import type { AuthenticatedSession, LoginInput, RegisterInput, SessionPersistence } from './types'

type AuthState =
  | { status: 'restoring'; session: null; error: null }
  | { status: 'anonymous'; session: null; error: string | null }
  | { status: 'authenticated'; session: AuthenticatedSession; error: null }

const errorMessage = (error: unknown) => {
  if (error instanceof ApiError) return error.message
  if (error instanceof Error) return error.message
  return 'Authentication failed'
}

export function useAuthSession() {
  const manager = useMemo(() => new AuthSessionManager(window.fetch.bind(window), createBrowserSessionStore()), [])
  const [state, setState] = useState<AuthState>({ status: 'restoring', session: null, error: null })
  const stopPresence = useRef<(() => void) | null>(null)

  useEffect(() => {
    if (state.status !== 'authenticated') return
    const heartbeat = new PresenceHeartbeat(signal => manager.heartbeat(signal))
    const stop = attachPresenceEvents(heartbeat, window, document)
    stopPresence.current = stop
    return () => { stop(); if (stopPresence.current === stop) stopPresence.current = null }
  }, [manager, state.status, state.session?.user.userId, state.session?.user.tenantId])

  const restore = useCallback(async () => {
    setState({ status: 'restoring', session: null, error: null })
    try {
      const session = await manager.restore()
      setState(session
        ? { status: 'authenticated', session, error: null }
        : { status: 'anonymous', session: null, error: null })
    } catch (error) {
      setState({ status: 'anonymous', session: null, error: errorMessage(error) })
    }
  }, [manager])

  useEffect(() => { void restore() }, [restore])

  const authenticate = useCallback(async (
    mode: 'signin' | 'register',
    input: LoginInput | RegisterInput,
    persistence: SessionPersistence,
  ) => {
    const session = mode === 'signin'
      ? await manager.login(input, persistence)
      : await manager.register(input as RegisterInput, persistence)
    setState({ status: 'authenticated', session, error: null })
    return session
  }, [manager])

  const logout = useCallback(async () => {
    stopPresence.current?.()
    try { await manager.logout() }
    finally { setState({ status: 'anonymous', session: null, error: null }) }
  }, [manager])

  const switchOrganization = useCallback(async (organizationId: string) => {
    stopPresence.current?.()
    // Unmount old scoped pages immediately, including their active stream listeners.
    setState({ status: 'restoring', session: null, error: null })
    try {
      const session = await manager.switchOrganization(organizationId)
      setState({ status: 'authenticated', session, error: null })
      return session
    } catch (error) {
      try {
        const recovered = await manager.restore()
        setState(recovered
          ? { status: 'authenticated', session: recovered, error: null }
          : { status: 'anonymous', session: null, error: errorMessage(error) })
      } catch (recoveryError) {
        setState({ status: 'anonymous', session: null, error: errorMessage(recoveryError) })
      }
      throw error
    }
  }, [manager])

  const request = useCallback(<T,>(path: string, init: RequestInit = {}) => (
    manager.request<T>(path, init)
  ), [manager])

  const openStream = useCallback((path: string, init: RequestInit = {}) => (
    manager.openStream(path, init)
  ), [manager])

  return { state, authenticate, logout, restore, switchOrganization, request, openStream }
}
