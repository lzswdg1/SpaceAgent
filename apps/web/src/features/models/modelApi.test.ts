import { describe, expect, it, vi } from 'vitest'
import type { AuthenticatedRequest } from '../overview/types'
import { addPoolMember, configureInferenceBudget, createModelPrice, deleteProvider, deleteProviderModel, listModelPrices, listProviderModels, loadInferenceBudget, loadModelResources, removePoolMember, setPoolStatus, testProvider, testProviderModel, updateProvider } from './modelApi'

describe('model resource API', () => {
  it('loads providers and routing pools from public contracts', async () => {
    const request = vi.fn(async () => []) as unknown as AuthenticatedRequest
    await loadModelResources(request)
    expect(request).toHaveBeenCalledWith('/api/v1/model-providers')
    expect(request).toHaveBeenCalledWith('/api/v1/model-pools')
  })

  it('encodes resource identifiers and sends slash-bearing external model IDs as JSON', async () => {
    const request = vi.fn(async () => ({})) as unknown as AuthenticatedRequest
    await listProviderModels(request, 'provider/1')
    await testProvider(request, 'provider/1')
    await addPoolMember(request, 'pool/1', { providerId: 'p', providerModelId: 'm', priority: 1, weight: 100 })
    await setPoolStatus(request, 'pool/1', true)
    await updateProvider(request, 'provider/1', { name: 'Provider', type: 'openai-compatible', baseUrl: 'https://example.test', apiKey: '', authType: 'bearer', enabled: true, isDefault: false })
    await deleteProviderModel(request, 'provider/1', 'model/1')
    await removePoolMember(request, 'pool/1', 'member/1')
    await deleteProvider(request, 'provider/1')
    await testProviderModel(request, 'provider/1', 'model/1')
    expect(request).toHaveBeenNthCalledWith(1, '/api/v1/model-providers/provider%2F1/models')
    expect(request).toHaveBeenNthCalledWith(2, '/api/v1/model-providers/provider%2F1/test', { method: 'POST' })
    expect(request).toHaveBeenNthCalledWith(3, '/api/v1/model-pools/pool%2F1/members', expect.objectContaining({ method: 'POST' }))
    expect(request).toHaveBeenNthCalledWith(4, '/api/v1/model-pools/pool%2F1/activate', { method: 'POST' })
    expect(request).toHaveBeenNthCalledWith(6, '/api/v1/model-providers/provider%2F1/models/model%2F1', { method: 'DELETE' })
    expect(request).toHaveBeenNthCalledWith(7, '/api/v1/model-pools/pool%2F1/members/member%2F1', { method: 'DELETE' })
    expect(request).toHaveBeenNthCalledWith(8, '/api/v1/model-providers/provider%2F1', { method: 'DELETE' })
    expect(request).toHaveBeenNthCalledWith(9, '/api/v1/model-providers/provider%2F1/models/test', {
      method: 'POST', body: JSON.stringify({ modelId: 'model/1' }),
    })
  })

  it('loads and configures Organization inference budget evidence', async () => {
    const request = vi.fn(async (path: string) => path.endsWith('/policy') ? {} : {}) as unknown as AuthenticatedRequest
    await loadInferenceBudget(request)
    const input = { enabled: true, monthlyRequestLimit: 1000,
      monthlyTokenLimit: 2_000_000, monthlyCostLimitMicros: 5_000_000 }
    await configureInferenceBudget(request, input)
    expect(request).toHaveBeenNthCalledWith(1, '/api/v1/inference-budget/policy')
    expect(request).toHaveBeenNthCalledWith(2, '/api/v1/inference-budget/usage')
    expect(request).toHaveBeenNthCalledWith(3, '/api/v1/inference-budget/policy', {
      method: 'PUT', body: JSON.stringify(input),
    })
  })

  it('lists and creates immutable effective Model price versions', async () => {
    const request = vi.fn(async () => ([])) as unknown as AuthenticatedRequest
    const input = { inputMicrosPerMillionTokens: 1_000_000,
      outputMicrosPerMillionTokens: 2_000_000,
      effectiveFrom: '2026-09-11T00:00:00.000Z', effectiveUntil: null }
    await listModelPrices(request, 'provider-model/1')
    await createModelPrice(request, 'provider-model/1', input)
    expect(request).toHaveBeenNthCalledWith(1, '/api/v1/provider-models/provider-model%2F1/prices')
    expect(request).toHaveBeenNthCalledWith(2, '/api/v1/provider-models/provider-model%2F1/prices', {
      method: 'POST', body: JSON.stringify(input),
    })
  })
})
