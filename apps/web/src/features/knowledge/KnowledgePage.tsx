import { useCallback, useEffect, useMemo, useState, type FormEvent } from 'react'
import type { AuthenticatedRequest } from '../overview/types'
import {
  controlKnowledgeUrlJob,
  createKnowledgeReference,
  createKnowledgeUrlJob,
  deleteKnowledgeDocument,
  getKnowledgeUrlJob,
  listKnowledgeChunks,
  listKnowledgeDocuments,
  listKnowledgeUrlJobs,
  processKnowledgeDocument,
  retrieveKnowledge,
} from './knowledgeApi'
import type { KnowledgeChunk, KnowledgeDocument, KnowledgeUrlJob, RetrievalMatch } from './types'
import './knowledge.css'

type Props = { request: AuthenticatedRequest; tx: (key: string) => string }
type ImportMode = 'local' | 'online' | 'paste'
type PendingUrlDocument = { document: KnowledgeDocument; name: string; url: string }

export function KnowledgePage({ request, tx }: Props) {
  const [documents, setDocuments] = useState<KnowledgeDocument[]>([])
  const [selectedId, setSelectedId] = useState<string | null>(null)
  const [chunks, setChunks] = useState<KnowledgeChunk[]>([])
  const [matches, setMatches] = useState<RetrievalMatch[]>([])
  const [query, setQuery] = useState('')
  const [search, setSearch] = useState('')
  const [status, setStatus] = useState('ALL')
  const [importMode, setImportMode] = useState<ImportMode | null>(null)
  const [deleteConfirm, setDeleteConfirm] = useState(false)
  const [loading, setLoading] = useState(true)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [urlJobs, setUrlJobs] = useState<Record<string, KnowledgeUrlJob>>({})
  const [pendingUrlDocument, setPendingUrlDocument] = useState<PendingUrlDocument | null>(null)
  const [urlRefreshMode, setUrlRefreshMode] = useState<'MANUAL' | 'PERIODIC'>('MANUAL')
  const [urlJobBusy, setUrlJobBusy] = useState(false)

  const selected = documents.find(({ id }) => id === selectedId) ?? null
  const selectedUrlJob = selected ? urlJobs[selected.id] ?? null : null
  const filtered = useMemo(() => documents.filter((document) => {
    const matchesText = document.name.toLowerCase().includes(search.trim().toLowerCase())
    return matchesText && (status === 'ALL' || document.status === status)
  }), [documents, search, status])
  const readyCount = documents.filter(({ status: documentStatus }) => documentStatus === 'READY').length

  const load = useCallback(async () => {
    setLoading(true)
    setError(null)
    try {
      const rows = await listKnowledgeDocuments(request)
      setDocuments(rows)
      setSelectedId((current) => current && rows.some(({ id }) => id === current) ? current : rows[0]?.id ?? null)
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : tx('knowledgeLoadFailed'))
    } finally {
      setLoading(false)
    }
  }, [request, tx])

  useEffect(() => { void load() }, [load])
  useEffect(() => {
    setMatches([])
    if (!selectedId) { setChunks([]); return }
    let active = true
    listKnowledgeChunks(request, selectedId)
      .then((rows) => active && setChunks(rows))
      .catch((reason) => active && setError(reason instanceof Error ? reason.message : tx('knowledgeChunkLoadFailed')))
    return () => { active = false }
  }, [request, selectedId, tx])

  useEffect(() => {
    if (!selectedId) return
    let active = true
    listKnowledgeUrlJobs(request, selectedId, 1, 20)
      .then((page) => {
        if (!active) return
        setUrlJobs((current) => {
          const next = { ...current }
          if (page.items[0]) next[selectedId] = page.items[0]
          else delete next[selectedId]
          return next
        })
      })
      .catch((reason) => active && setError(
        reason instanceof Error ? reason.message : tx('knowledgeUrlStatusFailed')))
    return () => { active = false }
  }, [request, selectedId, tx])

  useEffect(() => {
    if (!selectedUrlJob || selectedUrlJob.state !== 'ACTIVE' || selected?.status === 'READY' || selected?.status === 'FAILED') return
    let active = true
    let refreshing = false
    let attempts = 0
    let timer: number | undefined
    const refresh = async () => {
      if (!active || refreshing || document.visibilityState === 'hidden') return
      refreshing = true
      attempts += 1
      try {
        const [job, rows] = await Promise.all([
          getKnowledgeUrlJob(request, selectedUrlJob.id),
          listKnowledgeDocuments(request),
        ])
        if (!active) return
        setUrlJobs((current) => ({ ...current, [job.knowledgeDocumentId]: job }))
        setDocuments(rows)
        const document = rows.find(({ id }) => id === selectedUrlJob.knowledgeDocumentId)
        if (document?.status === 'READY') {
          const nextChunks = await listKnowledgeChunks(request, document.id)
          if (active) setChunks(nextChunks)
        }
      } catch (reason) {
        if (active) setError(reason instanceof Error ? reason.message : tx('knowledgeUrlStatusFailed'))
      } finally {
        refreshing = false
        if (attempts >= 30 && timer !== undefined) window.clearInterval(timer)
      }
    }
    void refresh()
    timer = window.setInterval(() => void refresh(), 2000)
    return () => { active = false; if (timer !== undefined) window.clearInterval(timer) }
  }, [request, selected?.status, selectedUrlJob?.id, selectedUrlJob?.state, tx])

  const finishImport = (document: KnowledgeDocument) => {
    setDocuments((rows) => [document, ...rows.filter(({ id }) => id !== document.id)])
    setSelectedId(document.id)
    setImportMode(null)
  }

  const importLocal = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    const form = event.currentTarget
    const file = new FormData(form).get('file')
    if (!(file instanceof File) || !file.size) return
    setBusy(true); setError(null)
    try {
      const content = await file.text()
      const created = await createKnowledgeReference(request, {
        name: file.name,
        contentType: file.type || 'text/plain',
        storageReference: `browser-file:${file.name}`,
      })
      const processed = await processKnowledgeDocument(request, created.id, content)
      setChunks(processed.chunks)
      finishImport(processed.document)
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : tx('knowledgeImportFailed'))
    } finally { setBusy(false) }
  }

  const importReference = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    const data = new FormData(event.currentTarget)
    const location = String(data.get('location') ?? '').trim()
    const name = String(data.get('name') ?? '').trim()
    if (!location || !name) return
    const refreshMinutes = Number(data.get('refreshMinutes') || 60)
    const maximumMegabytes = Number(data.get('maximumMegabytes') || 2)
    setBusy(true); setError(null)
    try {
      let created = pendingUrlDocument?.name === name && pendingUrlDocument.url === location
        ? pendingUrlDocument.document
        : null
      if (!created) {
        created = await createKnowledgeReference(request, {
          name,
          contentType: 'text/html',
          storageReference: location,
        })
        setPendingUrlDocument({ document: created, name, url: location })
        setDocuments((rows) => [created!, ...rows.filter(({ id }) => id !== created!.id)])
        setSelectedId(created.id)
      }
      const job = await createKnowledgeUrlJob(request, {
        knowledgeDocumentId: created.id,
        url: location,
        refreshPolicy: {
          mode: urlRefreshMode,
          intervalSeconds: urlRefreshMode === 'PERIODIC' ? Math.round(refreshMinutes * 60) : 0,
          useConditionalRequests: true,
          maximumRedirects: 3,
          maximumBytes: Math.round(maximumMegabytes * 1_000_000),
        },
        idempotencyKey: crypto.randomUUID(),
      })
      setUrlJobs((rows) => ({ ...rows, [created.id]: job }))
      setPendingUrlDocument(null)
      finishImport(created)
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : tx('knowledgeUrlCreateFailed'))
    } finally { setBusy(false) }
  }

  const importPaste = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    const data = new FormData(event.currentTarget)
    const name = String(data.get('name') ?? '').trim()
    const content = String(data.get('content') ?? '').trim()
    if (!name || !content) return
    setBusy(true); setError(null)
    try {
      const created = await createKnowledgeReference(request, {
        name,
        contentType: 'text/plain',
        storageReference: `inline-ui:${Date.now()}`,
      })
      const processed = await processKnowledgeDocument(request, created.id, content)
      setChunks(processed.chunks)
      finishImport(processed.document)
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : tx('knowledgeImportFailed'))
    } finally { setBusy(false) }
  }

  const testRetrieval = async (event: FormEvent) => {
    event.preventDefault()
    if (!selected || !query.trim()) return
    setBusy(true); setError(null)
    try {
      const result = await retrieveKnowledge(request, { documentIds: [selected.id], query: query.trim(), topK: 4 })
      setMatches(result.matches)
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : tx('retrievalFailed'))
    } finally { setBusy(false) }
  }

  const refreshUrlEvidence = async () => {
    if (!selectedUrlJob || urlJobBusy) return
    setUrlJobBusy(true); setError(null)
    try {
      const [job, rows] = await Promise.all([
        getKnowledgeUrlJob(request, selectedUrlJob.id),
        listKnowledgeDocuments(request),
      ])
      setUrlJobs((current) => ({ ...current, [job.knowledgeDocumentId]: job }))
      setDocuments(rows)
      const document = rows.find(({ id }) => id === job.knowledgeDocumentId)
      if (document?.status === 'READY') setChunks(await listKnowledgeChunks(request, document.id))
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : tx('knowledgeUrlStatusFailed'))
    } finally { setUrlJobBusy(false) }
  }

  const controlUrlJob = async (action: 'pause' | 'resume') => {
    if (!selectedUrlJob || urlJobBusy) return
    setUrlJobBusy(true); setError(null)
    try {
      const job = await controlKnowledgeUrlJob(
        request, selectedUrlJob.id, action, selectedUrlJob.revision,
      )
      setUrlJobs((current) => ({ ...current, [job.knowledgeDocumentId]: job }))
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : tx('knowledgeUrlControlFailed'))
      const job = await getKnowledgeUrlJob(request, selectedUrlJob.id).catch(() => null)
      if (job) setUrlJobs((current) => ({ ...current, [job.knowledgeDocumentId]: job }))
    } finally { setUrlJobBusy(false) }
  }

  const remove = async () => {
    if (!selected || busy) return
    setBusy(true); setError(null)
    try {
      await deleteKnowledgeDocument(request, selected.id)
      const remaining = documents.filter(({ id }) => id !== selected.id)
      setUrlJobs((rows) => { const next = { ...rows }; delete next[selected.id]; return next })
      setDocuments(remaining); setSelectedId(remaining[0]?.id ?? null); setDeleteConfirm(false)
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : tx('knowledgeDeleteFailed'))
    } finally { setBusy(false) }
  }

  return <section className="knowledge-registry">
    <header className="knowledge-heading"><div><span>KNOWLEDGE / REGISTRY</span><h1>{tx('knowledgeRegistry')}</h1><p>{tx('knowledgeRegistryHint')}</p></div><div className="knowledge-stats"><div><small>{tx('documents')}</small><b>{documents.length}</b></div><div><small>READY</small><b>{readyCount}</b></div></div></header>
    {error && <div className="knowledge-error"><span>{error}</span><button onClick={() => setError(null)}>×</button></div>}
    <section className="knowledge-toolbar"><div className="knowledge-search"><span>⌕</span><input value={search} onChange={(event) => setSearch(event.target.value)} placeholder={tx('searchKnowledge')} /></div><select value={status} onChange={(event) => setStatus(event.target.value)}><option value="ALL">{tx('allStatuses')}</option><option value="READY">READY</option><option value="UPLOADED">UPLOADED</option><option value="PROCESSING">PROCESSING</option><option value="FAILED">FAILED</option></select><button onClick={() => setImportMode('local')}>{tx('localImport')}</button><button onClick={() => setImportMode('online')}>{tx('onlineImport')}</button><button className="primary" onClick={() => setImportMode('paste')}>{tx('pasteText')}</button></section>
    <div className="knowledge-layout">
      <main className="knowledge-catalog"><div className="knowledge-section-title"><b>{tx('knowledgeSources')}</b><span>{filtered.length.toString().padStart(2, '0')}</span></div><div className="knowledge-grid">{filtered.map((document) => <button type="button" key={document.id} className={document.id === selectedId ? 'active' : ''} onClick={() => setSelectedId(document.id)}><header><span>{document.contentType.includes('markdown') ? 'MD' : document.contentType.includes('json') ? 'JS' : 'TX'}</span><em>{document.status}</em></header><b>{document.name}</b><p>{document.storageLocation}</p><footer><span>{new Date(document.updatedAt).toLocaleDateString()}</span><span>{document.status === 'READY' ? tx('indexed') : tx('referenceOnly')}</span></footer></button>)}{!loading && !filtered.length && <div className="knowledge-empty"><b>{tx('noKnowledgeDocuments')}</b></div>}</div></main>
      <aside className="knowledge-inspector"><header><b>{tx('sourceInspector')}</b><span>{selected?.status || '—'}</span></header>{selected ? <><section className="knowledge-source-meta"><label>{tx('selectedSource')}</label><h2>{selected.name}</h2><p>{selected.contentType}</p><code>{selected.storageLocation}</code></section>{selectedUrlJob && <section className="knowledge-url-status"><label>{tx('urlIngestion')}</label><div><b>{selectedUrlJob.state}</b><span>REV {selectedUrlJob.revision}</span></div><small>{selectedUrlJob.refreshPolicy.mode}{selectedUrlJob.refreshPolicy.mode === 'PERIODIC' ? ` · ${Math.round(selectedUrlJob.refreshPolicy.intervalSeconds / 60)} MIN` : ''}</small><small>{selectedUrlJob.nextRefreshAt ? new Date(selectedUrlJob.nextRefreshAt).toLocaleString() : '—'}</small><div className="knowledge-url-actions"><button type="button" disabled={urlJobBusy} onClick={() => void refreshUrlEvidence()}>↻ {tx('refreshUrlEvidence')}</button>{selectedUrlJob.state === 'ACTIVE' && <button type="button" disabled={urlJobBusy} onClick={() => void controlUrlJob('pause')}>{tx('pauseUrlIngestion')}</button>}{selectedUrlJob.state === 'PAUSED' && <button type="button" disabled={urlJobBusy} onClick={() => void controlUrlJob('resume')}>{tx('resumeUrlIngestion')}</button>}</div></section>}<section><label>{tx('indexEvidence')}</label><dl><div><dt>{tx('chunks')}</dt><dd>{chunks.length}</dd></div><div><dt>{tx('dimensions')}</dt><dd>{chunks[0]?.embeddingDimensions || '—'}</dd></div></dl></section><section><label>{tx('testRetrieval')}</label><form className="retrieval-form" onSubmit={testRetrieval}><input value={query} onChange={(event) => setQuery(event.target.value)} placeholder={tx('retrievalQuery')} /><button disabled={busy || selected.status !== 'READY'}>→</button></form><div className="retrieval-results">{matches.map((match) => <article key={match.chunkId}><span>{Math.round(match.score * 100)}%</span><p>{match.content}</p></article>)}{query && !busy && !matches.length && <small>{tx('noRetrievalMatches')}</small>}</div></section><section className="knowledge-danger"><button onClick={() => setDeleteConfirm(true)}>{tx('removeSource')}</button></section></> : <div className="knowledge-inspector-empty">{tx('selectKnowledgeSource')}</div>}</aside>
    </div>

    {importMode && <div className="knowledge-modal" role="dialog" aria-modal="true"><div className="knowledge-dialog"><header><div><span>IMPORT / {importMode.toUpperCase()}</span><h2>{tx(importMode === 'local' ? 'localImport' : importMode === 'online' ? 'onlineImport' : 'pasteText')}</h2></div><button onClick={() => { setImportMode(null); setPendingUrlDocument(null) }}>×</button></header>{importMode === 'local' && <form onSubmit={importLocal}><p>{tx('localImportHint')}</p><label className="file-drop"><input name="file" type="file" accept=".txt,.md,.json,.csv,text/plain,text/markdown,application/json,text/csv" required/><span>{tx('chooseLocalFile')}</span></label><button className="submit" disabled={busy}>{busy ? tx('processing') : tx('importAndIndex')}</button></form>}{importMode === 'online' && <form onSubmit={importReference}><p>{tx('onlineImportHint')}</p><label><span>{tx('sourceName')}</span><input name="name" required /></label><label><span>{tx('sourceUrl')}</span><input name="location" type="url" placeholder="https://" pattern="https://.*" required /></label><label><span>{tx('urlRefreshMode')}</span><select value={urlRefreshMode} onChange={(event) => setUrlRefreshMode(event.target.value as 'MANUAL' | 'PERIODIC')}><option value="MANUAL">{tx('manualUrlRefresh')}</option><option value="PERIODIC">{tx('periodicUrlRefresh')}</option></select></label>{urlRefreshMode === 'PERIODIC' && <label><span>{tx('refreshIntervalMinutes')}</span><input name="refreshMinutes" type="number" min="5" max="43200" defaultValue="60" required /></label>}<label><span>{tx('maximumUrlMegabytes')}</span><input name="maximumMegabytes" type="number" min="1" max="10" defaultValue="2" required /></label><button className="submit" disabled={busy}>{busy ? tx('processing') : tx('importAndIndex')}</button></form>}{importMode === 'paste' && <form onSubmit={importPaste}><p>{tx('pasteTextHint')}</p><label><span>{tx('sourceName')}</span><input name="name" required /></label><label><span>{tx('content')}</span><textarea name="content" required /></label><button className="submit" disabled={busy}>{busy ? tx('processing') : tx('importAndIndex')}</button></form>}</div></div>}
    {deleteConfirm && selected && <div className="knowledge-modal" role="dialog" aria-modal="true"><div className="knowledge-dialog compact"><header><div><span>{tx('systemConfirmation')}</span><h2>{tx('removeKnowledgeSource')}</h2></div></header><p>{selected.name}</p><div className="knowledge-dialog-actions"><button onClick={() => setDeleteConfirm(false)}>{tx('cancel')}</button><button className="danger" disabled={busy} onClick={() => void remove()}>{tx('remove')}</button></div></div></div>}
  </section>
}
