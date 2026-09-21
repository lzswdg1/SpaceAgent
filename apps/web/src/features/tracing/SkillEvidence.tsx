import { useEffect, useState } from 'react'
import type { AuthenticatedRequest } from '../overview/types'
import './skillEvidence.css'

type Evidence = { state: string; preparedAt: string | null; skills: { skillVersionId: string; configHash: string }[] }
export function SkillEvidence({ runId, request, tx }: { runId: string; request: AuthenticatedRequest; tx: (key: string) => string }) {
  const [attempt, setAttempt] = useState(0), [data, setData] = useState<Evidence | null>(null)
  const [busy, setBusy] = useState(false), [failed, setFailed] = useState(false)
  useEffect(() => {
    if (!attempt) return
    let current = true
    setBusy(true); setFailed(false); setData(null)
    request<Evidence>(`/api/v1/chat/runs/${encodeURIComponent(runId)}/skill-evidence`).then(value => { if (current) setData(value) })
      .catch(() => { if (current) setFailed(true) }).finally(() => { if (current) setBusy(false) })
    return () => { current = false }
  }, [attempt, request, runId])
  return <section className="trace-skill-evidence" aria-busy={busy}><header><b>{tx('skillEvidenceTitle')}</b>
    <button type="button" disabled={busy} onClick={() => setAttempt(value => value + 1)}>{tx('skillEvidenceLoad')}</button></header>
    {busy && <p role="status">{tx('loadingTrace')}</p>}{failed && <p role="alert">{tx('skillEvidenceFailed')}</p>}
    {data && <><p>{tx(data.state === 'CONTEXT_PREPARED' ? 'skillEvidencePrepared' : 'skillEvidenceNone')}</p>
      {data.preparedAt && <time>{new Date(data.preparedAt).toLocaleString()}</time>}
      <ul>{data.skills.map(skill => <li key={skill.skillVersionId}><code>{skill.skillVersionId}</code><small>SHA-256: {skill.configHash}</small></li>)}</ul></>}
  </section>
}
