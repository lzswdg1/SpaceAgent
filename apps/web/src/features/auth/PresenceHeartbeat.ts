import { ApiError } from '../../lib/api'

export const PRESENCE_INTERVAL_MS = 30_000
const REQUEST_TIMEOUT_MS = 10_000
const WAKE_THROTTLE_MS = 5_000

/** A tab renews a server-owned login-session lease; it never stores identity or counts users. */
export class PresenceHeartbeat {
  private running = false
  private generation = 0
  private timer: ReturnType<typeof setTimeout> | undefined
  private deadline: ReturnType<typeof setTimeout> | undefined
  private controller: AbortController | null = null
  private lastAttempt = -Infinity

  constructor(private readonly send: (signal: AbortSignal) => Promise<unknown>) {}

  start() {
    if (this.running) return
    this.running = true
    this.generation++
    this.schedule(0)
  }

  stop() {
    this.running = false
    this.generation++
    clearTimeout(this.timer)
    clearTimeout(this.deadline)
    this.controller?.abort()
    this.controller = null
  }

  wake() {
    if (!this.running || this.controller || Date.now() - this.lastAttempt < WAKE_THROTTLE_MS) return
    this.schedule(0)
  }

  private schedule(delay: number) {
    clearTimeout(this.timer)
    this.timer = setTimeout(() => { void this.tick() }, delay)
  }

  private async tick() {
    if (!this.running || this.controller) return
    const generation = this.generation
    const controller = new AbortController()
    this.controller = controller
    this.lastAttempt = Date.now()
    let deadline: ReturnType<typeof setTimeout> | undefined
    const timedOut = new Promise<never>((_, reject) => {
      deadline = setTimeout(() => {
        controller.abort()
        reject(new DOMException('Presence request timed out', 'TimeoutError'))
      }, REQUEST_TIMEOUT_MS)
      this.deadline = deadline
      controller.signal.addEventListener('abort', () => reject(new DOMException('Presence stopped', 'AbortError')), { once: true })
    })
    try {
      await Promise.race([Promise.resolve().then(() => this.send(controller.signal)), timedOut])
    } catch (error) {
      // Existing transport performs its one authorized refresh. Do not hammer revoked identities.
      if (generation === this.generation && error instanceof ApiError && [401, 403].includes(error.status)) this.stop()
      // Presence failure must not interrupt a conversation or expose raw errors/credentials.
    } finally {
      clearTimeout(deadline)
      if (generation === this.generation) {
        this.controller = null
        if (this.running) this.schedule(PRESENCE_INTERVAL_MS)
      }
    }
  }
}

export function attachPresenceEvents(
  heartbeat: PresenceHeartbeat,
  lifecycle: EventTarget,
  page: EventTarget & { readonly visibilityState: string },
) {
  const wake = () => heartbeat.wake()
  const show = () => heartbeat.start()
  const hide = () => heartbeat.stop()
  const visible = () => { if (page.visibilityState === 'visible') heartbeat.wake() }
  lifecycle.addEventListener('online', wake)
  lifecycle.addEventListener('pageshow', show)
  lifecycle.addEventListener('pagehide', hide)
  page.addEventListener('visibilitychange', visible)
  heartbeat.start()
  return () => {
    lifecycle.removeEventListener('online', wake)
    lifecycle.removeEventListener('pageshow', show)
    lifecycle.removeEventListener('pagehide', hide)
    page.removeEventListener('visibilitychange', visible)
    heartbeat.stop()
    // No leave on tab close/unmount: another tab may use this same login-session lease.
  }
}
