export type McpEntry = {
  id: string
  slug: string
  name: string
  description: string
  transport: string
  authType: 'NONE' | 'OAUTH2' | 'BEARER' | 'CUSTOM'
  defaultEndpoint: string | null
  manifestJson: string
  publisherNamespace: string
  registryName: string
  sourceType: 'CURATED' | 'PRIVATE' | 'OFFICIAL_REGISTRY'
  trustTier: 'REGISTRY_VERIFIED' | 'ORGANIZATION_TRUSTED' | 'UNVERIFIED'
  lifecycle: 'CANDIDATE' | 'APPROVED' | 'DEPRECATED' | 'REVOKED'
  currentVersionId: string | null
  currentVersion: string | null
  currentManifestSha256: string | null
  revision: number
}

export type McpTransport = {
  id: string
  position: number
  transportType: string
  endpointTemplate: string
  endpointConfigurable: boolean
  variablesSchemaJson: string | null
  headersSchemaJson: string | null
  enabled: boolean
}

export type McpServerVersion = {
  id: string
  entryId: string
  version: string
  sourceType: McpEntry['sourceType']
  sourceUri: string | null
  manifestSchemaUri: string | null
  manifestJson: string
  manifestSha256: string
  authType: McpEntry['authType']
  lifecycleState: 'APPROVED' | 'DEPRECATED' | 'REVOKED'
  publishedAt: string | null
  createdAt: string
  transports: McpTransport[]
}

export type McpInstallation = {
  id: string
  entryId: string
  serverVersionId: string
  serverVersion: string
  tenantId: string
  subjectId: string
  createdBy: string
  scope: 'USER' | 'ORGANIZATION'
  displayName: string
  state: string
  createdAt: string
  updatedAt: string
}

export type McpConnection = {
  id: string
  installationId: string
  tenantId: string
  managedBy: string
  endpointUrl: string
  authType: McpEntry['authType']
  state: string
  authConfigured: boolean
  externalAccountId: string | null
  externalAccountName: string | null
  revision: number
  createdAt: string
  updatedAt: string
  revokedAt: string | null
}

export type GithubOAuth = { state: string; authorizationUrl: string; expiresAt: string }
export type GithubAccount = { connectionId: string; accountId: string; login: string }

export type McpOAuth = {
  state: string
  authorizationUrl: string
  authorizationServer: string
  clientRegistrationId: string
  expiresAt: string
}

export type McpOAuthGrant = {
  connectionId: string
  state: McpConnection['state']
  tokenExpiresAt: string | null
  grantedScopes: string[]
}

export type McpConnectionObservation = {
  id: string
  outcome: 'SUCCEEDED' | 'FAILED'
  connectionRevision: number
  latencyMs: number
  protocolVersion: string | null
  snapshotId: string | null
  safeErrorCode: string | null
  observedAt: string
}

export type McpQualification = {
  connectionId: string
  state: McpConnection['state']
  connectionRevision: number
  snapshotId: string | null
  snapshotSha256: string | null
  protocolVersion: string | null
  serverName: string | null
  serverTitle: string | null
  serverVersion: string | null
  toolCount: number
  qualifiedAt: string | null
  lastObservation: McpConnectionObservation | null
}
