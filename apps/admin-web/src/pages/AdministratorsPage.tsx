import { errorText } from '../errorText'
import { useCallback, useEffect, useRef, useState, type FormEvent } from 'react'
import type { AdminApi } from '../adminApi'
import type { AdministratorSession, AdministratorSummary } from '../types'

type Action = 'revoke'
const stamp = (value: string | null) => value ? new Date(value).toLocaleString() : '—'

export function AdministratorsPage({ api, tx, currentAdminId }: {
  api: AdminApi
  tx: (key: string) => string
  currentAdminId: string
}) {
  const [administrator, setAdministrator] = useState<AdministratorSummary | null>(null)
  const [sessions, setSessions] = useState<AdministratorSession[]>([])
  const [action, setAction] = useState<Action | null>(null)
  const [sessionTarget, setSessionTarget] = useState<AdministratorSession | null>(null)
  const [loading, setLoading] = useState(true)
  const [submitting, setSubmitting] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const version = useRef(0)
  const modal = useRef<HTMLFormElement>(null)

  const load = useCallback(async () => {
    const current = ++version.current
    setLoading(true); setError(null)
    try {
      const [principalPage, sessionPage] = await Promise.all([
        api.administrators({ page: 0, pageSize: 1 }),
        api.administratorSessions(currentAdminId),
      ])
      if (current !== version.current) return
      const singleton = principalPage.items.find((value) => value.id === currentAdminId) ?? principalPage.items[0] ?? null
      if (!singleton || singleton.id !== currentAdminId) throw new Error('Authenticated system administrator projection is unavailable')
      setAdministrator(singleton)
      setSessions(sessionPage.items)
    } catch (reason) {
      if (current === version.current) setError(reason instanceof Error ? reason.message : 'Unable to load system administrator security')
    } finally { if (current === version.current) setLoading(false) }
  }, [api, currentAdminId])

  useEffect(() => { void load(); return () => { version.current += 1 } }, [load])

  useEffect(() => {
    if (!action) return
    const frame = requestAnimationFrame(() => modal.current?.querySelector<HTMLElement>('input,textarea,button')?.focus())
    const key = (event: KeyboardEvent) => {
      if (event.key === 'Escape' && !submitting) { setAction(null); setSessionTarget(null) }
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

  const submit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    if (!action || !administrator) return
    const form = new FormData(event.currentTarget)
    setSubmitting(true); setError(null)
    try {
      if (sessionTarget) {
        await api.revokeAdministratorSession(administrator.id, sessionTarget.id, String(form.get('reason') || '').trim())
      }
      setAction(null); setSessionTarget(null); await load()
    } catch (reasonValue) { setError(reasonValue instanceof Error ? reasonValue.message : 'System administrator security command failed') }
    finally { setSubmitting(false) }
  }

  const activeOtherSessions = sessions.filter((session) => session.active && !session.current).length

  return <section className="page-view">
    <header className="page-head"><div><h1>{tx('administratorsTitle')}</h1></div></header>
    {error && <div className="page-error" role="alert"><span>{errorText(error, tx)}</span><button onClick={() => void load()}>{tx('retry')}</button></div>}
    {loading && !administrator ? <div className="page-loading"><i />{tx('loading')}</div> : administrator && <div className="singleton-admin-layout">
      <article className="singleton-identity surface">
        <header><b>{tx('LOGIN NAME')}：{administrator.loginName}</b><span className="good">{tx(administrator.status)}</span></header>
        <div className="detail-grid"><div><span>{tx("CREDENTIAL VERSION")}</span><b>V{administrator.credentialVersion}</b></div><div><span>{tx("LAST LOGIN")}</span><b>{stamp(administrator.lastSuccessfulLoginAt)}</b></div><div><span>{tx("UPDATED")}</span><b>{stamp(administrator.updatedAt)}</b></div></div>
      </article>
      <div className="singleton-security-stack">
        <p className="contract-note">{tx('configuredAccountHint')}</p>
        <section className="surface session-control"><header><b>{tx('administratorSessions')}</b><span>{sessions.length}{tx("TOTAL ·")}{activeOtherSessions}{tx("OTHER ACTIVE")}</span></header><div className="admin-session-list">{sessions.map((session) => <div key={session.id}><span><b>{session.current ? tx('currentSession') : session.id}</b><small>{stamp(session.createdAt)} · {stamp(session.expiresAt)}</small></span><em className={session.active ? 'good' : 'warn'}>{tx(session.active ? 'ACTIVE' : 'REVOKED')}</em>{session.active && !session.current && <button className="danger" onClick={() => { setSessionTarget(session); setAction('revoke') }}>{tx('revokeSession')}</button>}</div>)}{!sessions.length && <p>—</p>}</div></section>
      </div>
    </div>}
    {action && <div className="modal-layer"><form ref={modal} className="modal" role="dialog" aria-modal="true" aria-labelledby="singleton-security-command" onSubmit={submit}><h2 id="singleton-security-command">{tx('revokeSession')}</h2><p className="modal-copy">{sessionTarget?.id}</p><label><span>{tx('operationReason')}</span><textarea name="reason" required maxLength={500} /></label><div className="modal-actions"><button type="button" onClick={() => { setAction(null); setSessionTarget(null) }}>{tx('cancel')}</button><button className={action === 'revoke' ? 'danger' : 'primary'} disabled={submitting}>{submitting ? tx('loading') : tx('confirm')}</button></div></form></div>}
  </section>
}
