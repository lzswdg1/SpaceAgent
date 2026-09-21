import type { PresenceSummary } from './types'

/** Read-only polling; never refreshes the rest of the dashboard or invents a count on failure. */
export function pollPresence(
  read: (signal: AbortSignal) => Promise<PresenceSummary>,
  update: (value: PresenceSummary) => void,
  failed: () => void,
  page: EventTarget & { readonly visibilityState: string },
) {
  let closed = false
  let sequence = 0
  let active: AbortController | null = null
  let timeout: ReturnType<typeof setTimeout> | undefined
  const poll = () => {
    if (closed || active || page.visibilityState === 'hidden') return
    const current = ++sequence
    const controller = new AbortController()
    active = controller
    const deadline = setTimeout(() => {
      if (closed || current !== sequence) return
      sequence++; controller.abort(); active = null; failed()
    }, 10_000)
    timeout = deadline
    void Promise.resolve().then(() => read(controller.signal)).then(value => {
      if (!closed && current === sequence) update(value)
    }).catch(() => {
      if (!closed && current === sequence) failed()
    }).finally(() => {
      clearTimeout(deadline)
      if (current === sequence) active = null
    })
  }
  const visibility = () => {
    if (page.visibilityState === 'hidden') {
      sequence++; clearTimeout(timeout); active?.abort(); active = null
    } else poll()
  }
  const timer = setInterval(poll, 15_000)
  page.addEventListener('visibilitychange', visibility)
  poll()
  return () => {
    closed = true; sequence++; clearInterval(timer); clearTimeout(timeout); active?.abort()
    page.removeEventListener('visibilitychange', visibility)
  }
}
