import { useCallback, useEffect, useMemo, useRef, useState, type FormEvent } from 'react'
import type { AuthenticatedRequest } from '../overview/types'
import { consolidateTaskMemory, listMemoryCandidates, proposeMemoryCandidate, recallMemory, reviewMemoryCandidate } from './memoryApi'
import type { MemoryCandidate, MemoryConsolidation, MemoryKind, MemoryScope, ScopedMemory } from './types'
import './memory.css'

type Props = { request: AuthenticatedRequest; userId: string; tx: (key: string) => string }
type ReviewTarget = { candidate: MemoryCandidate; decision: 'ACCEPTED' | 'REJECTED' }
const kinds: MemoryKind[] = ['FACT', 'PREFERENCE', 'CONSTRAINT', 'DECISION', 'ARCHITECTURE', 'PROCEDURE', 'FAILURE', 'EXPERIENCE']

export function MemoryPage({ request, userId, tx }: Props) {
  const sequence = useRef(0)
  const [scopeType, setScopeType] = useState<MemoryScope>('USER')
  const [scopeId, setScopeId] = useState(userId)
  const [memories, setMemories] = useState<ScopedMemory[]>([])
  const [candidates, setCandidates] = useState<MemoryCandidate[]>([])
  const [reviewTarget, setReviewTarget] = useState<ReviewTarget | null>(null)
  const [consolidation, setConsolidation] = useState<MemoryConsolidation | null>(null)
  const [loading, setLoading] = useState(false)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const effectiveScopeId = scopeType === 'USER' ? userId : scopeId.trim()
  const scope = useMemo(() => effectiveScopeId ? { scope: scopeType, scopeId: effectiveScopeId } : null,
    [effectiveScopeId, scopeType])

  const load = useCallback(async () => {
    if (!scope) { setMemories([]); setCandidates([]); return }
    const current = ++sequence.current
    setLoading(true); setError(null)
    try {
      const [nextMemories, nextCandidates] = await Promise.all([
        recallMemory(request, scope, 50),
        listMemoryCandidates(request, scope, 50),
      ])
      if (current !== sequence.current) return
      setMemories(nextMemories); setCandidates(nextCandidates)
    } catch (reason) {
      if (current === sequence.current) {
        setError(reason instanceof Error ? reason.message : tx('memoryLoadFailed'))
        setMemories([]); setCandidates([])
      }
    } finally { if (current === sequence.current) setLoading(false) }
  }, [request, scope, tx])

  useEffect(() => { void load() }, [load])

  const changeScope = (next: MemoryScope) => {
    sequence.current += 1
    setScopeType(next)
    setScopeId(next === 'USER' ? userId : '')
    setConsolidation(null); setReviewTarget(null); setError(null)
  }

  const submitCandidate = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    if (!scope || busy) return
    const form = event.currentTarget
    const data = new FormData(form)
    setBusy(true); setError(null)
    try {
      await proposeMemoryCandidate(request, {
        scope,
        kind: String(data.get('kind')) as MemoryKind,
        sourceId: String(data.get('sourceId') ?? '').trim(),
        sourceType: 'MANUAL_WEB',
        content: String(data.get('content') ?? '').trim(),
        confidence: Number(data.get('confidence')),
        dedupeKey: String(data.get('dedupeKey') ?? '').trim() || null,
      })
      form.reset()
      await load()
    } catch (reason) { setError(reason instanceof Error ? reason.message : tx('memoryCandidateSaveFailed')) }
    finally { setBusy(false) }
  }

  const reviewCandidate = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    if (!reviewTarget || busy) return
    const reason = String(new FormData(event.currentTarget).get('reason') ?? '').trim()
    setBusy(true); setError(null)
    try {
      await reviewMemoryCandidate(request, reviewTarget.candidate.id, {
        decision: reviewTarget.decision, reason: reason || null,
      })
      setReviewTarget(null)
      await load()
    } catch (reasonValue) { setError(reasonValue instanceof Error ? reasonValue.message : tx('memoryReviewFailed')) }
    finally { setBusy(false) }
  }

  const consolidate = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    if (!scope || scope.scope !== 'TASK' || busy) return
    const data = new FormData(event.currentTarget)
    const acceptThreshold = Number(data.get('acceptThreshold'))
    const promotionThreshold = Number(data.get('promotionThreshold'))
    if (promotionThreshold < acceptThreshold) { setError(tx('memoryThresholdInvalid')); return }
    setBusy(true); setError(null)
    try {
      const result = await consolidateTaskMemory(request, {
        taskId: scope.scopeId,
        projectId: String(data.get('projectId') ?? '').trim() || null,
        userId: data.get('promoteToUser') ? userId : null,
        acceptThreshold, promotionThreshold,
      })
      setConsolidation(result)
      await load()
    } catch (reason) { setError(reason instanceof Error ? reason.message : tx('memoryConsolidationFailed')) }
    finally { setBusy(false) }
  }

  return <section className="memory-page">
    <header className="memory-heading"><div><span>MEMORY / REVIEW</span><h1>{tx('memoryControl')}</h1><p>{tx('memoryControlHint')}</p></div><button disabled={!scope || loading} onClick={() => void load()}>{tx('refresh')}</button></header>
    {error && <div className="memory-error" role="alert"><span>{error}</span><button onClick={() => setError(null)}>×</button></div>}
    <section className="memory-scope"><div className="memory-scope-tabs">{(['USER', 'PROJECT', 'TASK'] as MemoryScope[]).map((value) => <button key={value} className={scopeType === value ? 'active' : ''} onClick={() => changeScope(value)}>{value}</button>)}</div><label><span>{tx('memoryScopeId')}</span><input value={effectiveScopeId} readOnly={scopeType === 'USER'} onChange={(event) => setScopeId(event.target.value)} placeholder={tx(scopeType === 'PROJECT' ? 'projectId' : scopeType === 'TASK' ? 'taskId' : 'userId')} /></label></section>
    <div className="memory-grid">
      <section className="memory-card"><header><b>{tx('consolidatedMemory')}</b><span>{memories.length.toString().padStart(2, '0')}</span></header><div>{memories.map((memory) => <article key={memory.id}><header><b>{memory.kind}</b><span>{memory.key}</span></header><p>{memory.value}</p><small>{new Date(memory.updatedAt).toLocaleString()}</small></article>)}{!loading && !memories.length && <div className="memory-empty">{scope ? tx('noConsolidatedMemory') : tx('memoryScopeRequired')}</div>}</div></section>
      <section className="memory-card"><header><b>{tx('pendingMemoryCandidates')}</b><span>{candidates.length.toString().padStart(2, '0')}</span></header><div>{candidates.map((candidate) => <article key={candidate.id}><header><b>{candidate.kind}</b><span>{Math.round(candidate.confidence * 100)}%</span></header><p>{candidate.content}</p><small>{candidate.sourceType} · {candidate.sourceId}</small><footer><button disabled={busy} onClick={() => setReviewTarget({ candidate, decision: 'ACCEPTED' })}>{tx('accept')}</button><button className="danger" disabled={busy} onClick={() => setReviewTarget({ candidate, decision: 'REJECTED' })}>{tx('reject')}</button></footer></article>)}{!loading && !candidates.length && <div className="memory-empty">{scope ? tx('noPendingMemory') : tx('memoryScopeRequired')}</div>}</div></section>
    </div>
    <div className="memory-forms">
      <form className="memory-form" onSubmit={submitCandidate}><header><span>01</span><b>{tx('proposeMemoryCandidate')}</b></header><label><span>{tx('memoryKind')}</span><select name="kind">{kinds.map((kind) => <option key={kind}>{kind}</option>)}</select></label><label><span>{tx('memorySourceId')}</span><input name="sourceId" maxLength={160} required /></label><label><span>{tx('memoryContent')}</span><textarea name="content" maxLength={32000} required /></label><div><label><span>{tx('confidence')}</span><input name="confidence" type="number" min="0" max="1" step="0.05" defaultValue="0.8" required /></label><label><span>{tx('dedupeKey')}</span><input name="dedupeKey" maxLength={512} /></label></div><button disabled={!scope || busy}>{busy ? tx('saving') : tx('propose')}</button></form>
      <form className="memory-form" onSubmit={consolidate}><header><span>02</span><b>{tx('consolidateTaskMemory')}</b></header><p>{scopeType === 'TASK' ? tx('memoryConsolidationHint') : tx('selectTaskScope')}</p><label><span>{tx('projectId')}</span><input name="projectId" maxLength={160} disabled={scopeType !== 'TASK'} /></label><div><label><span>{tx('acceptThreshold')}</span><input name="acceptThreshold" type="number" min="0" max="1" step="0.05" defaultValue="0.55" disabled={scopeType !== 'TASK'} /></label><label><span>{tx('promotionThreshold')}</span><input name="promotionThreshold" type="number" min="0" max="1" step="0.05" defaultValue="0.75" disabled={scopeType !== 'TASK'} /></label></div><label className="memory-check"><input name="promoteToUser" type="checkbox" disabled={scopeType !== 'TASK'} /><span>{tx('promoteMemoryToUser')}</span></label><button disabled={scopeType !== 'TASK' || !scope || busy}>{busy ? tx('processing') : tx('consolidate')}</button>{consolidation && <dl><div><dt>{tx('accepted')}</dt><dd>{consolidation.accepted}</dd></div><div><dt>{tx('rejected')}</dt><dd>{consolidation.rejected}</dd></div><div><dt>{tx('promoted')}</dt><dd>{consolidation.promotedToProject + consolidation.promotedToUser}</dd></div></dl>}</form>
    </div>
    {reviewTarget && <div className="memory-modal" role="dialog" aria-modal="true"><form className="memory-dialog" onSubmit={reviewCandidate}><span>{tx('systemConfirmation')}</span><h2>{tx(reviewTarget.decision === 'ACCEPTED' ? 'acceptMemoryCandidate' : 'rejectMemoryCandidate')}</h2><p>{reviewTarget.candidate.content}</p><label><span>{tx('reasonOptional')}</span><textarea name="reason" maxLength={2000} autoFocus /></label><div><button type="button" disabled={busy} onClick={() => setReviewTarget(null)}>{tx('cancel')}</button><button className={reviewTarget.decision === 'REJECTED' ? 'danger' : 'primary'} disabled={busy}>{busy ? tx('processing') : tx('confirm')}</button></div></form></div>}
  </section>
}
