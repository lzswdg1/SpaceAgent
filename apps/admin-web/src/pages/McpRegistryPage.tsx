import { errorText } from '../errorText'
import { useCallback, useEffect, useRef, useState, type FormEvent } from 'react'
import type { AdminApi } from '../adminApi'
import type { McpRegistryCandidate, McpRegistryCandidateDetail, McpRegistrySyncJob } from '../types'

type Props = { api: AdminApi; tx: (key: string) => string }
type Action = 'sync' | 'approve' | 'reject'
const stamp = (value: string | null) => value ? new Date(value).toLocaleString() : '—'

export function McpRegistryPage({ api, tx }: Props) {
  const [jobs, setJobs] = useState<McpRegistrySyncJob[]>([])
  const [candidates, setCandidates] = useState<McpRegistryCandidate[]>([])
  const [jobTotal, setJobTotal] = useState(0)
  const [candidateTotal, setCandidateTotal] = useState(0)
  const [jobPage, setJobPage] = useState(0)
  const [candidatePage, setCandidatePage] = useState(0)
  const [jobState, setJobState] = useState('')
  const [candidateState, setCandidateState] = useState('PENDING_REVIEW')
  const [query, setQuery] = useState('')
  const [detail, setDetail] = useState<McpRegistryCandidateDetail | null>(null)
  const [action, setAction] = useState<Action | null>(null)
  const [loading, setLoading] = useState(true)
  const [submitting, setSubmitting] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const requestVersion = useRef(0)
  const modal = useRef<HTMLFormElement>(null)

  const load = useCallback(async () => {
    const version = ++requestVersion.current
    setLoading(true); setError(null)
    try {
      const [jobResult, candidateResult] = await Promise.all([
        api.mcpRegistrySyncJobs({ page: jobPage, pageSize: 10, state: jobState || undefined }),
        api.mcpRegistryCandidates({ page: candidatePage, pageSize: 25, state: candidateState || undefined, query: query.trim() || undefined }),
      ])
      if (version !== requestVersion.current) return
      setJobs(jobResult.items); setJobTotal(jobResult.total)
      setCandidates(candidateResult.items); setCandidateTotal(candidateResult.total)
    } catch (reason) { if (version === requestVersion.current) setError(reason instanceof Error ? reason.message : tx('mcpRegistryLoadFailed')) }
    finally { if (version === requestVersion.current) setLoading(false) }
  }, [api, candidatePage, candidateState, jobPage, jobState, query, tx])

  useEffect(() => {
    const timer = window.setTimeout(() => void load(), query ? 250 : 0)
    return () => { clearTimeout(timer); requestVersion.current += 1 }
  }, [load, query])

  useEffect(() => {
    if (!action) return
    const frame = requestAnimationFrame(() => modal.current?.querySelector<HTMLElement>('textarea,input,button')?.focus())
    const key = (event: KeyboardEvent) => {
      if (event.key === 'Escape' && !submitting) setAction(null)
      if (event.key === 'Tab' && modal.current) {
        const controls = [...modal.current.querySelectorAll<HTMLElement>('button:not(:disabled),input:not(:disabled),textarea:not(:disabled)')]
        if (!controls.length) return
        const first = controls[0]; const last = controls[controls.length - 1]
        if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last.focus() }
        else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first.focus() }
      }
    }
    addEventListener('keydown', key)
    return () => { cancelAnimationFrame(frame); removeEventListener('keydown', key) }
  }, [action, submitting])

  const inspect = async (candidate: McpRegistryCandidate) => {
    setError(null)
    try { setDetail(await api.mcpRegistryCandidate(candidate.id)) }
    catch (reason) { setError(reason instanceof Error ? reason.message : tx('mcpRegistryLoadFailed')) }
  }

  const submit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault(); if (!action) return
    const form = new FormData(event.currentTarget)
    const reason = String(form.get('reason') || '').trim()
    setSubmitting(true); setError(null)
    try {
      if (action === 'sync') await api.requestMcpRegistrySync(reason)
      else if (detail && action === 'approve') await api.approveMcpRegistryCandidate(detail.candidate.id, reason)
      else if (detail) await api.rejectMcpRegistryCandidate(detail.candidate.id, reason)
      setAction(null); await load()
      if (detail && action !== 'sync') setDetail(await api.mcpRegistryCandidate(detail.candidate.id))
    } catch (reasonValue) { setError(reasonValue instanceof Error ? reasonValue.message : tx('mcpRegistryCommandFailed')) }
    finally { setSubmitting(false) }
  }

  const latestJob = jobs[0] ?? null

  return <section className="page-view">
    <header className="page-head"><div><h1>{tx('mcpRegistryTitle')}</h1><p>{tx('mcpRegistryCopy')}</p></div></header>
    {error && <div className="page-error" role="alert"><span>{errorText(error, tx)}</span><button onClick={() => void load()}>{tx('retry')}</button></div>}
    <div className="registry-command-strip"><div><span>{tx('latestSync')}</span><b>{latestJob ? tx(latestJob.state) : '—'}</b><small>{latestJob ? `${latestJob.fetchedCount} FETCHED · ${latestJob.candidateCount} CANDIDATES · ${stamp(latestJob.updatedAt)}` : tx('noRegistryJobs')}</small></div><button className="primary" disabled={submitting} onClick={() => setAction('sync')}>{tx('requestRegistrySync')}</button></div>
    <div className="registry-toolbar"><label className="search-box"><span>⌕</span><input value={query} onChange={(event) => { setCandidatePage(0); setQuery(event.target.value) }} placeholder={tx('searchRegistryCandidates')} aria-label={tx('searchRegistryCandidates')} /></label><select value={candidateState} onChange={(event) => { setCandidatePage(0); setCandidateState(event.target.value) }} aria-label={tx('reviewState')}><option value="">{tx("ALL REVIEW STATES")}</option><option value="PENDING_REVIEW">{tx("PENDING_REVIEW")}</option><option value="APPROVED">{tx("APPROVED")}</option><option value="REJECTED">{tx("REJECTED")}</option></select><select value={jobState} onChange={(event) => { setJobPage(0); setJobState(event.target.value) }} aria-label={tx('syncState')}><option value="">{tx("ALL JOB STATES")}</option><option value="PENDING">{tx("PENDING")}</option><option value="RUNNING">{tx("RUNNING")}</option><option value="SUCCEEDED">{tx("SUCCEEDED")}</option><option value="FAILED">{tx("FAILED")}</option></select></div>
    <div className="registry-layout">
      <section className="data-surface"><header className="registry-surface-head"><b>{tx('reviewCandidates')}</b></header><div className="data-table registry-candidate-table"><div className="data-row heading"><span>{tx("MCP SERVER")}</span><span>{tx('reviewState')}</span><span>{tx("COMPATIBILITY")}</span><span>{tx("UPDATED")}</span></div>{candidates.map((candidate) => <button className="data-row" key={candidate.id} onClick={() => void inspect(candidate)}><span className="identity"><i>{candidate.registryName.slice(0, 2).toUpperCase()}</i><span><b>{candidate.registryName}</b><small>V{candidate.registryVersion} · {candidate.sourceKey}</small></span></span><span className={`state ${candidate.reviewState === 'APPROVED' ? 'good' : candidate.reviewState === 'REJECTED' ? 'bad' : 'warn'}`}>{tx(candidate.reviewState)}</span><span>{tx(candidate.compatibility)}<small>{candidate.compatibilityReason || candidate.registryStatus}</small></span><span>{stamp(candidate.updatedAt)}</span></button>)}{!loading && !candidates.length && <div className="empty-state">{tx('noRegistryCandidates')}</div>}</div><footer className="pagination"><span>{candidateTotal ? `${candidatePage * 25 + 1}–${candidatePage * 25 + candidates.length} / ${candidateTotal}` : '0 / 0'}</span><div><button disabled={candidatePage === 0 || loading} onClick={() => setCandidatePage((value) => value - 1)}>←</button><button disabled={(candidatePage + 1) * 25 >= candidateTotal || loading} onClick={() => setCandidatePage((value) => value + 1)}>→</button></div></footer></section>
      <section className="data-surface registry-jobs"><header className="registry-surface-head"><b>{tx('syncJobs')}</b></header>{jobs.map((job) => <article key={job.id}><header><b>{tx(job.state)}</b><span>{tx("ATTEMPT")}{job.attempt}</span></header><p>{job.sourceKey}</p><dl><div><dt>{tx("FETCHED")}</dt><dd>{job.fetchedCount}</dd></div><div><dt>{tx("SNAPSHOTS")}</dt><dd>{job.snapshotCount}</dd></div><div><dt>{tx("CANDIDATES")}</dt><dd>{job.candidateCount}</dd></div></dl><small>{job.safeErrorCode || stamp(job.updatedAt)}</small></article>)}{!loading && !jobs.length && <div className="empty-state">{tx('noRegistryJobs')}</div>}<footer className="pagination"><span>{jobTotal ? `${jobPage * 10 + 1}–${jobPage * 10 + jobs.length} / ${jobTotal}` : '0 / 0'}</span><div><button disabled={jobPage === 0 || loading} onClick={() => setJobPage((value) => value - 1)}>←</button><button disabled={(jobPage + 1) * 10 >= jobTotal || loading} onClick={() => setJobPage((value) => value + 1)}>→</button></div></footer></section>
    </div>
    <div className={`drawer-layer ${detail ? 'open' : ''}`}><button className="drawer-scrim" aria-label={tx("Close")} onClick={() => setDetail(null)} /><aside className="drawer" role="dialog" aria-modal="true"><header><span>{tx("MCP REGISTRY / REVIEW")}</span><button aria-label={tx("Close")} onClick={() => setDetail(null)}>×</button></header>{detail && <><div className="registry-detail-title"><span>{detail.candidate.sourceKey}</span><h2>{detail.title || detail.candidate.registryName}</h2><p>{detail.description || '—'}</p></div><div className="detail-grid"><div><span>{tx("VERSION")}</span><b>{detail.candidate.registryVersion}</b></div><div><span>{tx('reviewState')}</span><b>{tx(detail.candidate.reviewState)}</b></div><div><span>{tx("COMPATIBILITY")}</span><b>{tx(detail.candidate.compatibility)}</b></div><div><span>{tx("MANIFEST SHA256")}</span><b>{detail.candidate.manifestSha256}</b></div></div><section className="drawer-section"><header><b>{tx("REMOTE TRANSPORTS")}</b><span>{detail.transports.length}</span></header><div className="registry-transports">{detail.transports.map((transport, index) => <div key={`${transport.endpointUrl}:${index}`}><span><b>{transport.endpointUrl}</b><small>{transport.secretHeaders ? 'SECRET HEADERS DECLARED' : 'NO SECRET HEADERS'}</small></span></div>)}{!detail.transports.length && <p>—</p>}</div></section>{detail.reviewReason && <section className="drawer-section"><header><b>{tx("REVIEW EVIDENCE")}</b><span>{stamp(detail.reviewedAt)}</span></header><p className="contract-note">{detail.reviewReason}</p></section>}{detail.candidate.reviewState === 'PENDING_REVIEW' && <div className="registry-review-actions"><button className="danger" onClick={() => setAction('reject')}>{tx('rejectCandidate')}</button><button className="primary" onClick={() => setAction('approve')}>{tx('approveCandidate')}</button></div>}</>}</aside></div>
    {action && <div className="modal-layer"><form ref={modal} className="modal" role="dialog" aria-modal="true" aria-labelledby="mcp-registry-command" onSubmit={submit}><h2 id="mcp-registry-command">{action === 'sync' ? tx('requestRegistrySync') : action === 'approve' ? tx('approveCandidate') : tx('rejectCandidate')}</h2><label><span>{tx('operationReason')}</span><textarea name="reason" required maxLength={500} /></label><div className="modal-actions"><button type="button" onClick={() => setAction(null)}>{tx('cancel')}</button><button className={action === 'reject' ? 'danger' : 'primary'} disabled={submitting}>{submitting ? tx('loading') : tx('confirm')}</button></div></form></div>}
  </section>
}
