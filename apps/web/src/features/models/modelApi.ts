import type { AuthenticatedRequest } from '../overview/types'
import type { InferenceBudgetPolicy, InferenceBudgetUsage, ModelPool, ModelPoolMember, ModelPrice, ModelProvider, ProviderModel, ProviderModelTest, ProviderTest } from './types'

export async function loadModelResources(request: AuthenticatedRequest) {
  const [providers, pools] = await Promise.all([
    request<ModelProvider[]>('/api/v1/model-providers'),
    request<ModelPool[]>('/api/v1/model-pools'),
  ])
  return { providers, pools }
}

export const listProviderModels = (request: AuthenticatedRequest, providerId: string) => request<ProviderModel[]>(
  `/api/v1/model-providers/${encodeURIComponent(providerId)}/models`,
)

export const createProvider = (
  request: AuthenticatedRequest,
  input: {
    name: string
    type: string
    baseUrl: string
    apiKey: string
    authType: string
    enabled: boolean
    isDefault: boolean
    models: Array<{ modelId: string; displayName: string; maxContextTokens: number; isDefault: boolean }>
  },
) => request<ModelProvider>('/api/v1/model-providers', { method: 'POST', body: JSON.stringify(input) })

export const updateProvider = (
  request: AuthenticatedRequest,
  providerId: string,
  input: {
    name: string
    type: string
    baseUrl: string
    apiKey: string
    authType: string
    enabled: boolean
    isDefault: boolean
  },
) => request<ModelProvider>(`/api/v1/model-providers/${encodeURIComponent(providerId)}`, {
  method: 'PUT', body: JSON.stringify(input),
})

export const deleteProvider = (request: AuthenticatedRequest, providerId: string) => request<void>(
  `/api/v1/model-providers/${encodeURIComponent(providerId)}`,
  { method: 'DELETE' },
)

export const testProvider = (request: AuthenticatedRequest, providerId: string) => request<ProviderTest>(
  `/api/v1/model-providers/${encodeURIComponent(providerId)}/test`,
  { method: 'POST' },
)

export const testProviderModel = (
  request: AuthenticatedRequest,
  providerId: string,
  modelId: string,
) => request<ProviderModelTest>(
  `/api/v1/model-providers/${encodeURIComponent(providerId)}/models/test`,
  { method: 'POST', body: JSON.stringify({ modelId }) },
)

export const addProviderModel = (
  request: AuthenticatedRequest,
  providerId: string,
  input: { modelId: string; displayName: string; maxContextTokens: number; isDefault: boolean },
) => request<ProviderModel>(`/api/v1/model-providers/${encodeURIComponent(providerId)}/models`, {
  method: 'POST', body: JSON.stringify(input),
})

export const deleteProviderModel = (
  request: AuthenticatedRequest,
  providerId: string,
  modelId: string,
) => request<void>(
  `/api/v1/model-providers/${encodeURIComponent(providerId)}/models/${encodeURIComponent(modelId)}`,
  { method: 'DELETE' },
)

export const createModelPool = (
  request: AuthenticatedRequest,
  input: { name: string; visibility: ModelPool['visibility']; routingStrategy: ModelPool['routingStrategy']; fallbackEnabled: boolean },
) => request<ModelPool>('/api/v1/model-pools', { method: 'POST', body: JSON.stringify(input) })

export const listPoolMembers = (request: AuthenticatedRequest, poolId: string) => request<ModelPoolMember[]>(
  `/api/v1/model-pools/${encodeURIComponent(poolId)}/members`,
)

export const addPoolMember = (
  request: AuthenticatedRequest,
  poolId: string,
  input: { providerId: string; providerModelId: string; priority: number; weight: number },
) => request<ModelPoolMember>(`/api/v1/model-pools/${encodeURIComponent(poolId)}/members`, {
  method: 'POST', body: JSON.stringify(input),
})

export const removePoolMember = (request: AuthenticatedRequest, poolId: string, memberId: string) => request<void>(
  `/api/v1/model-pools/${encodeURIComponent(poolId)}/members/${encodeURIComponent(memberId)}`,
  { method: 'DELETE' },
)

export const setPoolStatus = (request: AuthenticatedRequest, poolId: string, active: boolean) => request<ModelPool>(
  `/api/v1/model-pools/${encodeURIComponent(poolId)}/${active ? 'activate' : 'disable'}`,
  { method: 'POST' },
)

export const loadInferenceBudget = async (request: AuthenticatedRequest) => {
  const [policy, usage] = await Promise.all([
    request<InferenceBudgetPolicy>('/api/v1/inference-budget/policy'),
    request<InferenceBudgetUsage>('/api/v1/inference-budget/usage'),
  ])
  return { policy, usage }
}

export const configureInferenceBudget = (
  request: AuthenticatedRequest,
  input: Pick<InferenceBudgetPolicy, 'enabled' | 'monthlyRequestLimit' | 'monthlyTokenLimit' | 'monthlyCostLimitMicros'>,
) => request<InferenceBudgetPolicy>('/api/v1/inference-budget/policy', {
  method: 'PUT', body: JSON.stringify(input),
})

export const listModelPrices = (
  request: AuthenticatedRequest,
  providerModelId: string,
) => request<ModelPrice[]>(`/api/v1/provider-models/${encodeURIComponent(providerModelId)}/prices`)

export const createModelPrice = (
  request: AuthenticatedRequest,
  providerModelId: string,
  input: {
    inputMicrosPerMillionTokens: number
    outputMicrosPerMillionTokens: number
    effectiveFrom: string | null
    effectiveUntil: string | null
  },
) => request<ModelPrice>(`/api/v1/provider-models/${encodeURIComponent(providerModelId)}/prices`, {
  method: 'POST', body: JSON.stringify(input),
})
