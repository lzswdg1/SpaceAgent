import { useEffect, useState } from 'react'
import type { AdminApi } from './adminApi'
import type { DashboardView, PresenceSummary, WindowRange } from './types'
import { pollPresence } from './pollPresence'

export function useDashboard(api: AdminApi, range: WindowRange, withPresence = false) {
  const [revision, refresh] = useState(0)
  const [data, setData] = useState<DashboardView | null>(null)
  const [presence, setPresence] = useState<PresenceSummary | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<unknown>(null)
  const [presenceError, setPresenceError] = useState(false)
  useEffect(() => {
    let current = true
    setLoading(true); setError(null); setData(null)
    void api.dashboard(range).then(value => { if (current) setData(value) })
      .catch(reason => { if (current) setError(reason) })
      .finally(() => { if (current) setLoading(false) })
    return () => { current = false }
  }, [api, range, revision])
  useEffect(() => {
    setPresence(null); setPresenceError(false)
    if (!withPresence) return
    return pollPresence(signal => api.presence({ signal }), value => {
      setPresence(value); setPresenceError(false)
    }, () => { setPresence(null); setPresenceError(true) }, document)
  }, [api, revision, withPresence])
  return { data, presence, loading, error, presenceError, refresh: () => refresh(v => v + 1) }
}
