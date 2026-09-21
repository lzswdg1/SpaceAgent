import type { AdminApi } from '../adminApi'
import { useDashboard } from '../useDashboard'
import { displayCount } from '../adminViewModel'

export function ResourcesPage({ api, tx }: { api: AdminApi; tx: (key: string) => string }) {
  const { data, error, loading, refresh } = useDashboard(api, '24h')
  const p = data?.projection
  const rows = p ? [
    ['providers',p.inference.providers,p.inference.activeProviders],
    ['modelPools',p.inference.modelPools,p.inference.activeModelPools],
    ['agents',p.agent.agents,p.agent.activeAgents],
    ['projects',p.project.projects,p.project.activeProjects],
    ['workspaces',p.project.workspaces,p.project.activeWorkspaces],
    ['conversations',p.conversation.conversations,p.conversation.activeConversations],
    ['mcpConnections',p.tooling.mcpConnections,p.tooling.activeMcpConnections],
  ] as const : []
  return <section className="page-view" aria-busy={loading}>
    <header className="page-head"><div><h1>{tx('resourceTitle')}</h1><p>{tx('inventoryHelp')}</p></div>
      <button className="refresh-button" disabled={loading} onClick={refresh}>{tx('refresh')}</button></header>
    {error != null && <div className="page-error" role="alert">{tx('dataUnavailable')}<button onClick={refresh}>{tx('retry')}</button></div>}
    {loading && <div className="page-loading">{tx('loading')}</div>}
    {data?.stale && <div className="stale-banner">{tx('staleEvidence')}</div>}
    {p && <div className="surface inventory-table"><table><thead><tr><th>{tx('resourceType')}</th><th>{tx('total')}</th><th>{tx('activeCount')}</th></tr></thead>
      <tbody>{rows.map(([label,total,active]) => <tr key={label}><td>{tx(label)}</td><td>{displayCount(total)}</td><td>{displayCount(active)}</td></tr>)}</tbody></table>
      <p>{tx('inventoryUnavailable')}</p><p>{tx('updatedAt')} {new Date(p.generatedAt).toLocaleString()}</p></div>}
  </section>
}
