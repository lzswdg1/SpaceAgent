import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '../../lib/api'
import { PresenceHeartbeat, attachPresenceEvents } from './PresenceHeartbeat'

const loops: PresenceHeartbeat[] = []
const loop = (send: (signal: AbortSignal) => Promise<unknown>) => {
  const value = new PresenceHeartbeat(send); loops.push(value); return value
}
beforeEach(() => { vi.useFakeTimers(); vi.setSystemTime(new Date('2026-09-14T00:00:00Z')) })
afterEach(() => { loops.splice(0).forEach(l => l.stop()); vi.clearAllTimers(); vi.useRealTimers() })

describe('Background presence heartbeat', () => {
  it('starts once, renews every 30 seconds and stops cleanly', async () => {
    const send = vi.fn(async () => undefined), h = loop(send)
    h.start(); h.start()
    await vi.advanceTimersByTimeAsync(0)
    expect(send).toHaveBeenCalledTimes(1)
    await vi.advanceTimersByTimeAsync(60_000)
    expect(send).toHaveBeenCalledTimes(3)
    h.stop(); await vi.advanceTimersByTimeAsync(90_000)
    expect(send).toHaveBeenCalledTimes(3)
  })
  it('avoids StrictMode duplicate starts and throttles wake events', async () => {
    const send = vi.fn(async () => undefined), h = loop(send)
    h.start(); h.stop(); h.start()
    await vi.advanceTimersByTimeAsync(0)
    h.wake(); h.wake(); await vi.advanceTimersByTimeAsync(0)
    expect(send).toHaveBeenCalledTimes(1)
    await vi.advanceTimersByTimeAsync(6_000)
    h.wake(); h.wake(); await vi.advanceTimersByTimeAsync(0)
    expect(send).toHaveBeenCalledTimes(2)
  })
  it('bounds hung requests and never overlaps renewals', async () => {
    const send = vi.fn((_signal: AbortSignal) => new Promise(() => {})), h = loop(send)
    h.start(); await vi.advanceTimersByTimeAsync(0)
    const signal = send.mock.calls[0][0]
    h.wake(); await vi.advanceTimersByTimeAsync(10_000)
    expect(signal.aborted).toBe(true)
    expect(send).toHaveBeenCalledTimes(1)
    await vi.advanceTimersByTimeAsync(30_000)
    expect(send).toHaveBeenCalledTimes(2)
  })
  it('retries network failures but stops after authorization is rejected', async () => {
    const send = vi.fn().mockRejectedValueOnce(new ApiError('offline', 0))
      .mockRejectedValueOnce(new ApiError('revoked', 401)), h = loop(send)
    h.start(); await vi.advanceTimersByTimeAsync(60_000)
    expect(send).toHaveBeenCalledTimes(2)
    h.wake(); await vi.advanceTimersByTimeAsync(90_000)
    expect(send).toHaveBeenCalledTimes(2)
  })
  it('does not restart a stopped scope when an old response arrives', async () => {
    let resolve!: () => void
    const send = vi.fn(() => new Promise<void>(r => { resolve = r })), h = loop(send)
    h.start(); await vi.advanceTimersByTimeAsync(0)
    h.stop(); resolve(); await vi.advanceTimersByTimeAsync(90_000)
    expect(send).toHaveBeenCalledTimes(1)
  })
  it('keeps other tabs online, resumes bfcache and removes event listeners', async () => {
    const a = new EventTarget(), b = new EventTarget()
    const doc = Object.assign(new EventTarget(), { visibilityState: 'visible' })
    const first = vi.fn(async () => undefined), second = vi.fn(async () => undefined)
    const stopA = attachPresenceEvents(loop(first), a, doc)
    const stopB = attachPresenceEvents(loop(second), b, doc)
    await vi.advanceTimersByTimeAsync(0)
    a.dispatchEvent(new Event('pagehide'))
    await vi.advanceTimersByTimeAsync(30_000)
    expect(first).toHaveBeenCalledTimes(1)
    expect(second).toHaveBeenCalledTimes(2)
    a.dispatchEvent(new Event('pageshow')); await vi.advanceTimersByTimeAsync(0)
    expect(first).toHaveBeenCalledTimes(2)
    doc.visibilityState = 'hidden'; doc.dispatchEvent(new Event('visibilitychange'))
    await vi.advanceTimersByTimeAsync(30_000)
    expect(first).toHaveBeenCalledTimes(3) // hidden is not logged out
    stopA(); stopB(); a.dispatchEvent(new Event('pageshow'))
    await vi.advanceTimersByTimeAsync(60_000)
    expect(first).toHaveBeenCalledTimes(3)
  })
})
