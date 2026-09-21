import { describe, expect, it, vi } from 'vitest'
import type { AuthenticatedRequest } from '../overview/types'
import { acceptOrganizationInvitation, addOrganizationMember, changeAccountPassword, createOrganization, createOrganizationInvitation, leaveOrganization, listOrganizationInvitations, loadOrganizationSettings, removeOrganizationMember, revokeOrganizationInvitation, transferOrganizationOwnership } from './organizationApi'

describe('organization settings API', () => {
  it('loads active organization and membership from public routes', async () => {
    const request = vi.fn(async (path: string) => path.endsWith('/members') ? [] : {}) as unknown as AuthenticatedRequest
    await loadOrganizationSettings(request, 'org/1')
    expect(request).toHaveBeenCalledWith('/api/v1/organizations/org%2F1')
    expect(request).toHaveBeenCalledWith('/api/v1/organizations/org%2F1/members')
  })

  it('creates a new organization without browser-owned policy fields', async () => {
    const request = vi.fn(async () => ({})) as unknown as AuthenticatedRequest
    await createOrganization(request, { name: 'Orbit Lab', slug: 'orbit-lab' })
    expect(request).toHaveBeenCalledWith('/api/v1/organizations', {
      method: 'POST', body: JSON.stringify({ name: 'Orbit Lab', slug: 'orbit-lab' }),
    })
  })

  it('maps member role, removal, ownership and leave to public Organization routes', async () => {
    const request = vi.fn(async () => ({})) as unknown as AuthenticatedRequest
    await addOrganizationMember(request, 'org/1', { userId: 'user/2', role: 'ADMIN' })
    await removeOrganizationMember(request, 'org/1', 'user/2')
    await transferOrganizationOwnership(request, 'org/1', 'user/2')
    await leaveOrganization(request, 'org/1')
    expect(request).toHaveBeenNthCalledWith(1, '/api/v1/organizations/org%2F1/members', {
      method: 'POST', body: JSON.stringify({ userId: 'user/2', role: 'ADMIN' }),
    })
    expect(request).toHaveBeenNthCalledWith(2, '/api/v1/organizations/org%2F1/members/user%2F2', { method: 'DELETE' })
    expect(request).toHaveBeenNthCalledWith(3, '/api/v1/organizations/org%2F1/transfer-owner', {
      method: 'POST', body: JSON.stringify({ newOwnerUserId: 'user/2' }),
    })
    expect(request).toHaveBeenNthCalledWith(4, '/api/v1/organizations/org%2F1/leave', { method: 'POST' })
  })

  it('maps invitation lifecycle without persisting the one-time token', async () => {
    const request = vi.fn(async () => ({})) as unknown as AuthenticatedRequest
    await listOrganizationInvitations(request, 'org/1')
    await createOrganizationInvitation(request, 'org/1', {
      email: 'member@example.com', role: 'MEMBER', expiresInHours: 24,
    })
    await revokeOrganizationInvitation(request, 'org/1', 'invite/1')
    await acceptOrganizationInvitation(request, 'x'.repeat(43))
    expect(request).toHaveBeenNthCalledWith(1, '/api/v1/organizations/org%2F1/invitations')
    expect(request).toHaveBeenNthCalledWith(2, '/api/v1/organizations/org%2F1/invitations', {
      method: 'POST', body: JSON.stringify({ email: 'member@example.com', role: 'MEMBER', expiresInHours: 24 }),
    })
    expect(request).toHaveBeenNthCalledWith(3, '/api/v1/organizations/org%2F1/invitations/invite%2F1', { method: 'DELETE' })
    expect(request).toHaveBeenNthCalledWith(4, '/api/v1/organization-invitations/accept', {
      method: 'POST', body: JSON.stringify({ token: 'x'.repeat(43) }),
    })
  })

  it('changes the authenticated account password through the public identity boundary', async () => {
    const request = vi.fn(async () => undefined) as unknown as AuthenticatedRequest
    await changeAccountPassword(request, 'old-secret', 'new-secret-value')
    expect(request).toHaveBeenCalledWith('/api/v1/auth/change-password', {
      method: 'POST', body: JSON.stringify({ oldPassword: 'old-secret', newPassword: 'new-secret-value' }),
    })
  })
})
