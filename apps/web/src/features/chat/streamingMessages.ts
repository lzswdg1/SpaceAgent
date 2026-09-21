import type { RuntimeEvent } from './types'

export const MAX_VISIBLE_RUNTIME_EVENTS = 100

type StreamMessage = {
  id: string
  content: string
  reasoning?: string
  reasoningOpen?: boolean
  reasoningStartedAt?: number
}

type MessageSetter<T extends StreamMessage> = (update: (rows: T[]) => T[]) => void

export function appendBoundedRuntimeEvent(
  rows: RuntimeEvent[],
  event: RuntimeEvent,
): RuntimeEvent[] {
  return [...rows.slice(-(MAX_VISIBLE_RUNTIME_EVENTS - 1)), event]
}

export function createStreamingMessageBatcher<T extends StreamMessage>(
  setMessages: MessageSetter<T>,
  messageId: string,
  delayMs = 50,
  clock: () => number = Date.now,
) {
  let content = ''
  let reasoning = ''
  let reasoningStartedAt: number | undefined
  let timer: ReturnType<typeof setTimeout> | null = null

  const flush = () => {
    if (timer !== null) clearTimeout(timer)
    timer = null
    if (!content && !reasoning) return
    const contentDelta = content
    const reasoningDelta = reasoning
    const startedAt = reasoningStartedAt
    content = ''
    reasoning = ''
    setMessages((rows) => updateOne(rows, messageId, (message) => ({
      ...message,
      content: message.content + contentDelta,
      ...(reasoningDelta ? {
        reasoning: (message.reasoning ?? '') + reasoningDelta,
        reasoningOpen: true,
        reasoningStartedAt: message.reasoningStartedAt ?? startedAt,
      } : {}),
    })))
  }

  const schedule = () => {
    if (timer === null) timer = setTimeout(flush, Math.max(16, delayMs))
  }

  return {
    content(delta: string) {
      content += delta
      schedule()
    },
    reasoning(delta: string) {
      if (reasoningStartedAt === undefined) reasoningStartedAt = clock()
      reasoning += delta
      schedule()
    },
    flush,
    cancel() {
      if (timer !== null) clearTimeout(timer)
      timer = null
      content = ''
      reasoning = ''
    },
  }
}

function updateOne<T extends StreamMessage>(
  rows: T[],
  messageId: string,
  update: (message: T) => T,
): T[] {
  for (let index = rows.length - 1; index >= 0; index -= 1) {
    if (rows[index].id !== messageId) continue
    const next = rows.slice()
    next[index] = update(rows[index])
    return next
  }
  return rows
}
