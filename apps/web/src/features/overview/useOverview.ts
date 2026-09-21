import { useCallback, useEffect, useRef, useState } from 'react'
import { loadOverview } from './overviewApi'
import type { AuthenticatedRequest, OverviewData, UsageRange } from './types'

type State = {
  loading: boolean
  data: OverviewData | null
  error: string | null
}

export function useOverview(request: AuthenticatedRequest, range: UsageRange, sessionPage: number) {
  const [state, setState] = useState<State>({ loading: true, data: null, error: null })
  const generation = useRef(0)
  const refresh = useCallback(async () => {
    const currentGeneration = ++generation.current
    setState({ data: null, loading: true, error: null })
    try {
      const data = await loadOverview(request, range, sessionPage)
      if(currentGeneration === generation.current) setState({ loading: false, data, error: null })
    } catch (error) {
      if(currentGeneration !== generation.current) return
      setState((current) => ({
        loading: false,
        data: current.data,
        error: error instanceof Error ? error.message : 'Overview request failed',
      }))
    }
  }, [range, request, sessionPage])

  useEffect(() => { void refresh(); return () => { generation.current++ } }, [refresh])
  return { ...state, refresh }
}
