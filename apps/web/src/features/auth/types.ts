export type AuthToken = {
  token: string
  userId: string
  username: string
  role: string
  tenantId: string
  tenantRole: string
  expiresAt: string | null
  refreshExpiresAt: string | null
}

export type CurrentUser = {
  userId: string
  username: string
  displayName: string | null
  role: string
  tenantId: string
  tenantRole: string
  createdAt: string
}

export type Organization = {
  id: string
  name: string
  slug: string
  creatorUserId: string
  status: string
  createdAt: string
  updatedAt: string
  deletionRequestedAt: string | null
}

export type OrganizationMembership = {
  organizationId: string
  userId: string
  role: string
  status: string
  joinedAt: string
  updatedAt: string
}

export type OrganizationSummary = {
  organization: Organization
  membership: OrganizationMembership
}

export type AuthenticatedSession = {
  token: AuthToken
  user: CurrentUser
  organizations: OrganizationSummary[]
}

export type LoginInput = {
  username: string
  password: string
}

export type RegisterInput = LoginInput & {
  displayName?: string
}

export type SessionPersistence = 'session' | 'local'
