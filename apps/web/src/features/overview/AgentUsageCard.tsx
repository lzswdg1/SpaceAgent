import { useEffect, useState } from 'react'
import type { Language } from '../../copy'
import type { AuthenticatedRequest, UsageResponse } from './types'
import { loadAgentUsage } from './overviewApi'
import { formatUsageCost, usageCopy } from './usageCopy'

export function AgentUsageCard({ request, language }: { request: AuthenticatedRequest; language: Language }) {
  const [attempt, setAttempt] = useState(0)
  const [state, setState] = useState<{ data?: UsageResponse; failed: boolean; loading: boolean }>({ failed: false, loading: true })
  useEffect(() => {
    let current = true
    setState({ failed: false, loading: true })
    loadAgentUsage(request).then(data => {
      if (current) setState({ data, failed: false, loading: false })
    }).catch(() => {
      if (current) setState({ failed: true, loading: false })
    })
    return () => { current = false }
  }, [request, attempt])
  const text = usageCopy(language)
  return <article className="overview-card agent-usage-card" aria-busy={state.loading}>
    <header><div><b>{text.ranking}</b><p>{text.scope}</p></div>
      <button type="button" disabled={state.loading} onClick={() => setAttempt(value => value + 1)}>{text.refresh}</button></header>
    {state.loading ? <p role="status">{text.loading}</p> : state.failed ? <p role="alert">{text.unavailable}</p>
      : !state.data?.rows.length ? <p>{text.empty}</p> : <>
        <div className="agent-usage-scroll" tabIndex={0} role="region" aria-label={text.ranking}>
          <table><thead><tr><th scope="col">#</th><th scope="col">{text.agent}</th><th scope="col">Tokens</th><th scope="col">{text.calls}</th>
            <th scope="col">{text.input}</th><th scope="col">{text.output}</th><th scope="col">{text.cache}</th><th scope="col">{text.cost}</th></tr></thead>
            <tbody>{state.data.rows.map((row, index) => <tr key={row.key}>
              <td>{index + 1}</td><th scope="row">{row.name}</th><td>{row.totalTokens.toLocaleString(language)}</td><td>{row.callCount.toLocaleString(language)}</td>
              <td>{row.inputTokens.toLocaleString(language)}</td><td>{row.outputTokens.toLocaleString(language)}</td>
              <td>{row.cacheReadTokens.toLocaleString(language)} / {row.cacheCreateTokens.toLocaleString(language)}</td>
              <td>{formatUsageCost(row.costUsd)}{row.hasIncompleteCost && <small>{text.incomplete}</small>}</td>
            </tr>)}</tbody></table>
        </div>
        {state.data.truncated && <p role="status">{text.truncated}</p>}
      </>}
    <p className="agent-usage-hint">{text.scopeHint}</p>
  </article>
}
