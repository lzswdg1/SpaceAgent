/** One request at a time. Completion schedules the next tick; cleanup aborts stale work. */
export function startSerialPolling(
  work: (signal: AbortSignal) => Promise<boolean | void>,
  delayMs: number,
  visible: () => boolean = () => document.visibilityState !== 'hidden',
) {
  const controller = new AbortController()
  let timer: ReturnType<typeof setTimeout> | null = null
  let stopped = false
  const schedule = () => { if (!stopped) timer = setTimeout(tick, delayMs) }
  const tick = async () => {
    timer = null
    if (stopped) return
    try {
      if (visible() && await work(controller.signal) === false) stopped = true
    } catch { /* Caller owns its explicit error UI; never retry a mutation here. */ }
    finally { schedule() }
  }
  schedule()
  return () => { stopped = true; controller.abort(); if (timer !== null) clearTimeout(timer) }
}
