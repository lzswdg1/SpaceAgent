import { errorText } from '../errorText'
import { useCallback, useEffect, useRef, useState, type FormEvent } from 'react'
import { AdminApiError, type AdminApi } from '../adminApi'
import { cleanupStepTone, deletionBlockers, isDeletionEligible } from '../adminViewModel'
import type { AgentSummary, CleanupProjection, CommandResult, CredentialItem, Page, UserDetail, UserResourceKind, UserResourceOverview, UserResourceSummary, UserSummary } from '../types'

type Props = { api: AdminApi; tx: (key: string) => string }
type Action = 'create' | 'suspend' | 'restore' | 'preflight' | 'delete'

const initials = (user: UserSummary) => (user.displayName || user.loginName).split(/\s+/).map((part) => part[0]).join('').slice(0, 2).toUpperCase()
const time = (value: string | null) => value ? new Date(value).toLocaleString() : '—'
const statusTone = (status: string) => status === 'ACTIVE' ? 'good' : status === 'SUSPENDED' || status === 'PENDING_ACTIVATION' ? 'warn' : status.includes('DELETION') || status === 'DELETED' ? 'bad' : ''
const resourceKinds: Array<[UserResourceKind, keyof UserResourceOverview, string]> = [
  ['MODEL_POOL', 'modelPools', 'modelPools'], ['PROJECT', 'projects', 'projects'],
  ['TASK', 'tasks', 'tasks'], ['WORKSPACE', 'workspaces', 'workspaces'],
  ['CONVERSATION', 'conversations', 'conversations'], ['MCP_CONNECTION', 'mcpConnections', 'mcpConnections'],
  ['KNOWLEDGE_DOCUMENT', 'knowledgeDocuments', 'knowledgeDocuments'], ['MEMORY', 'memories', 'memories'],
  ['AUTOMATION', 'automations', 'automations'], ['RUN', 'runs', 'runs'],
  ['MODEL_EFFECT', 'modelEffects', 'modelEffects'], ['TOOL_EFFECT', 'toolEffects', 'toolEffects'],
]
const resourceMetrics = (resource: UserResourceSummary, tx: (key: string) => string) => {
  const pair = (left: string, right: string) => `${tx(left)} ${resource.primaryCount} · ${tx(right)} ${resource.secondaryCount}`
  if (resource.kind === 'MODEL_POOL') return pair('membersCount', 'enabledCount')
  if (resource.kind === 'PROJECT') return pair('tasks', 'workspaces')
  if (resource.kind === 'TASK') return pair('childTasksCount', 'workspaces')
  if (resource.kind === 'WORKSPACE') return `${tx('writableCount')} ${resource.primaryCount ? tx('yes') : tx('no')}`
  if (resource.kind === 'CONVERSATION') return pair('messagesCount', 'snapshotsCount')
  if (resource.kind === 'KNOWLEDGE_DOCUMENT') return `${tx('chunksCount')} ${resource.primaryCount}`
  if (resource.kind === 'AUTOMATION') return pair('executionsCount', 'riskCount')
  if (resource.kind === 'RUN') return pair('stepsCount', 'checkpointsCount')
  return null
}

export function UsersPage({ api, tx }: Props) {
  const [users, setUsers] = useState<UserSummary[]>([])
  const [total, setTotal] = useState(0)
  const [page, setPage] = useState(0)
  const [query, setQuery] = useState('')
  const [status, setStatus] = useState('')
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [detail, setDetail] = useState<UserDetail | null>(null)
  const [detailLoading, setDetailLoading] = useState(false)
  const [cleanup, setCleanup] = useState<CleanupProjection | null>(null)
  const [providers, setProviders] = useState<Page<CredentialItem> | null>(null)
  const [agents, setAgents] = useState<Page<AgentSummary> | null>(null)
  const [resourceOverview, setResourceOverview] = useState<UserResourceOverview | null>(null)
  const [resourceKind, setResourceKind] = useState<UserResourceKind>('RUN')
  const [resourcePage, setResourcePage] = useState<Page<UserResourceSummary> | null>(null)
  const [resourceLoading, setResourceLoading] = useState(false)
  const [action, setAction] = useState<Action | null>(null)
  const [submitting, setSubmitting] = useState(false)
  const [command, setCommand] = useState<CommandResult | null>(null)
  const listVersion = useRef(0)
  const detailVersion = useRef(0)
  const resourceVersion = useRef(0)
  const modalRef = useRef<HTMLFormElement>(null)

  const load = useCallback(async () => {
    const version = ++listVersion.current
    setLoading(true); setError(null)
    try {
      const result = await api.users({ page, pageSize: 25, query: query.trim() || undefined, status: status || undefined })
      if (version !== listVersion.current) return
      setUsers(result.items); setTotal(result.total)
    } catch (reason) { if (version === listVersion.current) setError(reason instanceof Error ? reason.message : 'Unable to load users') }
    finally { if (version === listVersion.current) setLoading(false) }
  }, [api, page, query, status])
  useEffect(() => { const timer = window.setTimeout(() => void load(), query ? 250 : 0); return () => { clearTimeout(timer); listVersion.current += 1 } }, [load, query])

  const openUser = async (user: UserSummary) => {
    const version = ++detailVersion.current
    const resourceRequest = ++resourceVersion.current
    setDetailLoading(true); setError(null); setCleanup(null); setProviders(null); setAgents(null); setCommand(null)
    setResourceOverview(null); setResourceKind('RUN'); setResourcePage(null)
    try {
      const [value, providerPage, agentPage, overview, runs] = await Promise.all([
        api.user(user.id), api.userProviders(user.id, 0, 10), api.userAgents(user.id, 0, 10),
        api.userResourceOverview(user.id), api.userResources(user.id, 'RUN', 0, 10),
      ])
      if (version !== detailVersion.current) return
      setDetail(value); setProviders(providerPage); setAgents(agentPage)
      setResourceOverview(overview)
      if (resourceRequest === resourceVersion.current) setResourcePage(runs)
      if (value.user.status === 'DELETION_PENDING') {
        try { const cleanupValue = await api.cleanupJob(user.id); if (version === detailVersion.current) setCleanup(cleanupValue) }
        catch (reason) { if (!(reason instanceof AdminApiError) || reason.status !== 404) throw reason }
      }
    } catch (reason) { if (version === detailVersion.current) setError(reason instanceof Error ? reason.message : 'Unable to load user') }
    finally { if (version === detailVersion.current) setDetailLoading(false) }
  }

  const closeDetail = () => { detailVersion.current += 1; resourceVersion.current += 1; setDetail(null); setCleanup(null); setProviders(null); setAgents(null); setResourceOverview(null); setResourcePage(null); setDetailLoading(false) }

  const changeResourcePage = async (kind: 'providers' | 'agents', nextPage: number) => {
    if (!detail || nextPage < 0) return
    const version = detailVersion.current
    try {
      if (kind === 'providers') {
        const value = await api.userProviders(detail.user.id, nextPage, 10)
        if (version === detailVersion.current) setProviders(value)
      } else {
        const value = await api.userAgents(detail.user.id, nextPage, 10)
        if (version === detailVersion.current) setAgents(value)
      }
    } catch (reason) { if (version === detailVersion.current) setError(reason instanceof Error ? reason.message : 'Unable to load user resources') }
  }

  const loadResourceKind = async (kind: UserResourceKind, nextPage = 0) => {
    if (!detail || nextPage < 0) return
    const version = detailVersion.current
    const request = ++resourceVersion.current
    setResourceKind(kind); setResourceLoading(true); setError(null)
    try {
      const value = await api.userResources(detail.user.id, kind, nextPage, 10)
      if (version === detailVersion.current && request === resourceVersion.current) setResourcePage(value)
    } catch (reason) { if (version === detailVersion.current) setError(reason instanceof Error ? reason.message : 'Unable to load user resources') }
    finally { if (version === detailVersion.current && request === resourceVersion.current) setResourceLoading(false) }
  }

  useEffect(() => {
    if (!action && !detail) return
    const focus = window.requestAnimationFrame(() => {
      if (action) modalRef.current?.querySelector<HTMLElement>('input, textarea, button')?.focus()
    })
    const keydown = (event: KeyboardEvent) => {
      if (event.key === 'Escape' && !submitting) {
        if (action) setAction(null)
        else closeDetail()
      }
      if (event.key === 'Tab' && action && modalRef.current) {
        const focusable = [...modalRef.current.querySelectorAll<HTMLElement>('button:not(:disabled), input:not(:disabled), textarea:not(:disabled), select:not(:disabled)')]
        if (!focusable.length) return
        const first = focusable[0]; const last = focusable[focusable.length - 1]
        if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last.focus() }
        else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first.focus() }
      }
    }
    addEventListener('keydown', keydown)
    return () => { cancelAnimationFrame(focus); removeEventListener('keydown', keydown) }
  }, [action, detail, submitting])

  const submit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    if (!action) return
    const form = new FormData(event.currentTarget)
    const reason = String(form.get('reason') || '').trim()
    setSubmitting(true); setError(null)
    try {
      let result: CommandResult
      if (action === 'create') {
        result = await api.createUser({
          loginName: String(form.get('loginName') || '').trim(),
          displayName: String(form.get('displayName') || '').trim(),
          organizationName: String(form.get('organizationName') || '').trim(),
          organizationSlug: String(form.get('organizationSlug') || '').trim(),
          reason,
        })
      } else {
        if (!detail) return
        result = action === 'suspend' ? await api.suspendUser(detail.user.id, reason)
          : action === 'restore' ? await api.restoreUser(detail.user.id, reason)
          : action === 'preflight' ? await api.deletionPreflight(detail.user.id, reason)
          : await api.deleteUser(detail.user.id, reason)
      }
      setAction(null); await load()
      if (detail) await openUser({ ...detail.user, status: result.state === 'SUCCEEDED' ? detail.user.status : detail.user.status })
      setCommand(result)
    } catch (reasonValue) { setError(reasonValue instanceof Error ? reasonValue.message : 'Command failed') }
    finally { setSubmitting(false) }
  }

  return <section className="page-view">
    <header className="page-head"><div><h1>{tx('usersTitle')}</h1></div></header>
    {error && <div className="page-error" role="alert"><span>{errorText(error, tx)}</span><button onClick={() => void load()}>{tx('retry')}</button></div>}
    {command && <div className={`command-result ${command.state.toLowerCase()}`}><div><span>{tx(command.operation)}</span><b>{tx(command.state)}</b><small>{command.commandId}</small></div>{command.activationToken && <div className="activation"><strong>{tx('activationWarning')}</strong><code>{command.activationToken}</code></div>}{deletionBlockers(command.result).length > 0 && <div className="blocker-list"><strong>{tx('preflightBlockers')}</strong>{deletionBlockers(command.result).map((blocker, index) => <span key={`${blocker.code}:${index}`}><b>{blocker.code}</b><small>{blocker.resourceType} · {blocker.count}</small></span>)}</div>}{command.safeErrorCode && <em>{command.safeErrorCode}</em>}</div>}
    <div className="toolbar"><label className="search-box"><span>⌕</span><input value={query} onChange={(event) => { setPage(0); setQuery(event.target.value) }} placeholder={tx('searchUsers')} aria-label={tx('searchUsers')} /></label><select value={status} onChange={(event) => { setPage(0); setStatus(event.target.value) }} aria-label={tx('status')}><option value="">{tx("ALL STATUS")}</option><option value="ACTIVE">{tx("ACTIVE")}</option><option value="PENDING_ACTIVATION">{tx("PENDING_ACTIVATION")}</option><option value="SUSPENDED">{tx("SUSPENDED")}</option><option value="DELETION_PENDING">{tx("DELETION_PENDING")}</option><option value="DELETED">{tx("DELETED")}</option></select><button className="primary" onClick={() => setAction('create')}>{tx('createUser')}</button></div>
    <div className="data-surface"><div className="data-table users-table"><div className="data-row heading"><span>{tx('user')}</span><span>{tx('status')}</span><span>{tx('memberships')}</span><span>{tx('lastSeen')}</span><span>{tx('sessions')}</span><span></span></div>{!error && users.map((user) => <button className="data-row" key={user.id} onClick={() => void openUser(user)}><span className="identity"><i>{initials(user)}</i><span><b>{user.displayName || user.loginName}</b><small>{user.loginName}</small></span></span><span className={`state ${statusTone(user.status)}`}>{tx(user.status)}</span><span>{user.membershipCount}</span><span>{time(user.lastSeenAt)}</span><span>{user.activeRefreshSessions}</span><span>›</span></button>)}{!loading && !error && !users.length && <div className="empty-state">{tx('noUsers')}</div>}</div><footer className="pagination"><span>{loading || error ? tx(error ? 'dataUnavailable' : 'loading') : `${page * 25 + (users.length ? 1 : 0)}–${page * 25 + users.length} / ${total}`}</span><div><button disabled={page === 0 || loading} onClick={() => setPage((value) => value - 1)}>← {tx('previous')}</button><button disabled={(page + 1) * 25 >= total || loading} onClick={() => setPage((value) => value + 1)}>{tx('next')} →</button></div></footer></div>

    <div className={`drawer-layer ${detail || detailLoading ? 'open' : ''}`}><button className="drawer-scrim" aria-label={tx("Close")} onClick={closeDetail} /><aside className="drawer" role="dialog" aria-modal="true"><header><span>{tx("PLATFORM USER /")}{detail?.user.id.slice(0, 8) || '—'}</span><button aria-label={tx("Close")} onClick={closeDetail}>×</button></header>{detailLoading && !detail ? <div className="page-loading"><i />{tx('loading')}</div> : detail && <>
      <div className="profile"><div><h2>{detail.user.displayName || detail.user.loginName}</h2><p>{detail.user.displayName && detail.user.displayName !== detail.user.loginName && <>{detail.user.loginName} · </>}<span className={statusTone(detail.user.status)}>{tx(detail.user.status)}</span></p></div></div>
      <div className="detail-grid"><div><span>{tx("LAST SEEN")}</span><b>{time(detail.user.lastSeenAt)}</b></div><div><span>{tx("ACTIVE SESSIONS")}</span><b>{detail.user.activeRefreshSessions}</b></div><div><span>{tx("SUCCESSFUL LOGINS")}</span><b>{detail.user.successfulLoginCount}</b></div><div><span>{tx("MUST CHANGE PASSWORD")}</span><b>{tx(detail.user.mustChangePassword ? 'YES' : 'NO')}</b></div></div>
      <section className="drawer-section"><header><b>{tx('memberships')}</b><span>{detail.memberships.length}</span></header>{detail.memberships.map((membership) => <div className="membership" key={membership.organizationId}><span><b>{membership.organizationName}</b><small>{membership.organizationStatus}</small></span><em>{tx(membership.role)}</em></div>)}</section>
      <section className="drawer-section"><header><b>{tx('resourceAssociation')}</b></header><div className="association-groups"><article><header><span>{tx('providerResources')}</span><b>{providers?.total ?? 0}</b></header><div className="linked-list">{providers?.items.map((provider) => <div key={provider.id}><span><b>{provider.name}</b><small>{provider.endpointHost || provider.authType || provider.id}</small></span><em className={provider.status === 'ACTIVE' ? 'good' : 'warn'}>{tx(provider.status)}</em></div>)}{providers && !providers.items.length && <p>—</p>}</div>{providers && providers.total > providers.pageSize && <div className="mini-pagination"><button disabled={providers.page === 0} onClick={() => void changeResourcePage('providers', providers.page - 1)}>←</button><span>{providers.page + 1} / {Math.ceil(providers.total / providers.pageSize)}</span><button disabled={(providers.page + 1) * providers.pageSize >= providers.total} onClick={() => void changeResourcePage('providers', providers.page + 1)}>→</button></div>}</article><article><header><span>{tx('activeAgent')}</span><b>{agents?.total ?? 0}</b></header><div className="linked-list">{agents?.items.map((agent) => <div key={agent.id}><span><b>{agent.name}</b><small>{agent.description || agent.id}</small></span><em className={agent.status === 'ACTIVE' ? 'good' : 'warn'}>{tx(agent.status)}</em></div>)}{agents && !agents.items.length && <p>—</p>}</div>{agents && agents.total > agents.pageSize && <div className="mini-pagination"><button disabled={agents.page === 0} onClick={() => void changeResourcePage('agents', agents.page - 1)}>←</button><span>{agents.page + 1} / {Math.ceil(agents.total / agents.pageSize)}</span><button disabled={(agents.page + 1) * agents.pageSize >= agents.total} onClick={() => void changeResourcePage('agents', agents.page + 1)}>→</button></div>}</article></div></section>
      <section className="drawer-section user-resource-section"><header><b>{tx('resourceDrilldown')}</b></header><div className="resource-kind-grid">{resourceKinds.map(([kind, countKey, labelKey]) => <button key={kind} className={resourceKind === kind ? 'active' : ''} onClick={() => void loadResourceKind(kind)}><span>{tx(labelKey)}</span><b>{resourceOverview?.[countKey] ?? 0}</b></button>)}</div><div className="resource-detail-list">{resourceLoading && <p>{tx('loading')}</p>}{!resourceLoading && resourcePage?.items.map((resource) => <div key={`${resource.kind}:${resource.id}`}><span><b>{resource.displayName || tx(resourceKinds.find(([kind]) => kind === resource.kind)?.[2] || resource.kind)}</b><small>{resource.id}{resource.relation ? ` · ${resource.relation}` : ''}{resource.parentId ? ` · parent ${resource.parentId}` : ''}</small></span><span><em className={resource.state === 'ACTIVE' || resource.state === 'COMPLETED' || resource.state === 'SUCCEEDED' ? 'good' : resource.state === 'FAILED' || resource.state === 'UNKNOWN' ? 'bad' : 'warn'}>{tx(resource.state)}</em>{resource.safeErrorCode && <small className="bad">{resource.safeErrorCode}</small>}{resourceMetrics(resource, tx) && <small>{resourceMetrics(resource, tx)}</small>}</span></div>)}{!resourceLoading && resourcePage && !resourcePage.items.length && <p>—</p>}</div>{resourcePage && resourcePage.total > resourcePage.pageSize && <div className="mini-pagination"><button disabled={resourcePage.page === 0 || resourceLoading} onClick={() => void loadResourceKind(resourceKind, resourcePage.page - 1)}>←</button><span>{resourcePage.page + 1} / {Math.ceil(resourcePage.total / resourcePage.pageSize)}</span><button disabled={(resourcePage.page + 1) * resourcePage.pageSize >= resourcePage.total || resourceLoading} onClick={() => void loadResourceKind(resourceKind, resourcePage.page + 1)}>→</button></div>}<p className="contract-note">{tx('resourceContentBoundary')}</p></section>
      {cleanup && <section className="drawer-section"><header><b>{tx('cleanupJob')}</b><span>{tx(cleanup.job.state)}</span></header><div className="cleanup-steps">{cleanup.steps.map((step) => <div key={step.stepKey}><i className={cleanupStepTone(step.state)} /><span><b>{step.stepKey}</b><small>{tx(step.state)}{tx("· attempt")}{step.attempt}</small></span></div>)}</div></section>}
      <section className="drawer-section"><header><b>{tx("CONTROLLED ACTIONS")}</b><span>{tx('authenticatedSessionRequired')}</span></header><div className="action-grid"><button disabled={detail.user.status !== 'ACTIVE'} onClick={() => setAction('suspend')}>{tx('suspend')}</button><button disabled={detail.user.status !== 'SUSPENDED'} onClick={() => setAction('restore')}>{tx('restore')}</button><button className="danger" disabled={detail.user.status !== 'SUSPENDED'} onClick={() => setAction('preflight')}>{tx('deletionPreflight')}</button><button className="danger" disabled={detail.user.status !== 'SUSPENDED' || !isDeletionEligible(command)} onClick={() => setAction('delete')}>{tx('requestDeletion')}</button></div>{detail.user.status === 'SUSPENDED' && !isDeletionEligible(command) && <p className="contract-note">{tx('preflightRequired')}</p>}</section>
    </>}</aside></div>

    {action && <div className="modal-layer"><form ref={modalRef} className="modal" role="dialog" aria-modal="true" aria-labelledby="admin-command-title" onSubmit={submit}><h2 id="admin-command-title">{action === 'create' ? tx('createUser') : action === 'suspend' ? tx('suspend') : action === 'restore' ? tx('restore') : action === 'preflight' ? tx('deletionPreflight') : tx('requestDeletion')}</h2>{action === 'create' && <div className="form-grid"><label><span>{tx("LOGIN NAME")}</span><input name="loginName" type="email" required maxLength={255} /></label><label><span>{tx("DISPLAY NAME")}</span><input name="displayName" maxLength={120} /></label><label><span>{tx("PERSONAL ORGANIZATION")}</span><input name="organizationName" maxLength={120} /></label><label><span>{tx("ORGANIZATION SLUG")}</span><input name="organizationSlug" pattern="[a-z0-9-]+" maxLength={63} /></label></div>}<label><span>{tx('operationReason')}</span><textarea name="reason" required maxLength={500} /></label><div className="modal-actions"><button type="button" onClick={() => setAction(null)}>{tx('cancel')}</button><button className={action === 'preflight' || action === 'delete' ? 'danger' : 'primary'} disabled={submitting}>{submitting ? tx('loading') : tx('confirm')}</button></div></form></div>}
  </section>
}
