import { errorText } from '../errorText'
import { canAuthorizeOrganizationDeletion } from '../organizationActions'
import { useCallback, useEffect, useRef, useState, type FormEvent } from 'react'
import type { AdminApi } from '../adminApi'
import type { CommandResult, OrganizationMemberSummary, OrganizationSummary } from '../types'

type Props = { api: AdminApi; tx: (key: string) => string }
type OrganizationAction = 'create' | 'edit' | 'delete' | 'addMember' | 'memberRole' | 'removeMember' | 'transferOwner'
const stamp = (value: string | null) => value ? new Date(value).toLocaleString() : '—'
const isMemberAction = (value: OrganizationAction) => ['addMember', 'memberRole', 'removeMember', 'transferOwner'].includes(value)

export function OrganizationsPage({ api, tx }: Props) {
  const [items, setItems] = useState<OrganizationSummary[]>([])
  const [total, setTotal] = useState(0)
  const [page, setPage] = useState(0)
  const [query, setQuery] = useState('')
  const [status, setStatus] = useState('')
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [selected, setSelected] = useState<OrganizationSummary | null>(null)
  const [members, setMembers] = useState<OrganizationMemberSummary[]>([])
  const [memberTotal, setMemberTotal] = useState(0)
  const [memberPage, setMemberPage] = useState(0)
  const [memberQuery, setMemberQuery] = useState('')
  const [memberStatus, setMemberStatus] = useState('')
  const [memberRole, setMemberRole] = useState('')
  const [memberLoading, setMemberLoading] = useState(false)
  const [memberError, setMemberError] = useState<string | null>(null)
  const [selectedMember, setSelectedMember] = useState<OrganizationMemberSummary | null>(null)
  const [action, setAction] = useState<OrganizationAction | null>(null)
  const [submitting, setSubmitting] = useState(false)
  const [command, setCommand] = useState<CommandResult | null>(null)
  const requestVersion = useRef(0)
  const memberRequestVersion = useRef(0)
  const modalRef = useRef<HTMLFormElement>(null)

  const load = useCallback(async () => {
    const version = ++requestVersion.current
    setLoading(true); setError(null)
    try {
      const result = await api.organizations({ page, pageSize: 25, query: query.trim() || undefined, status: status || undefined })
      if (version !== requestVersion.current) return
      setItems(result.items); setTotal(result.total)
      setSelected((current) => current ? result.items.find((item) => item.id === current.id) ?? current : null)
    } catch (reason) {
      if (version === requestVersion.current) setError(reason instanceof Error ? reason.message : 'Unable to load organizations')
    } finally { if (version === requestVersion.current) setLoading(false) }
  }, [api, page, query, status])

  const loadMembers = useCallback(async () => {
    if (!selected) { setMembers([]); setMemberTotal(0); return }
    const version = ++memberRequestVersion.current
    setMemberLoading(true); setMemberError(null)
    try {
      const result = await api.organizationMembers(selected.id, {
        page: memberPage, pageSize: 20, query: memberQuery.trim() || undefined,
        status: memberStatus || undefined, role: memberRole || undefined,
      })
      if (version !== memberRequestVersion.current) return
      setMembers(result.items); setMemberTotal(result.total)
    } catch (reason) {
      if (version === memberRequestVersion.current) setMemberError(reason instanceof Error ? reason.message : 'Unable to load members')
    } finally { if (version === memberRequestVersion.current) setMemberLoading(false) }
  }, [api, selected, memberPage, memberQuery, memberStatus, memberRole])

  useEffect(() => {
    const timer = window.setTimeout(() => void load(), query ? 250 : 0)
    return () => { clearTimeout(timer); requestVersion.current += 1 }
  }, [load, query])

  useEffect(() => {
    const timer = window.setTimeout(() => void loadMembers(), memberQuery ? 250 : 0)
    return () => { clearTimeout(timer); memberRequestVersion.current += 1 }
  }, [loadMembers, memberQuery])

  useEffect(() => {
    if (!action) return
    const frame = requestAnimationFrame(() => modalRef.current?.querySelector<HTMLElement>('input, textarea, select, button')?.focus())
    const keydown = (event: KeyboardEvent) => { if (event.key === 'Escape' && !submitting) setAction(null) }
    addEventListener('keydown', keydown)
    return () => { cancelAnimationFrame(frame); removeEventListener('keydown', keydown) }
  }, [action, submitting])

  const chooseOrganization = (organization: OrganizationSummary) => {
    setSelected(organization); setSelectedMember(null); setMemberPage(0)
    setMemberQuery(''); setMemberStatus(''); setMemberRole(''); setMemberError(null)
  }

  const beginMemberAction = (next: OrganizationAction, member: OrganizationMemberSummary | null = null) => {
    setSelectedMember(member); setAction(next)
  }

  const submit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    if (!action) return
    const form = new FormData(event.currentTarget)
    const reason = String(form.get('reason') || '').trim()
    setSubmitting(true); setError(null); setMemberError(null)
    try {
      let result: CommandResult | null = null
      if (action === 'create') result = await api.createOrganization({ ownerUserId: String(form.get('ownerUserId') || '').trim(), name: String(form.get('name') || '').trim(), slug: String(form.get('slug') || '').trim(), reason })
      else if (action === 'edit' && selected) result = await api.updateOrganization(selected.id, { name: String(form.get('name') || '').trim(), slug: String(form.get('slug') || '').trim(), reason })
      else if (action === 'delete' && selected) result = await api.deleteOrganization(selected.id, reason)
      else if (action === 'addMember' && selected) result = await api.addOrganizationMember(selected.id, { userId: String(form.get('userId') || '').trim(), role: String(form.get('role') || ''), reason })
      else if (action === 'memberRole' && selected && selectedMember) result = await api.updateOrganizationMemberRole(selected.id, selectedMember.userId, { role: String(form.get('role') || ''), reason })
      else if (action === 'removeMember' && selected && selectedMember) result = await api.removeOrganizationMember(selected.id, selectedMember.userId, reason)
      else if (action === 'transferOwner' && selected) result = await api.transferOrganizationOwnership(selected.id, String(form.get('newOwnerUserId') || '').trim(), reason)
      if (!result) return
      const memberAction = isMemberAction(action)
      setCommand(result); setAction(null); setSelectedMember(null)
      if (memberAction) await Promise.all([load(), loadMembers()])
      else { setSelected(null); await load() }
    } catch (reasonValue) {
      const message = reasonValue instanceof Error ? reasonValue.message : 'Organization command failed'
      if (isMemberAction(action)) setMemberError(message); else setError(message)
    } finally { setSubmitting(false) }
  }

  const actionTitle = action === 'create' ? tx('createOrganization')
    : action === 'edit' ? tx('editOrganization') : action === 'delete' ? tx(selected?.status === 'DELETING' ? 'authorizeStorageCleanup' : 'deleteOrganization')
      : action === 'addMember' ? tx('addMember') : action === 'memberRole' ? tx('changeRole')
        : action === 'removeMember' ? tx('removeMember') : tx('transferOwner')

  return <section className="page-view">
    <header className="page-head"><div><h1>{tx('organizationTitle')}</h1></div></header>
    {error && <div className="page-error" role="alert"><span>{errorText(error, tx)}</span><button onClick={() => void load()}>{tx('retry')}</button></div>}
    {command && <div className={`command-result ${command.state.toLowerCase()}`}><div><span>{tx(command.operation)}</span><b>{tx(command.state)}</b><small>{command.commandId}</small></div>{command.safeErrorCode && <em>{command.safeErrorCode}</em>}</div>}
    <div className="toolbar"><label className="search-box"><span>⌕</span><input value={query} onChange={(event) => { setPage(0); setQuery(event.target.value) }} placeholder={tx('search')} aria-label={tx('search')} /></label><select value={status} onChange={(event) => { setPage(0); setStatus(event.target.value) }}><option value="">{tx("ALL STATUS")}</option><option value="ACTIVE">{tx("ACTIVE")}</option><option value="DELETING">{tx("DELETING")}</option><option value="DELETED">{tx("DELETED")}</option></select><button className="primary" onClick={() => { setSelected(null); setAction('create') }}>{tx('createOrganization')}</button></div>
    <div className="data-surface"><div className="data-table organizations-table"><div className="data-row heading"><span>{tx("ORGANIZATION")}</span><span>{tx('status')}</span><span>{tx("ACTIVE MEMBERS")}</span><span>{tx("CREATOR")}</span><span>{tx("UPDATED")}</span></div>{!error && items.map((organization) => <button className="data-row" key={organization.id} onClick={() => chooseOrganization(organization)}><span className="identity"><i>{organization.name.slice(0, 2).toUpperCase()}</i><span><b>{organization.name}</b><small>{organization.slug} · {organization.id.slice(0, 8)}</small></span></span><span className={`state ${organization.status === 'ACTIVE' ? 'good' : organization.status === 'DELETING' ? 'warn' : 'bad'}`}>{tx(organization.status)}</span><span>{organization.activeMembers}</span><span>{organization.creatorDisplayName || organization.creatorUserId?.slice(0, 8) || '—'}</span><span>{stamp(organization.updatedAt)}</span></button>)}{!loading && !error && !items.length && <div className="empty-state">{tx("No organizations")}</div>}</div><footer className="pagination"><span>{loading || error ? tx(error ? 'dataUnavailable' : 'loading') : `${page * 25 + (items.length ? 1 : 0)}–${page * 25 + items.length} / ${total}`}</span><div><button disabled={page === 0 || loading} onClick={() => setPage((value) => value - 1)}>← {tx('previous')}</button><button disabled={(page + 1) * 25 >= total || loading} onClick={() => setPage((value) => value + 1)}>{tx('next')} →</button></div></footer></div>
    <div className={`drawer-layer ${selected ? 'open' : ''}`}><button className="drawer-scrim" aria-label={tx("Close")} onClick={() => setSelected(null)} /><aside className="drawer" role="dialog" aria-modal="true"><header><span>{tx("ORGANIZATION /")}{selected?.id.slice(0, 8) || '—'}</span><button aria-label={tx("Close")} onClick={() => setSelected(null)}>×</button></header>{selected && <><div className="profile"><div><h2>{selected.name}</h2><p>{selected.slug} · <span className={selected.status === 'ACTIVE' ? 'good' : 'warn'}>{tx(selected.status)}</span></p></div></div><div className="detail-grid"><div><span>{tx("ACTIVE MEMBERS")}</span><b>{selected.activeMembers}</b></div><div><span>{tx("OWNER")}</span><b>{selected.creatorDisplayName || selected.creatorUserId || '—'}</b></div><div><span>{tx("CREATED")}</span><b>{stamp(selected.createdAt)}</b></div><div><span>{tx("UPDATED")}</span><b>{stamp(selected.updatedAt)}</b></div></div>
      <section className="drawer-section member-section"><header><b>{tx('organizationMembers')}</b><button disabled={selected.status !== 'ACTIVE'} onClick={() => beginMemberAction('addMember')}>{tx('addMember')}</button></header><div className="member-toolbar"><input value={memberQuery} onChange={(event) => { setMemberPage(0); setMemberQuery(event.target.value) }} placeholder={tx('memberSearch')} aria-label={tx('memberSearch')} /><select value={memberStatus} onChange={(event) => { setMemberPage(0); setMemberStatus(event.target.value) }}><option value="">{tx("ALL")}</option><option value="ACTIVE">{tx("ACTIVE")}</option><option value="SUSPENDED">{tx("SUSPENDED")}</option></select><select value={memberRole} onChange={(event) => { setMemberPage(0); setMemberRole(event.target.value) }}><option value="">{tx("ALL ROLES")}</option><option value="OWNER">{tx("OWNER")}</option><option value="ADMIN">{tx("ADMIN")}</option><option value="MEMBER">{tx("MEMBER")}</option><option value="VIEWER">{tx("VIEWER")}</option></select></div>{memberError && <div className="form-error" role="alert">{errorText(memberError, tx)}</div>}<div className="member-list">{members.map((member) => <div className="member-row" key={member.userId}><div className="identity"><i>{(member.displayName || member.loginName).slice(0, 2).toUpperCase()}</i><span><b>{member.displayName || member.loginName}</b><small>{member.loginName} · {member.userId.slice(0, 8)}</small></span></div><div className="member-evidence"><span className={`state ${member.membershipStatus === 'ACTIVE' ? 'good' : 'warn'}`}>{tx(member.membershipStatus)}</span><b>{tx(member.role)}</b></div><div className="member-actions">{member.role !== 'OWNER' && member.membershipStatus === 'ACTIVE' && <>{member.userStatus === 'ACTIVE' && <><button onClick={() => beginMemberAction('memberRole', member)}>{tx('changeRole')}</button><button onClick={() => beginMemberAction('transferOwner', member)}>{tx('transferOwner')}</button></>}<button className="danger" onClick={() => beginMemberAction('removeMember', member)}>{tx('removeMember')}</button></>}{member.membershipStatus === 'SUSPENDED' && member.userStatus === 'ACTIVE' && <button onClick={() => beginMemberAction('addMember', member)}>{tx('addMember')}</button>}{member.role === 'OWNER' && <small>{tx('ownerProtected')}</small>}</div></div>)}{!memberLoading && !memberError && !members.length && <p>{tx('noMembers')}</p>}</div><footer className="mini-pagination"><span>{memberLoading ? tx('loading') : `${memberPage * 20 + (members.length ? 1 : 0)}–${memberPage * 20 + members.length} / ${memberTotal}`}</span><button disabled={memberPage === 0 || memberLoading} onClick={() => setMemberPage((value) => value - 1)}>←</button><button disabled={(memberPage + 1) * 20 >= memberTotal || memberLoading} onClick={() => setMemberPage((value) => value + 1)}>→</button></footer></section>
      <section className="drawer-section"><header><b>{tx("CONTROLLED ACTIONS")}</b><span>{tx('authenticatedSessionRequired')}</span></header><div className="action-grid"><button disabled={selected.status !== 'ACTIVE'} onClick={() => setAction('edit')}>{tx('editOrganization')}</button><button className="danger" disabled={!canAuthorizeOrganizationDeletion(selected.status)} onClick={() => setAction('delete')}>{tx(selected.status === 'DELETING' ? 'authorizeStorageCleanup' : 'deleteOrganization')}</button></div><p className="contract-note">{tx('organizationDeleteCopy')}</p></section></>}</aside></div>
    {action && <div className="modal-layer"><form ref={modalRef} className="modal" role="dialog" aria-modal="true" aria-labelledby="organization-command-title" onSubmit={submit}><h2 id="organization-command-title">{actionTitle}</h2>{action === 'delete' && <p role="alert">{tx('storageCleanupWarning')}</p>}{action !== 'delete' && action !== 'removeMember' && <div className="form-grid">{action === 'create' && <label><span>{tx('ownerUserId')}</span><input name="ownerUserId" required maxLength={36} /></label>}{(action === 'create' || action === 'edit') && <><label><span>{tx("NAME")}</span><input name="name" defaultValue={action === 'edit' ? selected?.name : ''} required maxLength={120} /></label><label><span>{tx("SLUG")}</span><input name="slug" defaultValue={action === 'edit' ? selected?.slug : ''} required pattern="[a-z0-9-]+" maxLength={63} /></label></>}{action === 'addMember' && <><label><span>{tx("USER ID")}</span><input name="userId" defaultValue={selectedMember?.userId || ''} required maxLength={36} /></label><label><span>{tx('memberRole')}</span><select name="role" defaultValue={selectedMember?.role === 'OWNER' ? 'MEMBER' : selectedMember?.role || 'MEMBER'}><option value="ADMIN">{tx("ADMIN")}</option><option value="MEMBER">{tx("MEMBER")}</option><option value="VIEWER">{tx("VIEWER")}</option></select></label></>}{action === 'memberRole' && <label><span>{tx('memberRole')}</span><select name="role" defaultValue={selectedMember?.role || 'MEMBER'}><option value="ADMIN">{tx("ADMIN")}</option><option value="MEMBER">{tx("MEMBER")}</option><option value="VIEWER">{tx("VIEWER")}</option></select></label>}{action === 'transferOwner' && <label><span>{tx('ownerUserId')}</span><input name="newOwnerUserId" defaultValue={selectedMember?.userId || ''} required maxLength={36} /></label>}</div>}<label><span>{tx('operationReason')}</span><textarea name="reason" required maxLength={500} /></label><div className="modal-actions"><button type="button" onClick={() => setAction(null)}>{tx('cancel')}</button><button className={(action === 'delete' || action === 'removeMember' || action === 'transferOwner') ? 'danger' : 'primary'} disabled={submitting}>{submitting ? tx('loading') : tx('confirm')}</button></div></form></div>}
  </section>
}
