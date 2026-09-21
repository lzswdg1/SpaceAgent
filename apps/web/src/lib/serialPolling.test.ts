import { afterEach, expect, it, vi } from 'vitest'
import { startSerialPolling } from './serialPolling'

afterEach(() => vi.useRealTimers())
it('never overlaps requests and cancellation invalidates the active signal', async () => {
  vi.useFakeTimers()
  let finish!: (value: boolean) => void
  let signal!: AbortSignal
  const work = vi.fn((next: AbortSignal) => { signal = next; return new Promise<boolean>(resolve => { finish = resolve }) })
  const stop = startSerialPolling(work, 100, () => true)
  await vi.advanceTimersByTimeAsync(1000)
  expect(work).toHaveBeenCalledTimes(1)
  stop(); expect(signal.aborted).toBe(true)
  finish(true); await vi.advanceTimersByTimeAsync(1000)
  expect(work).toHaveBeenCalledTimes(1)
})
it('skips hidden views and stops when all work is terminal', async () => {
  vi.useFakeTimers()
  let visible = false
  const work = vi.fn(async () => false)
  const stop = startSerialPolling(work, 100, () => visible)
  await vi.advanceTimersByTimeAsync(300)
  expect(work).not.toHaveBeenCalled()
  visible = true
  await vi.advanceTimersByTimeAsync(1000)
  expect(work).toHaveBeenCalledTimes(1)
  stop()
})
