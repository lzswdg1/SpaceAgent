import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { pollPresence } from './pollPresence'
import type { PresenceSummary } from './types'

const cleanups: Array<() => void> = []
beforeEach(() => vi.useFakeTimers())
afterEach(() => { cleanups.splice(0).forEach(stop => stop()); vi.clearAllTimers(); vi.useRealTimers() })
const sample: PresenceSummary = { onlineUsers: 2, onlineSessions: 3, observedSessionCount: 3, coverage: 'HEARTBEAT_CLIENTS_ONLY', measuredAt: '2026-09-14T00:00:00Z' }

it('refreshes only while visible and delivers server counts without guessing', async () => {
  const page = Object.assign(new EventTarget(), { visibilityState: 'visible' })
  const read = vi.fn(async () => sample), update = vi.fn(), failed = vi.fn()
  const stop = pollPresence(read, update, failed, page); cleanups.push(stop)
  await vi.advanceTimersByTimeAsync(0)
  expect(update).toHaveBeenLastCalledWith(sample)
  await vi.advanceTimersByTimeAsync(15_000); expect(read).toHaveBeenCalledTimes(2)
  page.visibilityState = 'hidden'; page.dispatchEvent(new Event('visibilitychange'))
  await vi.advanceTimersByTimeAsync(60_000); expect(read).toHaveBeenCalledTimes(2)
  page.visibilityState = 'visible'; page.dispatchEvent(new Event('visibilitychange'))
  await vi.advanceTimersByTimeAsync(0); expect(read).toHaveBeenCalledTimes(3)
  stop(); await vi.advanceTimersByTimeAsync(60_000); expect(read).toHaveBeenCalledTimes(3)
})

it('times out a stale request, reports failure, and ignores its late result', async () => {
  let resolve!: (v: PresenceSummary) => void
  const page = Object.assign(new EventTarget(), { visibilityState: 'visible' })
  const read = vi.fn((_signal: AbortSignal) => new Promise<PresenceSummary>(r => { resolve = r }))
  const update = vi.fn(), failed = vi.fn()
  cleanups.push(pollPresence(read, update, failed, page))
  await vi.advanceTimersByTimeAsync(10_000)
  expect(read.mock.calls[0][0].aborted).toBe(true)
  expect(failed).toHaveBeenCalledTimes(1)
  resolve(sample); await vi.advanceTimersByTimeAsync(0)
  expect(update).not.toHaveBeenCalled()
  await vi.advanceTimersByTimeAsync(5_000); expect(read).toHaveBeenCalledTimes(2)
})

it('does not replace failed reads with a zero count', async () => {
  const failed = vi.fn(), update = vi.fn()
  const page = Object.assign(new EventTarget(), { visibilityState: 'visible' })
  cleanups.push(pollPresence(async () => { throw new Error('offline') }, update, failed, page))
  await vi.advanceTimersByTimeAsync(0)
  expect(update).not.toHaveBeenCalled(); expect(failed).toHaveBeenCalledTimes(1)
})
