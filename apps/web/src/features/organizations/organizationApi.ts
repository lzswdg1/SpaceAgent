import type { Organization, OrganizationMembership } from '../auth/types'
import type { AuthenticatedRequest } from '../overview/types'

export type OrganizationRole = 'OWNER' | 'ADMIN' | 'MEMBER' | 'VIEWER'

export type OrganizationInvitation = {
  id: string
  organizationId: string
  email: string
  role: OrganizationRole
  status: 'PENDING' | 'ACCEPTED' | 'REVOKED' | 'EXPIRED'
  invitedByUserId: string
  expiresAt: string
  acceptedByUserId: string | null
  createdAt: string
  updatedAt: string
  acceptedAt: string | null
  revokedAt: string | null
}

export type OrganizationInvitationCreated = {
  invitation: OrganizationInvitation
  token: string
}

export type AcceptedOrganizationInvitation = {
  invitationId: string
  membership: OrganizationMembership
}

export type OrganizationLeave = {
  organizationId: string
  organizationStatus: string
  remainingActiveMembers: number
}

export const loadOrganizationSettings = async (request: AuthenticatedRequest, organizationId: string) => {
  const encoded = encodeURIComponent(organizationId)
  const [organization, members] = await Promise.all([
    request<Organization>(`/api/v1/organizations/${encoded}`),
    request<OrganizationMembership[]>(`/api/v1/organizations/${encoded}/members`),
  ])
  return { organization, members }
}

export const createOrganization = (
  request: AuthenticatedRequest,
  input: { name: string; slug: string },
) => request<Organization>('/api/v1/organizations', { method: 'POST', body: JSON.stringify(input) })

export const addOrganizationMember = (
  request: AuthenticatedRequest,
  organizationId: string,
  input: { userId: string; role: Exclude<OrganizationRole, 'OWNER'> },
) => request<OrganizationMembership>(`/api/v1/organizations/${encodeURIComponent(organizationId)}/members`, {
  method: 'POST', body: JSON.stringify(input),
})

export const removeOrganizationMember = (
  request: AuthenticatedRequest,
  organizationId: string,
  userId: string,
) => request<void>(`/api/v1/organizations/${encodeURIComponent(organizationId)}/members/${encodeURIComponent(userId)}`, {
  method: 'DELETE',
})

export const transferOrganizationOwnership = (
  request: AuthenticatedRequest,
  organizationId: string,
  newOwnerUserId: string,
) => request<Organization>(`/api/v1/organizations/${encodeURIComponent(organizationId)}/transfer-owner`, {
  method: 'POST', body: JSON.stringify({ newOwnerUserId }),
})

export const leaveOrganization = (
  request: AuthenticatedRequest,
  organizationId: string,
) => request<OrganizationLeave>(`/api/v1/organizations/${encodeURIComponent(organizationId)}/leave`, {
  method: 'POST',
})

export const listOrganizationInvitations = (
  request: AuthenticatedRequest,
  organizationId: string,
) => request<OrganizationInvitation[]>(`/api/v1/organizations/${encodeURIComponent(organizationId)}/invitations`)

export const createOrganizationInvitation = (
  request: AuthenticatedRequest,
  organizationId: string,
  input: { email: string; role: Exclude<OrganizationRole, 'OWNER'>; expiresInHours: number },
) => request<OrganizationInvitationCreated>(`/api/v1/organizations/${encodeURIComponent(organizationId)}/invitations`, {
  method: 'POST', body: JSON.stringify(input),
})

export const revokeOrganizationInvitation = (
  request: AuthenticatedRequest,
  organizationId: string,
  invitationId: string,
) => request<OrganizationInvitation>(`/api/v1/organizations/${encodeURIComponent(organizationId)}/invitations/${encodeURIComponent(invitationId)}`, {
  method: 'DELETE',
})

export const acceptOrganizationInvitation = (
  request: AuthenticatedRequest,
  token: string,
) => request<AcceptedOrganizationInvitation>('/api/v1/organization-invitations/accept', {
  method: 'POST', body: JSON.stringify({ token }),
})

export const changeAccountPassword = (
  request: AuthenticatedRequest,
  oldPassword: string,
  newPassword: string,
) => request<void>('/api/v1/auth/change-password', {
  method: 'POST', body: JSON.stringify({ oldPassword, newPassword }),
})
