import { useCallback, useEffect, useMemo, useRef, useState, type FormEvent } from 'react'
import type { AuthenticatedRequest } from '../overview/types'
import {
  beginGithubOAuth,
  beginMcpOAuth,
  completeGithubOAuth,
  completeMcpOAuth,
  connectMcp,
  disableMcp,
  installMcp,
  loadMcpObservations,
  loadMcpQualification,
  loadMcpRegistry,
  loadMcpVersions,
  qualifyMcp,
  revokeMcp,
} from './mcpApi'
import type {
  McpConnection,
  McpConnectionObservation,
  McpEntry,
  McpInstallation,
  McpQualification,
  McpServerVersion,
} from './types'
import './mcp.css'
import { oauthActionLabel } from './mcpReauthorization'

type Props = { request: AuthenticatedRequest; tx: (key: string) => string }
type Dialog = 'install' | 'connect' | 'status' | null
type OAuthFlow = 'github' | 'generic'

const oauthFlowKey = 'spaceagent-mcp-oauth-flow'
const capabilities = (entry: McpEntry) => {
  try {
    const value = JSON.parse(entry.manifestJson) as { capabilities?: unknown }
    return Array.isArray(value.capabilities) ? value.capabilities.map(String) : []
  } catch { return [] }
}
const stamp = (value: string | null) => value ? new Date(value).toLocaleString() : '—'

export function McpPage({ request, tx }: Props) {
  const [catalog, setCatalog] = useState<McpEntry[]>([])
  const [installations, setInstallations] = useState<McpInstallation[]>([])
  const [connections, setConnections] = useState<McpConnection[]>([])
  const [versions, setVersions] = useState<McpServerVersion[]>([])
  const [selectedVersionId, setSelectedVersionId] = useState('')
  const [qualification, setQualification] = useState<McpQualification | null>(null)
  const [observations, setObservations] = useState<McpConnectionObservation[]>([])
  const [search, setSearch] = useState('')
  const [selectedEntry, setSelectedEntry] = useState<McpEntry | null>(null)
  const [selectedInstallation, setSelectedInstallation] = useState<McpInstallation | null>(null)
  const [selectedConnection, setSelectedConnection] = useState<McpConnection | null>(null)
  const [dialog, setDialog] = useState<Dialog>(null)
  const [confirmTarget, setConfirmTarget] = useState<{ kind: 'installation' | 'connection'; id: string; name: string } | null>(null)
  const [authType, setAuthType] = useState<McpEntry['authType']>('NONE')
  const [busy, setBusy] = useState(false)
  const [loading, setLoading] = useState(true)
  const [versionLoading, setVersionLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const versionRequest = useRef(0)
  const filtered = useMemo(() => catalog.filter((entry) => `${entry.name} ${entry.description} ${entry.publisherNamespace} ${entry.registryName} ${capabilities(entry).join(' ')}`.toLowerCase().includes(search.trim().toLowerCase())), [catalog, search])
  const activeConnections = connections.filter((connection) => connection.state === 'ACTIVE' && installations.some((installation) => installation.id === connection.installationId && installation.state === 'INSTALLED'))

  const load = useCallback(async () => {
    setLoading(true); setError(null)
    try {
      const registry = await loadMcpRegistry(request)
      setCatalog(registry.catalog); setInstallations(registry.installations); setConnections(registry.connections)
    } catch (reason) { setError(reason instanceof Error ? reason.message : tx('mcpLoadFailed')) }
    finally { setLoading(false) }
  }, [request, tx])

  useEffect(() => { void load() }, [load])
  useEffect(() => {
    const parameters = new URLSearchParams(window.location.search)
    const state = parameters.get('state'); const code = parameters.get('code'); const oauthError = parameters.get('error')
    if (!state || (!code && !oauthError)) return
    const flow = window.sessionStorage.getItem(oauthFlowKey) as OAuthFlow | null
    setBusy(true)
    const completion = flow === 'generic'
      ? completeMcpOAuth(request, { state, code, error: oauthError })
      : code && !oauthError ? completeGithubOAuth(request, state, code) : Promise.reject(new Error(tx('mcpOAuthFailed')))
    completion.then(() => load())
      .catch((reason) => setError(reason instanceof Error ? reason.message : tx(flow === 'generic' ? 'mcpOAuthFailed' : 'githubOAuthFailed')))
      .finally(() => {
        window.sessionStorage.removeItem(oauthFlowKey)
        window.history.replaceState(null, '', '/app/mcp')
        setBusy(false)
      })
  }, [load, request, tx])

  const installedFor = (entryId: string) => installations.find((installation) => installation.entryId === entryId && installation.state === 'INSTALLED')
  const connectionFor = (installationId: string) => connections.find((connection) => connection.installationId === installationId && connection.state !== 'REVOKED')
  const entryForInstallation = (installation: McpInstallation) => catalog.find(({ id }) => id === installation.entryId)

  const openInstall = async (entry: McpEntry) => {
    const current = ++versionRequest.current
    setSelectedEntry(entry); setVersions([]); setSelectedVersionId(''); setVersionLoading(true); setDialog('install'); setError(null)
    try {
      const rows = (await loadMcpVersions(request, entry.id)).filter((version) => version.lifecycleState === 'APPROVED')
      if (current !== versionRequest.current) return
      setVersions(rows)
      setSelectedVersionId(rows.some(({ id }) => id === entry.currentVersionId) ? entry.currentVersionId || '' : rows[0]?.id || '')
    } catch (reason) { if (current === versionRequest.current) setError(reason instanceof Error ? reason.message : tx('mcpVersionsFailed')) }
    finally { if (current === versionRequest.current) setVersionLoading(false) }
  }
  const openConnect = (installation: McpInstallation) => {
    const entry = entryForInstallation(installation)
    if (!entry) return
    const currentConnection = connectionFor(installation.id)
    setSelectedEntry(entry); setSelectedInstallation(installation); setSelectedConnection(currentConnection || null); setAuthType(currentConnection?.authType || entry.authType); setDialog('connect'); setError(null)
  }

  const submitInstall = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault(); if (!selectedEntry || !selectedVersionId) return; const data = new FormData(event.currentTarget); setBusy(true); setError(null)
    try {
      const installation = await installMcp(request, { entryId: selectedEntry.id, serverVersionId: selectedVersionId, scope: String(data.get('scope')) as McpInstallation['scope'], displayName: String(data.get('displayName') ?? selectedEntry.name).trim() })
      setInstallations((rows) => [installation, ...rows.filter(({ id }) => id !== installation.id)])
      setSelectedInstallation(installation); setSelectedConnection(null); setAuthType(selectedEntry.authType); setDialog('connect')
    } catch (reason) { setError(reason instanceof Error ? reason.message : tx('mcpInstallFailed')) }
    finally { setBusy(false) }
  }

  const submitConnection = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault(); if (!selectedInstallation || !selectedEntry) return; const data = new FormData(event.currentTarget); const secret = String(data.get('secret') ?? '')
    const auth: Record<string, string> = authType === 'BEARER' && secret ? { token: secret } : authType === 'CUSTOM' && secret ? { [`header:${String(data.get('headerName') || 'X-API-Key')}`]: secret } : {}
    setBusy(true); setError(null)
    try {
      const connection = await connectMcp(request, { installationId: selectedInstallation.id, endpointUrl: String(data.get('endpointUrl') ?? '').trim(), authType, auth })
      setConnections((rows) => [connection, ...rows.filter(({ id }) => id !== connection.id)])
      setDialog(null)
    } catch (reason) { setError(reason instanceof Error ? reason.message : tx('mcpConnectFailed')) }
    finally { setBusy(false) }
  }

  const startOAuth = async (connection: McpConnection, entry: McpEntry) => {
    if (busy || !oauthActionLabel(connection, entry.slug)) return
    setBusy(true); setError(null)
    try {
      const redirectUri = `${window.location.origin}/app/mcp`
      const flow: OAuthFlow = entry.slug === 'github' ? 'github' : 'generic'
      const oauth = flow === 'github'
        ? await beginGithubOAuth(request, connection.id, redirectUri)
        : await beginMcpOAuth(request, connection.id, redirectUri)
      window.sessionStorage.setItem(oauthFlowKey, flow)
      window.location.assign(oauth.authorizationUrl)
    } catch (reason) { setError(reason instanceof Error ? reason.message : tx(entry.slug === 'github' ? 'githubOAuthFailed' : 'mcpOAuthFailed')); setBusy(false) }
  }

  const inspectConnection = async (connection: McpConnection) => {
    setSelectedConnection(connection); setQualification(null); setObservations([]); setDialog('status'); setBusy(true); setError(null)
    try {
      const [nextQualification, history] = await Promise.all([
        loadMcpQualification(request, connection.id), loadMcpObservations(request, connection.id),
      ])
      setQualification(nextQualification); setObservations(history)
    } catch (reason) { setError(reason instanceof Error ? reason.message : tx('mcpQualificationLoadFailed')) }
    finally { setBusy(false) }
  }

  const runQualification = async (connection: McpConnection) => {
    setBusy(true); setError(null)
    try {
      const result = await qualifyMcp(request, connection.id)
      setQualification(result)
      setConnections((rows) => rows.map((row) => row.id === connection.id ? { ...row, state: result.state } : row))
      setObservations(await loadMcpObservations(request, connection.id))
      setSelectedConnection((current) => current?.id === connection.id ? { ...current, state: result.state } : current)
      setDialog('status')
    } catch (reason) {
      const message = reason instanceof Error ? reason.message : tx('mcpQualificationFailed')
      await load(); setSelectedConnection(null); setDialog(null); setError(message)
    }
    finally { setBusy(false) }
  }

  const confirmLifecycle = async () => {
    if (!confirmTarget || busy) return
    setBusy(true); setError(null)
    try {
      if (confirmTarget.kind === 'connection') {
        const updated = await revokeMcp(request, confirmTarget.id)
        setConnections((rows) => rows.map((connection) => connection.id === updated.id ? updated : connection))
      } else {
        const updated = await disableMcp(request, confirmTarget.id)
        setInstallations((rows) => rows.map((installation) => installation.id === updated.id ? updated : installation))
      }
      setConfirmTarget(null)
    } catch (reason) { setError(reason instanceof Error ? reason.message : tx('mcpLifecycleFailed')) }
    finally { setBusy(false) }
  }

  const customEntry = catalog.find(({ slug }) => slug === 'custom-streamable-http')

  return <section className="mcp-registry">
    <header className="mcp-heading"><div><span>MCP / CAPABILITY REGISTRY</span><h1>{tx('capabilityRegistry')}</h1><p>{tx('capabilityRegistryHint')}</p></div><button disabled={!customEntry} onClick={() => customEntry && void openInstall(customEntry)}>{tx('connectCustomMcp')}</button></header>
    {error && <div className="mcp-error" role="alert"><span>{error}</span><button onClick={() => setError(null)}>×</button></div>}
    <section className="mcp-search"><span>⌕</span><input value={search} onChange={(event) => setSearch(event.target.value)} placeholder={tx('searchMcp')} aria-label={tx('searchMcp')} /><small>{filtered.length} {tx('results')}</small></section>
    <section className="mcp-section"><header><b>{tx('connectedMcp')}</b><span>{activeConnections.length.toString().padStart(2,'0')} / ACTIVE</span></header><div className="mcp-connections">{installations.filter(({ state }) => state === 'INSTALLED').map((installation) => { const entry = entryForInstallation(installation); const connection = connectionFor(installation.id); if (!entry) return null; const canQualify = connection && ['PENDING_VALIDATION', 'ERROR', 'DEGRADED'].includes(connection.state); return <article key={installation.id}><div className="mcp-monogram">{entry.name.slice(0,2).toUpperCase()}</div><div><b>{installation.displayName}</b><small>{entry.name} · V{installation.serverVersion} · {installation.scope}</small></div><span className={`mcp-state state-${connection?.state.toLowerCase() || 'unconnected'}`}>{connection?.state || 'UNCONNECTED'}</span><div className="mcp-actions">{connection && oauthActionLabel(connection, entry.slug) && <button disabled={busy} onClick={() => void startOAuth(connection, entry)}>{tx(oauthActionLabel(connection, entry.slug)!)}</button>}{canQualify && <button disabled={busy} onClick={() => void runQualification(connection)}>{tx('qualifyConnection')}</button>}{connection && <button disabled={busy} onClick={() => void inspectConnection(connection)}>{tx('qualificationEvidence')}</button>}{!connection ? <button onClick={() => openConnect(installation)}>{tx('connect')}</button> : <><button onClick={() => openConnect(installation)}>{tx('editConnection')}</button><button className="danger" onClick={() => setConfirmTarget({ kind: 'connection', id: connection.id, name: installation.displayName })}>{tx('revoke')}</button></>}<button className="danger" onClick={() => setConfirmTarget({ kind: 'installation', id: installation.id, name: installation.displayName })}>{tx('disable')}</button></div></article>})}{!loading && !installations.some(({ state }) => state === 'INSTALLED') && <div className="mcp-empty">{tx('noConnectedMcp')}</div>}</div></section>
    <section className="mcp-section"><header><b>{tx('marketplaceMcp')}</b><span>{filtered.length.toString().padStart(2,'0')}</span></header><div className="mcp-grid">{filtered.map((entry) => { const installed = installedFor(entry.id); const entryCapabilities = capabilities(entry); return <article key={entry.id}><header><span>{entry.name.slice(0,2).toUpperCase()}</span><em>{entry.trustTier} · {entry.currentVersion || '—'}</em></header><h2>{entry.name}</h2><p>{entry.description}</p><div className="mcp-capabilities">{entryCapabilities.slice(0,4).map((capability) => <span key={capability}>{capability.replace(/_/g,' ')}</span>)}</div><footer><small>{entry.registryName} / {entry.authType}</small>{installed ? <button onClick={() => openConnect(installed)}>{connectionFor(installed.id) ? tx('manage') : tx('connect')}</button> : <button onClick={() => void openInstall(entry)}>{tx('install')}</button>}</footer></article>})}</div></section>

    {dialog === 'install' && selectedEntry && <div className="mcp-modal" role="dialog" aria-modal="true"><form className="mcp-dialog" onSubmit={submitInstall}><DialogHeader kicker={`INSTALL / ${selectedEntry.slug}`} title={selectedEntry.name} close={() => { versionRequest.current += 1; setDialog(null) }} />{error && <div className="mcp-inline-error" role="alert">{error}</div>}<p>{selectedEntry.description}</p><div className="mcp-contract-strip"><span>{selectedEntry.trustTier}</span><span>{selectedEntry.sourceType}</span><span>{selectedEntry.registryName}</span></div><label><span>{tx('serverVersion')}</span><select value={selectedVersionId} onChange={(event) => setSelectedVersionId(event.target.value)} required disabled={versionLoading}>{versionLoading ? <option value="">{tx('loading')}</option> : versions.length ? versions.map((version) => <option key={version.id} value={version.id}>{version.version} · {version.lifecycleState} · {version.transports[0]?.transportType || selectedEntry.transport}</option>) : <option value="">{tx('noApprovedVersions')}</option>}</select></label><label><span>{tx('displayName')}</span><input name="displayName" defaultValue={selectedEntry.name} required /></label><label><span>{tx('installationScope')}</span><select name="scope"><option value="USER">USER</option><option value="ORGANIZATION">ORGANIZATION</option></select></label><button className="submit" disabled={busy || versionLoading || !selectedVersionId}>{busy ? tx('saving') : tx('installAndContinue')}</button></form></div>}
    {dialog === 'connect' && selectedEntry && selectedInstallation && <div className="mcp-modal" role="dialog" aria-modal="true"><form className="mcp-dialog" onSubmit={submitConnection}><DialogHeader kicker={`CONNECT / ${selectedEntry.slug} / V${selectedInstallation.serverVersion}`} title={selectedInstallation.displayName} close={() => setDialog(null)} />{error && <div className="mcp-inline-error" role="alert">{error}</div>}<p>{selectedEntry.slug === 'github' ? tx('githubConnectionHint') : tx('customConnectionHint')}</p><label><span>ENDPOINT URL</span><input name="endpointUrl" type="url" defaultValue={selectedConnection?.endpointUrl || selectedEntry.defaultEndpoint || ''} placeholder="https://" required /></label><label><span>{tx('authentication')}</span><select value={authType} onChange={(event) => setAuthType(event.target.value as McpEntry['authType'])}><option value="NONE">NONE</option><option value="OAUTH2">OAUTH2</option><option value="BEARER">BEARER</option><option value="CUSTOM">CUSTOM HEADER</option></select></label>{authType === 'CUSTOM' && <label><span>{tx('headerName')}</span><input name="headerName" defaultValue="X-API-Key" required /></label>}{(authType === 'BEARER' || authType === 'CUSTOM') && <label><span>{tx('connectionSecret')}</span><input name="secret" type="password" autoComplete="off" required /></label>}<p className="mcp-boundary-note">{tx('qualificationRequired')}</p><button className="submit" disabled={busy}>{busy ? tx('saving') : authType === 'OAUTH2' ? tx('createOAuthConnection') : tx('saveConnection')}</button></form></div>}
    {dialog === 'status' && selectedConnection && <div className="mcp-modal" role="dialog" aria-modal="true"><div className="mcp-dialog mcp-status-dialog"><DialogHeader kicker={`CONNECTION / ${selectedConnection.revision}`} title={tx('qualificationEvidence')} close={() => setDialog(null)} />{error && <div className="mcp-inline-error" role="alert">{error}</div>}{busy && !qualification ? <div className="mcp-status-loading">{tx('loading')}</div> : <><div className="mcp-status-grid"><div><span>{tx('connectionStatus')}</span><b>{qualification?.state || selectedConnection.state}</b></div><div><span>{tx('protocolVersion')}</span><b>{qualification?.protocolVersion || '—'}</b></div><div><span>{tx('serverIdentity')}</span><b>{qualification?.serverTitle || qualification?.serverName || '—'}</b></div><div><span>{tx('toolCount')}</span><b>{qualification?.toolCount ?? 0}</b></div><div><span>{tx('serverVersion')}</span><b>{qualification?.serverVersion || '—'}</b></div><div><span>{tx('qualifiedAt')}</span><b>{stamp(qualification?.qualifiedAt || null)}</b></div></div><section className="mcp-observations"><header><b>{tx('healthHistory')}</b><span>{observations.length}</span></header>{observations.map((observation) => <div key={observation.id}><span><b>{observation.outcome}</b><small>{stamp(observation.observedAt)} · REV {observation.connectionRevision}</small></span><span><b>{observation.latencyMs} ms</b><small>{observation.safeErrorCode || observation.protocolVersion || '—'}</small></span></div>)}{!observations.length && <p>{tx('neverQualified')}</p>}</section>{selectedConnection.state !== 'PENDING_AUTH' && selectedConnection.state !== 'REVOKED' && <button className="submit" disabled={busy} onClick={() => void runQualification(selectedConnection)}>{busy ? tx('processing') : tx('qualifyConnection')}</button>}</>}</div></div>}
    {confirmTarget && <div className="mcp-modal confirm-layer" role="dialog" aria-modal="true"><div className="mcp-dialog"><DialogHeader kicker={tx('systemConfirmation')} title={tx(confirmTarget.kind === 'connection' ? 'confirmRevokeConnection' : 'confirmDisableMcp')} close={() => setConfirmTarget(null)} />{error && <div className="mcp-inline-error" role="alert">{error}</div>}<p>{confirmTarget.name}</p><div className="mcp-confirm-actions"><button onClick={() => setConfirmTarget(null)}>{tx('cancel')}</button><button className="danger" disabled={busy} onClick={() => void confirmLifecycle()}>{busy ? tx('processing') : tx('confirm')}</button></div></div></div>}
  </section>
}

function DialogHeader({ kicker, title, close }: { kicker: string; title: string; close: () => void }) {
  return <header className="mcp-dialog-header"><div><span>{kicker}</span><h2>{title}</h2></div><button type="button" aria-label="Close" onClick={close}>×</button></header>
}
