import { afterEach, describe, expect, it, vi } from 'vitest'
import { appendBoundedRuntimeEvent, createStreamingMessageBatcher, MAX_VISIBLE_RUNTIME_EVENTS } from './streamingMessages'

type Row = { id: string; content: string; reasoning?: string; reasoningOpen?: boolean; reasoningStartedAt?: number }

afterEach(() => vi.useRealTimers())

describe('streaming message batching', () => {
  it('coalesces content and reasoning without replacing unaffected history rows', () => {
    vi.useFakeTimers()
    const historical: Row = { id: 'history', content: 'stable' }
    let rows: Row[] = [historical, { id: 'live', content: '' }]
    let updates = 0
    const batch = createStreamingMessageBatcher<Row>((update) => {
      rows = update(rows)
      updates += 1
    }, 'live', 50, () => 123)

    batch.content('hel')
    batch.content('lo')
    batch.reasoning('check')
    expect(updates).toBe(0)
    vi.advanceTimersByTime(49)
    expect(updates).toBe(0)
    vi.advanceTimersByTime(1)

    expect(updates).toBe(1)
    expect(rows[0]).toBe(historical)
    expect(rows[1]).toMatchObject({
      content: 'hello', reasoning: 'check', reasoningOpen: true, reasoningStartedAt: 123,
    })
  })

  it('flushes terminal text immediately and cancels delayed work', () => {
    vi.useFakeTimers()
    let rows: Row[] = [{ id: 'live', content: '' }]
    let updates = 0
    const batch = createStreamingMessageBatcher<Row>((update) => {
      rows = update(rows)
      updates += 1
    }, 'live')

    batch.content('done')
    batch.flush()
    batch.cancel()
    vi.runAllTimers()

    expect(rows[0].content).toBe('done')
    expect(updates).toBe(1)
  })

  it('retains only bounded event details while callers can count separately', () => {
    let events = [] as { type: string; data: unknown }[]
    for (let index = 0; index < MAX_VISIBLE_RUNTIME_EVENTS + 25; index += 1) {
      events = appendBoundedRuntimeEvent(events, { type: String(index), data: null })
    }
    expect(events).toHaveLength(MAX_VISIBLE_RUNTIME_EVENTS)
    expect(events[0].type).toBe('25')
  })
})
