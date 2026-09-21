import { useState } from 'react'
import type { AdminApi } from '../adminApi'
import type { WindowRange } from '../types'
import { useDashboard } from '../useDashboard'
import { displayCount } from '../adminViewModel'

type Props = { api: AdminApi; tx: (key: string) => string }

export function OverviewPage({ api, tx }: Props) {
  const [range, setRange] = useState<WindowRange>('24h')
  const [expanded, setExpanded] = useState(false)
  const { data, presence, loading, error, presenceError, refresh } = useDashboard(api, range, true)
  const p = data?.projection
  const presenceNote = presenceError ? 'presenceUnavailable' : !presence ? 'loading' : presence.coverage === 'HEARTBEAT_CLIENTS_ONLY' ? 'presencePartial' : 'presenceNotCollected'
  const metrics = p ? [
    ['totalUsers', p.identity.totalUsers, 'allRegisteredUsers'],
    ['organizationsMetric', p.identity.activeOrganizations, 'currentSnapshot'],
    ['heartbeatOnline', presence?.onlineUsers, presenceNote],
    ['unfinishedRuns', p.runtime.activeRuns, 'stateNotLiveness'],
  ] as const : []
  return <section className="page-view" aria-busy={loading}>
    <header className="page-head"><div><h1>{tx('overviewTitle')}</h1></div>
      <div className="freshness"><span>{tx('updatedAt')}</span><b>{p ? new Date(p.generatedAt).toLocaleString() : '—'}</b>
        <button className="refresh-button" disabled={loading} onClick={refresh}>{tx('refresh')}</button></div></header>
    {error != null && <div className="page-error" role="alert">{tx('dataUnavailable')}<button onClick={refresh}>{tx('retry')}</button></div>}
    {data?.stale && <div className="stale-banner" role="status">{tx('staleEvidence')}</div>}
    {loading && <div className="page-loading" role="status">{tx('loading')}</div>}
    {p && <>
      <div className="metric-strip">{metrics.map(([label,value,note]) => <div className="metric" key={label}>
        <span>{tx(label)}</span><strong>{displayCount(value)}</strong><small>{tx(note)}</small>
      </div>)}</div>
      <article className="surface"><header><b>{tx('attention')}</b></header><div className="attention-list">
        {([
          ['unknownModelCalls', p.inference.unknownModelCalls, 'unknownHelp'],
          ['unknownToolCalls', p.tooling.unknownToolExecutions, 'unknownHelp'],
          ['providerProblems', p.inference.unhealthyProviders + p.inference.untestedProviders, 'providerProblemsHelp'],
          ['failedRunsTotal', p.runtime.failedRuns, 'allTimeCount'],
        ] as const).map(([label,value,note]) => <div key={label}><span><b>{tx(label)}</b><small>{tx(note)}</small></span>
          <strong className={typeof value === 'number' && value > 0 ? 'warn' : ''}>{displayCount(value)}</strong></div>)}
      </div></article>
      <details className="surface observation-details" open={expanded} onToggle={e => setExpanded(e.currentTarget.open)}><summary>{tx('dataDetails')}</summary>
        <div className="range">{(['24h','7d','30d'] as WindowRange[]).map(value => <button key={value}
          aria-pressed={range === value} className={range === value ? 'active' : ''} onClick={() => setRange(value)}>{tx(value)}</button>)}</div>
        <p>{tx('windowScope')}</p><dl className="observation-fields">
          <div><dt>{tx('newUsers')}</dt><dd>{displayCount(p.identity.registeredInWindow)}</dd></div>
          <div><dt>{tx('loginUsers')}</dt><dd>{displayCount(p.identity.uniqueSuccessfulLoginsInWindow)}</dd></div>
          <div><dt>{tx('recentUsers')}</dt><dd>{displayCount(p.identity.recentlyActiveUsers)}</dd></div>
          <div><dt>{tx('refreshSessions')}</dt><dd>{displayCount(p.identity.activeRefreshSessions)}</dd></div>
          <div><dt>{tx('release')}</dt><dd>{p.releaseVersion}</dd></div>
          <div><dt>{tx('schema')}</dt><dd>V{p.schemaVersion}</dd></div>
        </dl><p>{tx('testDataNote')}</p></details>
    </>}
  </section>
}
