import { useCallback, useEffect, useRef, useState } from 'react'

/** User-driven pages shared by actual Agent selectors and the configuration index. */
export function usePagedOptions<T>(load: (offset: number, signal: AbortSignal) => Promise<T[]>,
  append: (page: T[]) => void, onError: (error: unknown) => void) {
  const callbacks = useRef({ load, append, onError })
  callbacks.current = { load, append, onError }
  const version = useRef(0), offset = useRef(100), inFlight = useRef(false)
  const controller = useRef<AbortController | null>(null), more = useRef(false)
  const [hasMore, setHasMore] = useState(false), [loading, setLoading] = useState(false)
  const reset = useCallback((count: number) => {
    version.current++; controller.current?.abort(); inFlight.current = false
    offset.current = 100; more.current = count >= 100
    setHasMore(more.current); setLoading(false)
  }, [])
  useEffect(() => () => { version.current++; controller.current?.abort() }, [])
  const loadMore = useCallback(async () => {
    if (inFlight.current || !more.current) return
    const scope = version.current
    const active = new AbortController(); controller.current = active
    inFlight.current = true; setLoading(true)
    try {
      const page = await callbacks.current.load(offset.current, active.signal)
      if (scope !== version.current || active.signal.aborted) return
      callbacks.current.append(page); offset.current += 100
      more.current = page.length === 100; setHasMore(more.current)
    } catch (reason) { if (scope === version.current && !active.signal.aborted) callbacks.current.onError(reason) }
    finally { if (scope === version.current) { inFlight.current = false; setLoading(false) } }
  }, [])
  return { hasMore, loading, reset, loadMore }
}
