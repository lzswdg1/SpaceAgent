import { useEffect, useState } from 'react'
import type { AuthenticatedRequest } from '../chat/types'
import type { SourceRepository, Workspace } from './types'
import { loadRepositoryBranches, loadRepositoryEnvironment, loadRepositoryFile, loadRepositoryFiles, type RepositoryEnvironment, type RepositoryEntry, type RepositoryFile } from './workbenchApi'
import './repository-workbench.css'
import { repositoryBranchOptions, visibleRepositoryBranch } from './repositoryBranches'

type Props = { request: AuthenticatedRequest; projectId: string; sourceId: string | null; sources?: Pick<SourceRepository, 'id' | 'displayName'>[]; onSource?: (sourceId: string) => void; workspace: Workspace | null; busy: boolean; refreshKey: unknown; onBranch: (branch: string, sourceId: string) => void; onPrepare: () => void; tx: (key: string) => string }
function WorkbenchIcon({ kind }: { kind: 'changes' | 'environment' | 'branch' | 'files' }) {
  const path = kind === 'branch' ? 'M6 6v12m0-6h6a6 6 0 0 0 6-6M4 4h4v4H4zM4 16h4v4H4zM16 3h4v4h-4z'
    : kind === 'environment' ? 'M4 4h16v13H4zM2 20h20'
    : kind === 'files' ? 'M3 7h7l2-3h9v16H3z'
    : 'M6 3h9l4 4v14H6zM10 11h5m-5 4h5'
  return <svg className="workbench-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><path d={path}/></svg>
}
export function RepositoryWorkbench({ request, projectId, sourceId, sources = [], onSource, workspace, busy, refreshKey, onBranch, onPrepare, tx }: Props) {
  const [environment, setEnvironment] = useState<RepositoryEnvironment | null>(null)
  const sourceKey = `${projectId}/${sourceId ?? ''}`
  const [branchResult, setBranchResult] = useState<{ key: string; items: string[]; loading: boolean; error: string | null }>({ key: '', items: [], loading: false, error: null })
  const branches = branchResult.key === sourceKey ? branchResult.items : []
  const branchesLoading = Boolean(sourceId) && (branchResult.key !== sourceKey || branchResult.loading)
  const branchError = branchResult.key === sourceKey ? branchResult.error : null
  const [refresh, setRefresh] = useState(0)
  const [error, setError] = useState<string | null>(null)
  const [browsing, setBrowsing] = useState(false)
  const [diff, setDiff] = useState(false)
  const [folder, setFolder] = useState('.')
  const [entries, setEntries] = useState<RepositoryEntry[]>([])
  const [selectedFile, setSelectedFile] = useState<string | null>(null)
  const [file, setFile] = useState<RepositoryFile | null>(null)
  const [pendingSelection, setPendingSelection] = useState<{ key: string; branch: string } | null>(null)
  const pendingBranch = pendingSelection?.key === sourceKey && visibleRepositoryBranch(pendingSelection.branch) ? pendingSelection.branch : ''
  const selectedBaseRef = visibleRepositoryBranch(workspace?.baseRef) ? workspace.baseRef : ''
  useEffect(() => { setSelectedFile(null); setFile(null); setFolder('.'); setEnvironment(null); setPendingSelection(null) }, [workspace?.id, projectId, sourceId])
  useEffect(() => {
    let active=true; setError(null)
    if (!workspace) return
    loadRepositoryEnvironment(request, projectId, workspace.id).then((value) => active && setEnvironment(value)).catch((e) => active && setError(e.message))
    return () => { active=false }
  }, [request, projectId, workspace?.id, refresh, refreshKey])
  useEffect(() => {
    let active=true
    setBranchResult({ key: sourceKey, items: [], loading: Boolean(sourceId), error: null })
    if (!sourceId) return
    loadRepositoryBranches(request, projectId, sourceId)
      .then((value) => active && setBranchResult({ key: sourceKey, items: value, loading: false, error: null }))
      .catch((e) => active && setBranchResult({ key: sourceKey, items: [], loading: false, error: e.message }))
    return () => { active=false }
  }, [request, projectId, sourceId, sourceKey, refresh, workspace?.id])
  useEffect(() => {
    let active=true; setEntries([])
    if (!browsing || !workspace) return
    loadRepositoryFiles(request, projectId, workspace.id, folder).then((value) => { if(active) {setEntries(value.entries); if(value.truncated) setError(tx('repositoryListTruncated'))} }).catch((e) => active && setError(e.message))
    return () => {active=false}
  }, [request, projectId, workspace?.id, browsing, folder, refresh, tx])
  useEffect(() => {
    let active=true; setFile(null)
    if (!selectedFile || !workspace || !browsing) return
    loadRepositoryFile(request, projectId, workspace.id, selectedFile).then((value) => active && setFile(value)).catch((e) => active && setError(e.message))
    return () => {active=false}
  }, [request, projectId, workspace?.id, selectedFile, browsing, refresh])
  const additions = environment?.patch.split('\n').filter((line) => line.startsWith('+') && !line.startsWith('+++')).length ?? 0
  const deletions = environment?.patch.split('\n').filter((line) => line.startsWith('-') && !line.startsWith('---')).length ?? 0
  return <section className="repository-environment">
    <header><h3>{tx('environmentInfo')}</h3><button type="button" aria-label={tx('refresh')} onClick={() => setRefresh((v) => v+1)}>↻</button></header>
    <button className="environment-row" disabled={!environment} onClick={() => setDiff(!diff)}><span><WorkbenchIcon kind="changes"/>{tx('changes')}</span><span className="change-count"><b>+{additions}</b> <i>−{deletions}</i></span></button>
    <div className="environment-row"><span><WorkbenchIcon kind="environment"/>{tx('executionEnvironment')}</span><b>{tx('isolatedWorkspace')}</b></div>
    {onSource && <label className="environment-branch"><span>{tx('sourceRepositories')}</span><select aria-label={tx('sourceRepositories')} disabled={busy || !sources.length} value={sourceId || ''} onChange={(event) => onSource(event.target.value)}><option value="">{tx('workbenchSelectRepository')}</option>{sources.map((source) => <option key={source.id} value={source.id}>{source.displayName}</option>)}</select></label>}
    <label className="environment-branch"><span><WorkbenchIcon kind="branch"/>{tx('codeBranch')}</span><select aria-label={tx('codeBranch')} aria-describedby="workbench-branch-status" disabled={busy || branchesLoading || !branches.length} value={pendingBranch || selectedBaseRef} onChange={(event) => setPendingSelection({ key: sourceKey, branch: event.target.value })}><option value="">{tx(branchesLoading ? 'workbenchLoadingBranches' : 'selectBranch')}</option>{repositoryBranchOptions(branches, selectedBaseRef).map((branch) => <option key={branch} value={branch}>{branch}</option>)}</select></label>
    <div id="workbench-branch-status" className="branch-load-status" role={branchError ? 'alert' : 'status'}>{busy ? tx('workbenchBranchBusy') : !sourceId ? tx(sources.length ? 'workbenchSelectRepository' : 'repositorySourceRequired') : branchesLoading ? tx('workbenchLoadingBranches') : branchError ? <>{branchError} <button type="button" onClick={() => setRefresh((v) => v+1)}>{tx('retry')}</button></> : !branches.length ? tx('workbenchBranchesNotLoaded') : !workspace ? tx('workbenchChooseBranchToPrepare') : ''}</div>
    {sourceId && pendingBranch && pendingBranch !== workspace?.baseRef && <div className="branch-confirm"><p>{tx('branchIsolationHint')}</p><button disabled={busy || branchesLoading} onClick={() => onBranch(pendingBranch, sourceId)}>{tx(workspace ? 'switchBranch' : 'repositoryPrepare')}</button><button onClick={() => setPendingSelection(null)}>{tx('cancel')}</button></div>}
    {environment && <small className="repository-commit">{environment.sourceName} · {environment.headCommit.slice(0,8)}</small>}
    {!workspace && <button className="environment-row" onClick={onPrepare}>{tx('repositoryPrepare')}</button>}
    <button className="environment-row" disabled={!workspace} aria-expanded={browsing} onClick={() => setBrowsing(!browsing)}><span><WorkbenchIcon kind="files"/>{tx('repositoryFiles')}</span><span>{browsing?'−':'＋'}</span></button>
    {error && <p role="alert" className="workbench-error">{error}</p>}
    {diff && environment && <div className="repository-diff"><b>{environment.changedFiles.length} {tx('changedFiles')}</b>{environment.changedFiles.map((path) => <button key={path} onClick={() => {setBrowsing(true);setSelectedFile(path)}}>{path}</button>)}<pre>{environment.patch || tx('noCodeChanges')}</pre></div>}
    {browsing && <div className="repository-browser"><header><button onClick={() => setFolder(folder.includes('/') ? folder.slice(0,folder.lastIndexOf('/')) : '.')}>↑</button><span>{folder}</span></header><div className="repository-file-list">{entries.map((entry) => <button className={selectedFile===entry.path?'active':''} key={entry.path} onClick={() => entry.directory?setFolder(entry.path):setSelectedFile(entry.path)}>{entry.directory?'▱':'▤'} {entry.path.split('/').slice(-1)[0]}</button>)}</div>
      {selectedFile && <section className="repository-file-view"><header><b>{selectedFile}</b><button onClick={() => setSelectedFile(null)} aria-label={tx('close')}>×</button></header>{file ? <><pre><code>{file.content}</code></pre>{file.truncated && <small>{tx('repositoryFileTruncated')}</small>}</> : <p>{tx('loading')}</p>}</section>}
    </div>}
  </section>
}
