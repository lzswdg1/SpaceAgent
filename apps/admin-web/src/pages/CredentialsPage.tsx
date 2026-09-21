import { errorText } from '../errorText'
import { useCallback, useEffect, useState } from 'react'
import type { AdminApi } from '../adminApi'
import type { CredentialItem, CredentialKind } from '../types'

export function CredentialsPage({ api, tx }: { api: AdminApi; tx: (key: string) => string }) {
  const [kind, setKind] = useState<CredentialKind>('provider')
  const [items, setItems] = useState<CredentialItem[]>([])
  const [page, setPage] = useState(0)
  const [total, setTotal] = useState(0)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const load = useCallback(async () => { setLoading(true); setError(null); try { const result = await api.credentials(kind, page, 25); setItems(result.items); setTotal(result.total) } catch (reason) { setError(reason instanceof Error ? reason.message : 'Unable to load credentials') } finally { setLoading(false) } }, [api, kind, page])
  useEffect(() => { void load() }, [load])
  return <section className="page-view"><header className="page-head"><div><h1>{tx('credentialsTitle')}</h1><p>{tx('credentialsCopy')}</p></div></header>{error && <div className="page-error" role="alert"><span>{errorText(error, tx)}</span><button onClick={() => void load()}>{tx('retry')}</button></div>}<div className="tabs">{(['provider', 'agent-key', 'mcp'] as CredentialKind[]).map((value) => <button className={kind === value ? 'active' : ''} key={value} onClick={() => { setPage(0); setKind(value) }}>{tx(value.toUpperCase())}</button>)}</div>{loading ? <div className="page-loading"><i />{tx('loading')}</div> : <div className="credential-grid">{!error && items.map((item) => <article key={item.id}><header><i>{item.kind.slice(0, 2).toUpperCase()}</i><span><b>{item.name}</b><small>{item.organizationId ? tx('ORGANIZATION') + ' ' + item.organizationId.slice(0, 8) : tx('USER PRIVATE')}</small></span><em className={item.secretConfigured ? 'good' : 'warn'}>{tx(item.secretConfigured ? 'CONFIGURED' : 'NOT CONFIGURED')}</em></header><dl><div><dt>{tx("Endpoint")}</dt><dd>{item.endpointHost || '—'}</dd></div><div><dt>{tx("Auth")}</dt><dd>{item.authType || '—'}</dd></div><div><dt>{tx("Hint")}</dt><dd>{item.secretHint || '—'}</dd></div><div><dt>{tx("Key version")}</dt><dd>{item.secretKeyVersion || '—'}</dd></div><div><dt>{tx("Owner")}</dt><dd>{item.ownerUserId?.slice(0, 8) || '—'}</dd></div><div><dt>{tx("Status")}</dt><dd>{tx(item.status)}</dd></div></dl></article>)}{!error && !items.length && <div className="empty-state">{tx("No credential evidence")}</div>}</div>}<footer className="pagination standalone"><span>{page * 25 + (items.length ? 1 : 0)}–{page * 25 + items.length} / {total}</span><div><button disabled={page === 0 || loading} onClick={() => setPage((value) => value - 1)}>← {tx('previous')}</button><button disabled={(page + 1) * 25 >= total || loading} onClick={() => setPage((value) => value + 1)}>{tx('next')} →</button></div></footer></section>
}
