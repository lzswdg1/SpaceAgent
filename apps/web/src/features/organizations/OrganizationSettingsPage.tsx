import { useCallback, useEffect, useState, type FormEvent } from 'react'
import type { AuthenticatedSession, Organization, OrganizationMembership } from '../auth/types'
import type { AuthenticatedRequest } from '../overview/types'
import {
  acceptOrganizationInvitation,
  addOrganizationMember,
  changeAccountPassword,
  createOrganization,
  createOrganizationInvitation,
  leaveOrganization,
  listOrganizationInvitations,
  loadOrganizationSettings,
  removeOrganizationMember,
  revokeOrganizationInvitation,
  transferOrganizationOwnership,
  type OrganizationInvitation,
  type OrganizationInvitationCreated,
  type OrganizationRole,
} from './organizationApi'
import './organizations.css'

type Props = {
  session: AuthenticatedSession
  request: AuthenticatedRequest
  onSwitchOrganization: (organizationId: string) => Promise<AuthenticatedSession>
  tx: (key: string) => string
}

type AssignableRole = Exclude<OrganizationRole, 'OWNER'>
type Confirmation =
  | { kind: 'role'; member: OrganizationMembership; role: AssignableRole }
  | { kind: 'remove'; member: OrganizationMembership }
  | { kind: 'transfer'; member: OrganizationMembership }
  | { kind: 'leave'; organization: Organization }
  | { kind: 'revoke'; invitation: OrganizationInvitation }

const assignableRoles: AssignableRole[] = ['ADMIN', 'MEMBER', 'VIEWER']

export function OrganizationSettingsPage({ session, request, onSwitchOrganization, tx }: Props) {
  const tenantId = session.user.tenantId
  const currentRole = session.user.tenantRole as OrganizationRole
  const isManager = currentRole === 'OWNER' || currentRole === 'ADMIN'
  const [organization, setOrganization] = useState<Organization | null>(null)
  const [members, setMembers] = useState<OrganizationMembership[]>([])
  const [invitations, setInvitations] = useState<OrganizationInvitation[]>([])
  const [oneTimeInvitation, setOneTimeInvitation] = useState<OrganizationInvitationCreated | null>(null)
  const [switchTarget, setSwitchTarget] = useState<Organization | null>(null)
  const [confirmation, setConfirmation] = useState<Confirmation | null>(null)
  const [createOpen, setCreateOpen] = useState(false)
  const [busy, setBusy] = useState(false)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [passwordChanged, setPasswordChanged] = useState(false)

  const load = useCallback(async () => {
    setLoading(true); setError(null)
    try {
      const [settings, nextInvitations] = await Promise.all([
        loadOrganizationSettings(request, tenantId),
        isManager ? listOrganizationInvitations(request, tenantId) : Promise.resolve([]),
      ])
      setOrganization(settings.organization)
      setMembers(settings.members)
      setInvitations(nextInvitations)
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : tx('organizationSettingsLoadFailed'))
    } finally { setLoading(false) }
  }, [isManager, request, tenantId, tx])

  useEffect(() => {
    setOneTimeInvitation(null)
    setConfirmation(null)
    void load()
  }, [load])

  const confirmSwitch = async () => {
    if (!switchTarget || busy) return
    setBusy(true); setError(null)
    try { await onSwitchOrganization(switchTarget.id); setSwitchTarget(null) }
    catch (reason) { setError(reason instanceof Error ? reason.message : tx('organizationSwitchFailed')) }
    finally { setBusy(false) }
  }

  const submitOrganization = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    const data = new FormData(event.currentTarget)
    setBusy(true); setError(null)
    try {
      const created = await createOrganization(request, {
        name: String(data.get('name') ?? '').trim(),
        slug: String(data.get('slug') ?? '').trim(),
      })
      setCreateOpen(false)
      await onSwitchOrganization(created.id)
    } catch (reason) { setError(reason instanceof Error ? reason.message : tx('organizationCreateFailed')) }
    finally { setBusy(false) }
  }

  const submitMember = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    if (!isManager || busy) return
    const form = event.currentTarget
    const data = new FormData(form)
    setBusy(true); setError(null)
    try {
      await addOrganizationMember(request, tenantId, {
        userId: String(data.get('userId') ?? '').trim(),
        role: String(data.get('role')) as AssignableRole,
      })
      form.reset()
      await load()
    } catch (reason) { setError(reason instanceof Error ? reason.message : tx('organizationMemberSaveFailed')) }
    finally { setBusy(false) }
  }

  const submitInvitation = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    if (!isManager || busy) return
    const form = event.currentTarget
    const data = new FormData(form)
    setBusy(true); setError(null)
    try {
      const created = await createOrganizationInvitation(request, tenantId, {
        email: String(data.get('email') ?? '').trim(),
        role: String(data.get('role')) as AssignableRole,
        expiresInHours: Number(data.get('expiresInHours')),
      })
      setOneTimeInvitation(created)
      form.reset()
      setInvitations(await listOrganizationInvitations(request, tenantId))
    } catch (reason) { setError(reason instanceof Error ? reason.message : tx('organizationInvitationCreateFailed')) }
    finally { setBusy(false) }
  }

  const submitInvitationAcceptance = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    if (busy) return
    const form = event.currentTarget
    const token = String(new FormData(form).get('token') ?? '').trim()
    setBusy(true); setError(null)
    try {
      const accepted = await acceptOrganizationInvitation(request, token)
      form.reset()
      await onSwitchOrganization(accepted.membership.organizationId)
    } catch (reason) { setError(reason instanceof Error ? reason.message : tx('organizationInvitationAcceptFailed')) }
    finally { setBusy(false) }
  }

  const submitPasswordChange = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    if (busy) return
    const form = event.currentTarget
    const data = new FormData(form)
    const oldPassword = String(data.get('oldPassword') ?? '')
    const newPassword = String(data.get('newPassword') ?? '')
    const confirmation = String(data.get('confirmPassword') ?? '')
    if (newPassword !== confirmation) {
      setPasswordChanged(false)
      setError(tx('passwordConfirmationMismatch'))
      return
    }
    setBusy(true); setError(null); setPasswordChanged(false)
    try {
      await changeAccountPassword(request, oldPassword, newPassword)
      form.reset()
      setPasswordChanged(true)
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : tx('passwordChangeFailed'))
    } finally { setBusy(false) }
  }

  const runConfirmedAction = async () => {
    if (!confirmation || busy) return
    setBusy(true); setError(null)
    try {
      if (confirmation.kind === 'role') {
        await addOrganizationMember(request, tenantId, {
          userId: confirmation.member.userId, role: confirmation.role,
        })
        await load()
      } else if (confirmation.kind === 'remove') {
        await removeOrganizationMember(request, tenantId, confirmation.member.userId)
        await load()
      } else if (confirmation.kind === 'transfer') {
        await transferOrganizationOwnership(request, tenantId, confirmation.member.userId)
        await onSwitchOrganization(tenantId)
      } else if (confirmation.kind === 'leave') {
        await leaveOrganization(request, confirmation.organization.id)
        await onSwitchOrganization(tenantId)
      } else {
        await revokeOrganizationInvitation(request, tenantId, confirmation.invitation.id)
        setInvitations(await listOrganizationInvitations(request, tenantId))
      }
      setConfirmation(null)
    } catch (reason) { setError(reason instanceof Error ? reason.message : tx('organizationActionFailed')) }
    finally { setBusy(false) }
  }

  const canManage = (member: OrganizationMembership) => isManager
    && member.userId !== session.user.userId
    && member.role !== 'OWNER'
    && !(currentRole === 'ADMIN' && member.role === 'ADMIN')

  const confirmationTitle = confirmation?.kind === 'remove' ? tx('removeOrganizationMember')
    : confirmation?.kind === 'transfer' ? tx('transferOrganizationOwnership')
      : confirmation?.kind === 'leave' ? tx('leaveOrganization')
        : confirmation?.kind === 'revoke' ? tx('revokeOrganizationInvitation')
          : tx('changeOrganizationRole')
  const confirmationSubject = confirmation?.kind === 'leave' ? confirmation.organization.name
    : confirmation?.kind === 'revoke' ? confirmation.invitation.email
      : confirmation ? `${confirmation.member.userId}${confirmation.kind === 'role' ? ` → ${confirmation.role}` : ''}` : ''

  return <section className="organization-settings">
    <header className="settings-heading"><div><span>MANAGE / ORGANIZATION</span><h1>{tx('organizationControl')}</h1><p>{tx('organizationControlHint')}</p></div><button onClick={() => setCreateOpen(true)}>{tx('newOrganization')}</button></header>
    {error && <div className="settings-error" role="alert"><span>{error}</span><button onClick={() => setError(null)}>×</button></div>}
    <div className="settings-grid">
      <section className="settings-card current-organization"><header><span>01</span><b>{tx('currentOrganization')}</b><em>{organization?.status || (loading ? 'LOADING' : '—')}</em></header><h2>{organization?.name || '—'}</h2><p>{organization?.slug || '—'}</p><dl><div><dt>{tx('yourRole')}</dt><dd>{currentRole}</dd></div><div><dt>{tx('members')}</dt><dd>{members.length}</dd></div><div><dt>{tx('created')}</dt><dd>{organization ? new Date(organization.createdAt).toLocaleDateString() : '—'}</dd></div></dl></section>

      <section className="settings-card organization-contexts"><header><span>02</span><b>{tx('organizationContext')}</b><em>{session.organizations.length.toString().padStart(2, '0')}</em></header><div>{session.organizations.map(({ organization: item, membership }) => <article key={item.id}><span><b>{item.name}</b><small>{item.slug} · {membership.role}</small></span>{item.id === tenantId ? <em>{tx('active')}</em> : <div className="organization-context-actions"><button onClick={() => setSwitchTarget(item)}>{tx('activate')}</button>{membership.role !== 'OWNER' && <button className="danger" onClick={() => setConfirmation({ kind: 'leave', organization: item })}>{tx('leave')}</button>}</div>}</article>)}</div><form className="invitation-accept" onSubmit={submitInvitationAcceptance}><label><span>{tx('acceptInvitation')}</span><input name="token" minLength={32} maxLength={512} required autoComplete="off" placeholder={tx('invitationToken')} /></label><button disabled={busy}>{busy ? tx('processing') : tx('accept')}</button></form></section>

      <section className="settings-card member-boundary"><header><span>03</span><b>{tx('accessBoundary')}</b><em>{members.length.toString().padStart(2, '0')}</em></header><div>{members.map((member) => <article key={member.userId}><span className="member-id">{member.userId === session.user.userId ? session.user.displayName || session.user.username : member.userId}</span>{canManage(member) ? <select aria-label={`${tx('organizationRole')} ${member.userId}`} value={member.role} disabled={busy} onChange={(event) => setConfirmation({ kind: 'role', member, role: event.target.value as AssignableRole })}>{assignableRoles.map((role) => <option key={role}>{role}</option>)}</select> : <span>{member.role}</span>}<em>{member.status}</em>{canManage(member) && <div className="member-actions">{currentRole === 'OWNER' && <button onClick={() => setConfirmation({ kind: 'transfer', member })}>{tx('transferOwner')}</button>}<button className="danger" onClick={() => setConfirmation({ kind: 'remove', member })}>{tx('remove')}</button></div>}</article>)}</div>{isManager && <form className="member-add" onSubmit={submitMember}><label><span>{tx('memberUserId')}</span><input name="userId" maxLength={160} required /></label><label><span>{tx('organizationRole')}</span><select name="role" defaultValue="MEMBER">{assignableRoles.map((role) => <option key={role}>{role}</option>)}</select></label><button disabled={busy}>{busy ? tx('saving') : tx('addMember')}</button></form>}</section>

      <section className="settings-card invitation-card"><header><span>04</span><b>{tx('organizationInvitations')}</b><em>{isManager ? invitations.length.toString().padStart(2, '0') : 'LOCKED'}</em></header>{isManager ? <>{oneTimeInvitation && <div className="invitation-token"><b>{tx('copyInvitationTokenNow')}</b><code>{oneTimeInvitation.token}</code><small>{tx('invitationTokenOneTime')}</small><button onClick={() => setOneTimeInvitation(null)}>{tx('dismiss')}</button></div>}<form className="invitation-create" onSubmit={submitInvitation}><label><span>{tx('email')}</span><input name="email" type="email" maxLength={255} required /></label><label><span>{tx('organizationRole')}</span><select name="role" defaultValue="MEMBER">{assignableRoles.map((role) => <option key={role}>{role}</option>)}</select></label><label><span>{tx('invitationLifetime')}</span><input name="expiresInHours" type="number" min="1" max="720" defaultValue="24" required /></label><button disabled={busy}>{busy ? tx('saving') : tx('createInvitation')}</button></form><div className="invitation-list">{invitations.map((invitation) => <article key={invitation.id}><span><b>{invitation.email}</b><small>{invitation.role} · {new Date(invitation.expiresAt).toLocaleString()}</small></span><em>{invitation.status}</em>{invitation.status === 'PENDING' && <button className="danger" disabled={busy} onClick={() => setConfirmation({ kind: 'revoke', invitation })}>{tx('revoke')}</button>}</article>)}{!loading && !invitations.length && <small>{tx('noOrganizationInvitations')}</small>}</div></> : <p>{tx('organizationManagerRequired')}</p>}</section>

      <section className="settings-card policy-card"><header><span>05</span><b>{tx('agentSharingPolicy')}</b><em>UNAVAILABLE</em></header><div className="policy-row"><span><b>{tx('organizationAgentSharing')}</b><small>{tx('organizationAgentSharingHint')}</small></span><span className="policy-unavailable" aria-disabled="true">{tx('unavailable')}</span></div><p>{tx('agentSharingContractMissing')}</p></section>
      <section className="settings-card credential-card"><header><span>06</span><b>{tx('credentialsAndKeys')}</b><em>ACCOUNT</em></header><h2>{tx('changePassword')}</h2><form className="password-change" onSubmit={submitPasswordChange}><label><span>{tx('currentPassword')}</span><input name="oldPassword" type="password" autoComplete="current-password" minLength={10} maxLength={64} required /></label><label><span>{tx('newPassword')}</span><input name="newPassword" type="password" autoComplete="new-password" minLength={10} maxLength={64} required /></label><label><span>{tx('confirmNewPassword')}</span><input name="confirmPassword" type="password" autoComplete="new-password" minLength={10} maxLength={64} required /></label><button disabled={busy}>{busy ? tx('processing') : tx('changePassword')}</button>{passwordChanged && <small role="status">{tx('passwordChanged')}</small>}</form><p>{tx('credentialPlaceholderHint')}</p></section>
    </div>

    {switchTarget && <div className="settings-modal" role="dialog" aria-modal="true"><div className="settings-dialog compact"><header><div><span>{tx('systemConfirmation')}</span><h2>{tx('switchOrganization')}</h2></div></header><p>{switchTarget.name}</p><div className="settings-actions"><button onClick={() => setSwitchTarget(null)}>{tx('cancel')}</button><button className="primary" disabled={busy} onClick={() => void confirmSwitch()}>{busy ? tx('switching') : tx('confirmSwitch')}</button></div></div></div>}
    {confirmation && <div className="settings-modal" role="dialog" aria-modal="true" aria-labelledby="organization-action-title"><div className="settings-dialog compact"><header><div><span>{tx('systemConfirmation')}</span><h2 id="organization-action-title">{confirmationTitle}</h2></div></header><p>{confirmationSubject}</p><small>{tx('organizationActionWarning')}</small><div className="settings-actions"><button disabled={busy} onClick={() => setConfirmation(null)}>{tx('cancel')}</button><button className="danger" disabled={busy} onClick={() => void runConfirmedAction()}>{busy ? tx('processing') : tx('confirm')}</button></div></div></div>}
    {createOpen && <div className="settings-modal" role="dialog" aria-modal="true"><form className="settings-dialog" onSubmit={submitOrganization}><header><div><span>ORGANIZATION / NEW</span><h2>{tx('newOrganization')}</h2></div><button type="button" onClick={() => setCreateOpen(false)}>×</button></header><label><span>{tx('organizationName')}</span><input name="name" required maxLength={120} /></label><label><span>SLUG</span><input name="slug" required maxLength={63} pattern="[a-z0-9-]+" /></label><button className="submit" disabled={busy}>{busy ? tx('saving') : tx('createAndSwitch')}</button></form></div>}
  </section>
}
