import { errorText } from '../errorText'
import { useCallback, useEffect, useRef, useState, type FormEvent } from 'react'
import type { AdminApi } from '../adminApi'
import type { CommandView } from '../types'

export function CommandsPage({ api, tx }: { api: AdminApi; tx: (key: string) => string }) {
  const [items, setItems] = useState<CommandView[]>([])
  const [total, setTotal] = useState(0)
  const [page, setPage] = useState(0)
  const [state, setState] = useState('')
  const [operation, setOperation] = useState('')
  const [target, setTarget] = useState('')
  const [id, setId] = useState('')
  const [command, setCommand] = useState<CommandView | null>(null)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const version = useRef(0)

  const load = useCallback(async () => {
    const current = ++version.current
    setLoading(true); setError(null)
    try {
      const result = await api.commands({ page, pageSize: 25, state: state || undefined, operation: operation.trim() || undefined, target: target.trim() || undefined })
      if (current !== version.current) return
      setItems(result.items); setTotal(result.total)
    } catch (reason) { if (current === version.current) setError(reason instanceof Error ? reason.message : 'Unable to load commands') }
    finally { if (current === version.current) setLoading(false) }
  }, [api, page, state, operation, target])

  useEffect(() => {
    const timer = window.setTimeout(() => void load(), operation || target ? 250 : 0)
    return () => { clearTimeout(timer); version.current += 1 }
  }, [load, operation, target])

  const find = async (event: FormEvent) => {
    event.preventDefault(); setLoading(true); setError(null)
    try { setCommand(await api.command(id.trim())) }
    catch (reason) { setError(reason instanceof Error ? reason.message : 'Command not found') }
    finally { setLoading(false) }
  }

  const inspect = async (value: CommandView) => {
    setId(value.id); setCommand(value)
    try { setCommand(await api.command(value.id)) } catch { /* page evidence remains visible */ }
  }

  const reconcile = async () => {
    if (!command) return
    setLoading(true); setError(null)
    try {
      const result = await api.reconcile(command.id)
      setCommand({ ...command, state: result.state, safeErrorCode: result.safeErrorCode, updatedAt: result.updatedAt, completedAt: result.completedAt })
      await load()
    } catch (reason) { setError(reason instanceof Error ? reason.message : 'Reconciliation failed') }
    finally { setLoading(false) }
  }

  return <section className="page-view">
    <header className="page-head"><div><h1>{tx('commandsTitle')}</h1><p>{tx('commandsCopy')}</p></div></header>
    {error && <div className="page-error" role="alert">{errorText(error, tx)}</div>}
    <div className="toolbar command-toolbar"><label className="search-box"><span>⌕</span><input value={target} onChange={(event) => { setPage(0); setTarget(event.target.value) }} placeholder={tx("Target ID")} aria-label={tx("Target ID")} /></label><input value={operation} onChange={(event) => { setPage(0); setOperation(event.target.value) }} placeholder={tx("Operation")} aria-label={tx("Operation")} /><select value={state} onChange={(event) => { setPage(0); setState(event.target.value) }}><option value="">{tx("ALL STATES")}</option><option value="UNKNOWN">{tx("UNKNOWN")}</option><option value="FAILED">{tx("FAILED")}</option><option value="DISPATCHING">{tx("DISPATCHING")}</option><option value="SUCCEEDED">{tx("SUCCEEDED")}</option></select></div>
    <div className="data-surface"><div className="data-table command-table"><div className="data-row heading"><span>{tx("OPERATION")}</span><span>{tx("STATE")}</span><span>{tx("TARGET")}</span><span>{tx("SAFE ERROR")}</span><span>{tx("UPDATED")}</span></div>{!error && items.map((item) => <button className="data-row" key={item.id} onClick={() => void inspect(item)}><span><b>{tx(item.operation)}</b><small>{item.id}</small></span><span className={`state ${item.state === 'SUCCEEDED' ? 'good' : item.state === 'UNKNOWN' || item.state === 'FAILED' ? 'bad' : 'warn'}`}>{tx(item.state)}</span><span>{item.targetType || '—'} / {item.targetId || '—'}</span><span>{item.safeErrorCode || '—'}</span><span>{new Date(item.updatedAt).toLocaleString()}</span></button>)}{!loading && !error && !items.length && <div className="empty-state">{tx('noCommands')}</div>}</div><footer className="pagination"><span>{loading || error ? tx(error ? 'dataUnavailable' : 'loading') : `${page * 25 + (items.length ? 1 : 0)}–${page * 25 + items.length} / ${total}`}</span><div><button disabled={page === 0 || loading} onClick={() => setPage((value) => value - 1)}>← {tx('previous')}</button><button disabled={(page + 1) * 25 >= total || loading} onClick={() => setPage((value) => value + 1)}>{tx('next')} →</button></div></footer></div>
    <form className="command-lookup" onSubmit={find}><label><span>{tx('commandId')}</span><input value={id} onChange={(event) => setId(event.target.value)} placeholder="00000000-0000-0000-0000-000000000000" required pattern="[0-9a-fA-F-]{36}" /></label><button className="primary" disabled={loading}>{loading || error ? tx(error ? 'dataUnavailable' : 'loading') : tx('search')}</button></form>
    {command && <article className="command-detail"><header><span>{tx(command.operation)}</span><b className={command.state === 'UNKNOWN' || command.state === 'FAILED' ? 'bad' : command.state === 'SUCCEEDED' ? 'good' : 'warn'}>{tx(command.state)}</b></header><div className="detail-grid"><div><span>{tx("COMMAND ID")}</span><b>{command.id}</b></div><div><span>{tx("TARGET")}</span><b>{tx(command.targetType)} / {command.targetId || '—'}</b></div><div><span>{tx("PLATFORM REFERENCE")}</span><b>{command.platformReference || '—'}</b></div><div><span>{tx("SAFE ERROR")}</span><b>{command.safeErrorCode || '—'}</b></div><div><span>{tx("CREATED")}</span><b>{new Date(command.createdAt).toLocaleString()}</b></div><div><span>{tx("UPDATED")}</span><b>{new Date(command.updatedAt).toLocaleString()}</b></div></div>{command.state === 'UNKNOWN' && <button className="reconcile" disabled={loading} onClick={() => void reconcile()}>{tx('reconcile')}</button>}</article>}
  </section>
}
