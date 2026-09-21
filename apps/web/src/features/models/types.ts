export type ModelProvider = {
  id: string
  name: string
  type: string
  baseUrl: string
  apiKey: string
  authType: string
  enabled: boolean
  isDefault: boolean
  connectionStatus: string
  lastTestedAt: string | null
  lastTestLatencyMs: number | null
  lastTestErrorCode: string | null
  createdAt: string
  updatedAt: string
}

export type ProviderModel = {
  id: string
  providerId: string
  modelId: string
  displayName: string
  isDefault: boolean
  maxContextTokens: number
  createdAt: string
}

export type ProviderTest = {
  providerId: string
  success: boolean
  status: string
  latencyMs: number
  discoveredModelIds: string[]
  errorCode: string | null
  testedAt: string
}

export type ProviderModelTest = {
  providerId: string
  modelId: string
  success: boolean
  latencyMs: number
  inputTokens: number
  outputTokens: number
  responsePreview: string
  errorCode: string | null
  testedAt: string
}

export type ModelPool = {
  id: string
  tenantId: string
  ownerId: string
  name: string
  visibility: 'PRIVATE' | 'ORGANIZATION'
  routingStrategy: 'PRIORITY' | 'WEIGHTED' | 'COST' | 'LATENCY'
  fallbackEnabled: boolean
  status: string
  createdAt: string
  updatedAt: string
}

export type ModelPoolMember = {
  id: string
  poolId: string
  providerId: string
  providerModelId: string
  modelId: string
  priority: number
  weight: number
  enabled: boolean
  createdAt: string
  updatedAt: string
}

export type InferenceBudgetPolicy = {
  tenantId: string
  enabled: boolean
  monthlyRequestLimit: number
  monthlyTokenLimit: number
  monthlyCostLimitMicros: number
  revision: number
  updatedAt: string
}

export type InferenceBudgetUsage = {
  consumedRequests: number
  reservedRequests: number
  consumedTokens: number
  reservedTokens: number
  consumedCostMicros: number
  reservedCostMicros: number
}

export type ModelPrice = {
  id: string
  providerModelId: string
  version: number
  inputMicrosPerMillionTokens: number
  outputMicrosPerMillionTokens: number
  currency: string
  effectiveFrom: string
  effectiveUntil: string | null
  createdAt: string
}
