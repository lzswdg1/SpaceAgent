import { useCallback, useEffect, useMemo, useRef, useState, type FormEvent } from 'react'
import { RequestScope } from '../../lib/RequestScope'
import type { AuthenticatedRequest } from '../overview/types'
import {
  addPoolMember,
  addProviderModel,
  configureInferenceBudget,
  createModelPrice,
  createModelPool,
  createProvider,
  deleteProvider,
  deleteProviderModel,
  listPoolMembers,
  listModelPrices,
  listProviderModels,
  loadInferenceBudget,
  loadModelResources,
  removePoolMember,
  setPoolStatus,
  testProvider,
  testProviderModel,
  updateProvider,
} from './modelApi'
import type { InferenceBudgetPolicy, InferenceBudgetUsage, ModelPool, ModelPoolMember, ModelPrice, ModelProvider, ProviderModel, ProviderModelTest, ProviderTest } from './types'
import { DEFAULT_MODEL_CONTEXT_TOKENS, modelContextTokens } from './modelDefaults'
import './models.css'

type Props = { request: AuthenticatedRequest; tenantRole: string; tx: (key: string) => string }
type Dialog = 'create-provider' | 'edit-provider' | 'create-pool' | 'provider' | 'pool' | 'budget' | 'pricing' | null
type ConfirmTarget = { kind: 'provider' | 'model' | 'member'; id: string; name: string }
const usd = (micros: number) => (micros / 1_000_000).toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 4 })

export function ModelResourcesPage({ request, tenantRole, tx }: Props) {
  const providerScope = useRef(new RequestScope())
  useEffect(() => () => providerScope.current.advance(), [])
  const [providers, setProviders] = useState<ModelProvider[]>([])
  const [pools, setPools] = useState<ModelPool[]>([])
  const [selectedProviderId, setSelectedProviderId] = useState<string | null>(null)
  const [selectedPoolId, setSelectedPoolId] = useState<string | null>(null)
  const [modelsByProvider, setModelsByProvider] = useState<Record<string, ProviderModel[]>>({})
  const [members, setMembers] = useState<ModelPoolMember[]>([])
  const [budgetPolicy, setBudgetPolicy] = useState<InferenceBudgetPolicy | null>(null)
  const [budgetUsage, setBudgetUsage] = useState<InferenceBudgetUsage | null>(null)
  const [pricingModel, setPricingModel] = useState<ProviderModel | null>(null)
  const [prices, setPrices] = useState<ModelPrice[]>([])
  const [testResult, setTestResult] = useState<ProviderTest | null>(null)
  const [testingProvider, setTestingProvider] = useState(false)
  const [dialogError, setDialogError] = useState<string | null>(null)
  const [prefillModelId, setPrefillModelId] = useState('')
  const [modelNotice, setModelNotice] = useState<string | null>(null)
  const [modelTests, setModelTests] = useState<Record<string, ProviderModelTest>>({})
  const [testingModelId, setTestingModelId] = useState<string | null>(null)
  const [dialog, setDialog] = useState<Dialog>(null)
  const [confirmTarget, setConfirmTarget] = useState<ConfirmTarget | null>(null)
  const [busy, setBusy] = useState(false)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const selectedProvider = providers.find(({ id }) => id === selectedProviderId) ?? null
  const selectedPool = pools.find(({ id }) => id === selectedPoolId) ?? null
  const selectedModels = selectedProvider ? modelsByProvider[selectedProvider.id] ?? [] : []
  const allModels = useMemo(() => providers.flatMap((provider) => (modelsByProvider[provider.id] ?? []).map((model) => ({ provider, model }))), [modelsByProvider, providers])
  const canManageBudget = tenantRole === 'OWNER' || tenantRole === 'ADMIN'

  const load = useCallback(async () => {
    setLoading(true); setError(null)
    try {
      const [resources, budget] = await Promise.all([
        loadModelResources(request),
        loadInferenceBudget(request),
      ])
      setProviders(resources.providers); setPools(resources.pools)
      setBudgetPolicy(budget.policy); setBudgetUsage(budget.usage)
    } catch (reason) { setError(reason instanceof Error ? reason.message : tx('modelResourcesLoadFailed')) }
    finally { setLoading(false) }
  }, [request, tx])
  useEffect(() => { void load() }, [load])

  const ensureProviderModels = useCallback(async (providerId: string) => {
    if (modelsByProvider[providerId]) return modelsByProvider[providerId]
    const rows = await listProviderModels(request, providerId)
    setModelsByProvider((current) => ({ ...current, [providerId]: rows }))
    return rows
  }, [modelsByProvider, request])

  const openProvider = async (provider: ModelProvider) => {
    providerScope.current.advance()
    setTestingProvider(false)
    setSelectedProviderId(provider.id); setTestResult(null); setModelTests({}); setTestingModelId(null); setDialogError(null); setModelNotice(null); setPrefillModelId(''); setDialog('provider'); setError(null)
    try { await ensureProviderModels(provider.id) }
    catch (reason) { setError(reason instanceof Error ? reason.message : tx('providerModelsLoadFailed')) }
  }

  const openPool = async (pool: ModelPool) => {
    setSelectedPoolId(pool.id); setDialog('pool'); setError(null)
    try {
      const [nextMembers] = await Promise.all([
        listPoolMembers(request, pool.id),
        ...providers.map(({ id }) => ensureProviderModels(id)),
      ])
      setMembers(nextMembers)
    } catch (reason) { setError(reason instanceof Error ? reason.message : tx('poolMembersLoadFailed')) }
  }

  const submitProvider = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault(); const data = new FormData(event.currentTarget); setBusy(true); setError(null)
    try {
      const provider = await createProvider(request, {
        name: String(data.get('name') ?? '').trim(), type: String(data.get('type') ?? 'openai-compatible'),
        baseUrl: String(data.get('baseUrl') ?? '').trim(), apiKey: String(data.get('apiKey') ?? ''),
        authType: String(data.get('authType') ?? 'bearer'), enabled: true, isDefault: data.get('isDefault') === 'on',
        models: [{ modelId: String(data.get('modelId') ?? '').trim(), displayName: String(data.get('displayName') ?? '').trim(), maxContextTokens: modelContextTokens(data.get('maxContextTokens')), isDefault: true }],
      })
      setProviders((rows) => [provider, ...rows]); setDialog(null)
    } catch (reason) { setError(reason instanceof Error ? reason.message : tx('providerCreateFailed')) }
    finally { setBusy(false) }
  }

  const submitPool = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault(); const data = new FormData(event.currentTarget); setBusy(true); setError(null)
    try {
      const pool = await createModelPool(request, {
        name: String(data.get('name') ?? '').trim(), visibility: String(data.get('visibility')) as ModelPool['visibility'],
        routingStrategy: String(data.get('routingStrategy')) as ModelPool['routingStrategy'], fallbackEnabled: data.get('fallbackEnabled') === 'on',
      })
      setPools((rows) => [pool, ...rows]); setDialog(null)
    } catch (reason) { setError(reason instanceof Error ? reason.message : tx('poolCreateFailed')) }
    finally { setBusy(false) }
  }

  const submitProviderUpdate = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault(); if (!selectedProvider) return; const data = new FormData(event.currentTarget); setBusy(true); setDialogError(null)
    try {
      const updated = await updateProvider(request, selectedProvider.id, {
        name: String(data.get('name') ?? '').trim(), type: String(data.get('type') ?? '').trim(),
        baseUrl: String(data.get('baseUrl') ?? '').trim(), apiKey: String(data.get('apiKey') ?? ''),
        authType: String(data.get('authType') ?? 'bearer'), enabled: data.get('enabled') === 'on', isDefault: data.get('isDefault') === 'on',
      })
      setProviders((rows) => rows.map((provider) => provider.id === updated.id ? updated : provider)); setTestResult(null); setDialog('provider')
    } catch (reason) { setDialogError(reason instanceof Error ? reason.message : tx('providerUpdateFailed')) }
    finally { setBusy(false) }
  }

  const openConfirm = (target: ConfirmTarget) => { setDialogError(null); setConfirmTarget(target) }
  const confirmRemoval = async () => {
    if (!confirmTarget || busy) return
    setBusy(true); setDialogError(null)
    try {
      if (confirmTarget.kind === 'provider') {
        await deleteProvider(request, confirmTarget.id)
        setProviders((rows) => rows.filter(({ id }) => id !== confirmTarget.id)); setModelsByProvider((current) => { const next = { ...current }; delete next[confirmTarget.id]; return next }); setSelectedProviderId(null); setDialog(null)
      } else if (confirmTarget.kind === 'model' && selectedProvider) {
        await deleteProviderModel(request, selectedProvider.id, confirmTarget.id)
        setModelsByProvider((current) => ({ ...current, [selectedProvider.id]: (current[selectedProvider.id] ?? []).filter(({ modelId }) => modelId !== confirmTarget.id) }))
      } else if (confirmTarget.kind === 'member' && selectedPool) {
        await removePoolMember(request, selectedPool.id, confirmTarget.id)
        setMembers((rows) => rows.filter(({ id }) => id !== confirmTarget.id))
      }
      setConfirmTarget(null)
    } catch (reason) { setDialogError(reason instanceof Error ? reason.message : tx('resourceRemoveFailed')) }
    finally { setBusy(false) }
  }

  const runProviderTest = async () => {
    if (!selectedProvider || testingProvider) return
    const isCurrent = providerScope.current.capture()
    setTestingProvider(true); setDialogError(null); setError(null)
    try {
      const result = await testProvider(request, selectedProvider.id)
      if (!isCurrent()) return
      setTestResult(result)
      if (result.success && result.discoveredModelIds.length) setPrefillModelId(result.discoveredModelIds[0])
      setProviders((rows) => rows.map((provider) => provider.id === result.providerId ? { ...provider, connectionStatus: result.status, lastTestLatencyMs: result.latencyMs, lastTestedAt: result.testedAt, lastTestErrorCode: result.errorCode } : provider))
    } catch (reason) { if (isCurrent()) setDialogError(reason instanceof Error ? reason.message : tx('providerTestFailed')) }
    finally { if (isCurrent()) setTestingProvider(false) }
  }

  const submitModel = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault(); if (!selectedProvider) return; const form = event.currentTarget; const data = new FormData(form); setBusy(true); setError(null); setDialogError(null); setModelNotice(null)
    try {
      const model = await addProviderModel(request, selectedProvider.id, { modelId: String(data.get('modelId') ?? '').trim(), displayName: String(data.get('displayName') ?? '').trim(), maxContextTokens: modelContextTokens(data.get('maxContextTokens')), isDefault: data.get('isDefault') === 'on' })
      setModelsByProvider((current) => ({ ...current, [selectedProvider.id]: [...(current[selectedProvider.id] ?? []), model] })); form.reset(); setPrefillModelId(''); setModelNotice(tx('modelAddedSuccessfully'))
    } catch (reason) { setDialogError(reason instanceof Error ? reason.message : tx('modelAddFailed')) }
    finally { setBusy(false) }
  }

  const runModelTest = async (modelId: string) => {
    if (!selectedProvider || testingModelId) return
    const isCurrent = providerScope.current.capture()
    const key = `${selectedProvider.id}:${modelId}`
    setTestingModelId(modelId); setDialogError(null)
    try {
      const result = await testProviderModel(request, selectedProvider.id, modelId)
      if (isCurrent()) setModelTests((current) => ({ ...current, [key]: result }))
    } catch (reason) { if (isCurrent()) setDialogError(reason instanceof Error ? reason.message : tx('modelTestFailed')) }
    finally { if (isCurrent()) setTestingModelId(null) }
  }

  const submitMember = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault(); if (!selectedPool) return; const data = new FormData(event.currentTarget); const model = allModels.find(({ model }) => model.id === data.get('providerModelId')); if (!model) return
    setBusy(true); setError(null); setDialogError(null)
    try {
      const member = await addPoolMember(request, selectedPool.id, { providerId: model.provider.id, providerModelId: model.model.id, priority: Number(data.get('priority') || 0), weight: Number(data.get('weight') || 100) })
      setMembers((rows) => [...rows, member])
    } catch (reason) { setDialogError(reason instanceof Error ? reason.message : tx('poolMemberAddFailed')) }
    finally { setBusy(false) }
  }

  const togglePool = async () => {
    if (!selectedPool || busy) return
    setBusy(true); setError(null)
    try {
      const updated = await setPoolStatus(request, selectedPool.id, selectedPool.status !== 'ACTIVE')
      setPools((rows) => rows.map((pool) => pool.id === updated.id ? updated : pool))
    } catch (reason) { setError(reason instanceof Error ? reason.message : tx('poolStatusFailed')) }
    finally { setBusy(false) }
  }

  const openPricing = async (model: ProviderModel) => {
    setPricingModel(model); setPrices([]); setDialogError(null); setDialog('pricing')
    try { setPrices(await listModelPrices(request, model.id)) }
    catch (reason) { setDialogError(reason instanceof Error ? reason.message : tx('modelPricesLoadFailed')) }
  }

  const submitBudget = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    if (!canManageBudget || busy) return
    const data = new FormData(event.currentTarget)
    setBusy(true); setDialogError(null)
    try {
      const policy = await configureInferenceBudget(request, {
        enabled: data.get('enabled') === 'on',
        monthlyRequestLimit: Number(data.get('monthlyRequestLimit')),
        monthlyTokenLimit: Number(data.get('monthlyTokenLimit')),
        monthlyCostLimitMicros: Number(data.get('monthlyCostLimitMicros')),
      })
      const budget = await loadInferenceBudget(request)
      setBudgetPolicy(policy); setBudgetUsage(budget.usage); setDialog(null)
    } catch (reason) { setDialogError(reason instanceof Error ? reason.message : tx('inferenceBudgetSaveFailed')) }
    finally { setBusy(false) }
  }

  const submitPrice = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    if (!pricingModel || busy) return
    const form = event.currentTarget
    const data = new FormData(form)
    const fromValue = String(data.get('effectiveFrom') ?? '')
    const untilValue = String(data.get('effectiveUntil') ?? '')
    const effectiveFrom = fromValue ? new Date(fromValue).toISOString() : null
    const effectiveUntil = untilValue ? new Date(untilValue).toISOString() : null
    if (effectiveFrom && effectiveUntil && effectiveUntil <= effectiveFrom) {
      setDialogError(tx('modelPriceWindowInvalid')); return
    }
    setBusy(true); setDialogError(null)
    try {
      await createModelPrice(request, pricingModel.id, {
        inputMicrosPerMillionTokens: Number(data.get('inputMicrosPerMillionTokens')),
        outputMicrosPerMillionTokens: Number(data.get('outputMicrosPerMillionTokens')),
        effectiveFrom, effectiveUntil,
      })
      form.reset()
      setPrices(await listModelPrices(request, pricingModel.id))
    } catch (reason) { setDialogError(reason instanceof Error ? reason.message : tx('modelPriceCreateFailed')) }
    finally { setBusy(false) }
  }

  return <section className="model-registry">
    <header className="model-heading"><div><span>MODEL / RESOURCES</span><h1>{tx('modelResourceCenter')}</h1><p>{tx('modelResourceHint')}</p></div><div><button onClick={() => setDialog('create-pool')}>{tx('createPool')}</button><button className="primary" onClick={() => setDialog('create-provider')}>{tx('addProvider')}</button></div></header>
    {error && <div className="model-error"><span>{error}</span><button onClick={() => setError(null)}>×</button></div>}
    {modelNotice && <div className="model-success" role="status"><span>{modelNotice}</span><button onClick={() => setModelNotice(null)}>×</button></div>}
    <section className="model-section inference-budget"><header><b>{tx('inferenceBudget')}</b><span>{budgetPolicy?.enabled ? 'ENFORCED' : 'DISABLED'}</span></header><div className="budget-grid"><article><span>{tx('requests')}</span><b>{(budgetUsage?.consumedRequests ?? 0).toLocaleString()} + {(budgetUsage?.reservedRequests ?? 0).toLocaleString()}</b><small>/ {(budgetPolicy?.monthlyRequestLimit ?? 0).toLocaleString()}</small></article><article><span>TOKENS</span><b>{(budgetUsage?.consumedTokens ?? 0).toLocaleString()} + {(budgetUsage?.reservedTokens ?? 0).toLocaleString()}</b><small>/ {(budgetPolicy?.monthlyTokenLimit ?? 0).toLocaleString()}</small></article><article><span>{tx('cost')}</span><b>${usd((budgetUsage?.consumedCostMicros ?? 0) + (budgetUsage?.reservedCostMicros ?? 0))}</b><small>/ ${usd(budgetPolicy?.monthlyCostLimitMicros ?? 0)}</small></article><article><span>REVISION</span><b>{budgetPolicy?.revision ?? '—'}</b><small>{budgetPolicy?.updatedAt ? new Date(budgetPolicy.updatedAt).toLocaleString() : '—'}</small></article>{canManageBudget && <button disabled={!budgetPolicy || busy} onClick={() => { setDialogError(null); setDialog('budget') }}>{tx('configureBudget')}</button>}</div></section>
    <section className="model-section"><header><b>{tx('modelProviders')}</b><span>{providers.length.toString().padStart(2, '0')}</span></header><div className="provider-grid">{providers.map((provider) => <article key={provider.id}><header><span>{provider.name.slice(0,2).toUpperCase()}</span><em>{provider.connectionStatus}</em></header><h2>{provider.name}</h2><p>{provider.baseUrl}</p><dl><div><dt>{tx('providerType')}</dt><dd>{provider.type}</dd></div><div><dt>{tx('lastLatency')}</dt><dd>{provider.lastTestLatencyMs == null ? '—' : `${provider.lastTestLatencyMs} ms`}</dd></div><div><dt>AUTH</dt><dd>{provider.apiKey ? 'CONFIGURED' : 'NONE'}</dd></div></dl><footer><span>{provider.isDefault ? tx('defaultProvider') : tx('available')}</span><button onClick={() => void openProvider(provider)}>{tx('open')}</button></footer></article>)}{!loading && !providers.length && <div className="model-empty">{tx('noProviders')}</div>}</div></section>
    <section className="model-section pool-section"><header><b>{tx('routingPools')}</b><span>{pools.length.toString().padStart(2, '0')}</span></header><div className="pool-grid">{pools.map((pool) => <button type="button" key={pool.id} onClick={() => void openPool(pool)}><span><b>{pool.name}</b><small>{pool.visibility}</small></span><span><em>{pool.routingStrategy}</em><strong>{pool.status}</strong></span></button>)}{!loading && !pools.length && <div className="model-empty">{tx('noModelPools')}</div>}</div></section>

    {dialog === 'create-provider' && <div className="model-modal" role="dialog" aria-modal="true"><form className="model-dialog" onSubmit={submitProvider}><DialogHeader kicker="PROVIDER / NEW" title={tx('addProvider')} close={() => setDialog(null)} /><div className="model-form-grid"><label><span>{tx('providerName')}</span><input name="name" required /></label><label><span>{tx('providerType')}</span><input name="type" defaultValue="openai-compatible" required /></label><label className="wide"><span>{tx('baseUrl')}</span><input name="baseUrl" type="url" placeholder="https://api.example.com/v1" required /></label><label className="wide"><span>API KEY</span><input name="apiKey" type="password" autoComplete="off" required /></label><label><span>{tx('authType')}</span><select name="authType"><option value="bearer">Bearer</option><option value="api-key">API Key</option></select></label><label className="check"><input type="checkbox" name="isDefault" /><span>{tx('setDefault')}</span></label></div><h3>{tx('initialModel')}</h3><ModelFields /><button className="submit" disabled={busy}>{busy ? tx('saving') : tx('createProvider')}</button></form></div>}
    {dialog === 'edit-provider' && selectedProvider && <div className="model-modal" role="dialog" aria-modal="true"><form className="model-dialog" onSubmit={submitProviderUpdate}><DialogHeader kicker={`PROVIDER / ${selectedProvider.id.slice(0,8)}`} title={tx('editProvider')} close={() => setDialog('provider')} />{dialogError && <div className="model-dialog-error" role="alert">{dialogError}</div>}<div className="model-form-grid"><label><span>{tx('providerName')}</span><input name="name" defaultValue={selectedProvider.name} required /></label><label><span>{tx('providerType')}</span><input name="type" defaultValue={selectedProvider.type} required /></label><label className="wide"><span>{tx('baseUrl')}</span><input name="baseUrl" type="url" defaultValue={selectedProvider.baseUrl} required /></label><label className="wide"><span>{tx('newApiKeyOptional')}</span><input name="apiKey" type="password" autoComplete="off" placeholder={tx('keepCurrentSecret')} /></label><label><span>{tx('authType')}</span><select name="authType" defaultValue={selectedProvider.authType}><option value="bearer">Bearer</option><option value="api-key">API Key</option></select></label><label className="check"><input type="checkbox" name="enabled" defaultChecked={selectedProvider.enabled} /><span>{tx('enabled')}</span></label><label className="check"><input type="checkbox" name="isDefault" defaultChecked={selectedProvider.isDefault} /><span>{tx('setDefault')}</span></label></div><button className="submit" disabled={busy}>{busy ? tx('saving') : tx('saveChanges')}</button></form></div>}
    {dialog === 'create-pool' && <div className="model-modal" role="dialog" aria-modal="true"><form className="model-dialog compact" onSubmit={submitPool}><DialogHeader kicker="POOL / NEW" title={tx('createPool')} close={() => setDialog(null)} /><label><span>{tx('poolName')}</span><input name="name" required /></label><label><span>{tx('visibility')}</span><select name="visibility"><option value="PRIVATE">PRIVATE</option><option value="ORGANIZATION">ORGANIZATION</option></select></label><label><span>{tx('routingStrategy')}</span><select name="routingStrategy"><option value="PRIORITY">PRIORITY</option><option value="WEIGHTED">WEIGHTED</option><option value="COST">COST</option><option value="LATENCY">LATENCY</option></select></label><label className="check"><input type="checkbox" name="fallbackEnabled" /><span>{tx('fallbackRouting')}</span></label><button className="submit" disabled={busy}>{busy ? tx('saving') : tx('createPool')}</button></form></div>}
    {dialog === 'provider' && selectedProvider && (
      <div className="model-modal" role="dialog" aria-modal="true">
        <div className="model-dialog detail">
          <DialogHeader kicker={`PROVIDER / ${selectedProvider.id.slice(0, 8)}`} title={selectedProvider.name} close={() => setDialog(null)} />
          <div className="resource-action-bar"><button onClick={() => { setDialogError(null); setDialog('edit-provider') }}>{tx('edit')}</button><button className="danger" onClick={() => openConfirm({ kind: 'provider', id: selectedProvider.id, name: selectedProvider.name })}>{tx('deleteProvider')}</button></div>
          <div className="provider-setup-guide"><b>{tx('howToAddModel')}</b><ol><li>{tx('modelGuideOne')}</li><li>{tx('modelGuideTwo')}</li><li>{tx('modelGuideThree')}</li></ol></div>
          {dialogError && <div className="model-dialog-error" role="alert">{dialogError}</div>}
          <div className={`connection-evidence ${testingProvider ? 'testing' : testResult?.success ? 'success' : testResult ? 'failed' : ''}`} aria-live="polite">
            <span><b>{testingProvider ? tx('testingConnection') : testResult?.success ? tx('connectionSucceeded') : testResult ? tx('connectionFailed') : selectedProvider.connectionStatus}</b><small>{testingProvider ? tx('waitingForProvider') : testResult?.errorCode || selectedProvider.lastTestErrorCode || (selectedProvider.connectionStatus === 'ACTIVE' ? tx('providerReady') : tx('noConnectionEvidence'))}</small></span>
            <dl><div><dt>{tx('lastLatency')}</dt><dd>{testResult ? `${testResult.latencyMs} ms` : selectedProvider.lastTestLatencyMs == null ? '—' : `${selectedProvider.lastTestLatencyMs} ms`}</dd></div><div><dt>{tx('discoveredModels')}</dt><dd>{testResult?.discoveredModelIds.length ?? '—'}</dd></div><div><dt>{tx('testedAt')}</dt><dd>{testResult ? new Date(testResult.testedAt).toLocaleTimeString() : selectedProvider.lastTestedAt ? new Date(selectedProvider.lastTestedAt).toLocaleTimeString() : '—'}</dd></div></dl>
            <button disabled={testingProvider} onClick={() => void runProviderTest()}>{testingProvider ? tx('testing') : tx('testConnection')}</button>
          </div>
          {testResult && !testResult.success && <p className="provider-test-hint">{testResult.errorCode === 'PROVIDER_AUTH_FAILED' ? tx('providerAuthFailedHint') : tx('providerConnectionFailedHint')}</p>}
          {testResult?.success && testResult.discoveredModelIds.length > 0 && <div className="discovered-model-list"><span>{tx('chooseDiscoveredModel')}</span><div>{testResult.discoveredModelIds.slice(0, 8).map((modelId) => <button key={modelId} onClick={() => setPrefillModelId(modelId)}>{modelId}</button>)}</div></div>}
          <section>
            <h3>{tx('providerModels')}</h3>
            <div className="provider-models">{selectedModels.map((model) => {
              const result = modelTests[`${selectedProvider.id}:${model.modelId}`]
              return <article className="provider-model-entry" key={model.id}>
                <div className="provider-model-row"><span><b>{model.displayName || model.modelId}</b><small>{model.modelId}</small></span><span className="resource-row-actions"><em>{model.maxContextTokens.toLocaleString()} CTX</em><button onClick={() => void openPricing(model)}>{tx('pricing')}</button><button disabled={testingModelId !== null} onClick={() => void runModelTest(model.modelId)}>{testingModelId === model.modelId ? tx('testing') : tx('testModel')}</button><button onClick={() => openConfirm({ kind: 'model', id: model.modelId, name: model.displayName || model.modelId })}>{tx('remove')}</button></span></div>
                {result && <div className={`model-test-result ${result.success ? 'success' : 'failed'}`}><span><b>{result.success ? tx('modelAvailable') : tx('modelUnavailable')}</b><small>{result.success ? result.responsePreview : result.errorCode}</small></span><dl><div><dt>{tx('lastLatency')}</dt><dd>{result.latencyMs} ms</dd></div><div><dt>TOKENS</dt><dd>{result.inputTokens} / {result.outputTokens}</dd></div><div><dt>{tx('testedAt')}</dt><dd>{new Date(result.testedAt).toLocaleTimeString()}</dd></div></dl></div>}
              </article>
            })}</div>
          </section>
          <form className="model-add-form" onSubmit={submitModel}><h3>{tx('addModel')}</h3><p>{tx('modelFieldsHint')}</p><ModelFields key={prefillModelId} defaultModelId={prefillModelId} /><button className="submit" disabled={busy}>{tx('addModel')}</button></form>
        </div>
      </div>
    )}
    {dialog === 'pool' && selectedPool && <div className="model-modal" role="dialog" aria-modal="true"><div className="model-dialog detail"><DialogHeader kicker={`POOL / ${selectedPool.id.slice(0,8)}`} title={selectedPool.name} close={() => setDialog(null)} />{dialogError && <div className="model-dialog-error" role="alert">{dialogError}</div>}<div className="connection-evidence"><span><b>{selectedPool.status}</b><small>{selectedPool.routingStrategy} · {selectedPool.visibility}</small></span><button disabled={busy} onClick={() => void togglePool()}>{selectedPool.status === 'ACTIVE' ? tx('disablePool') : tx('activatePool')}</button></div><section><h3>{tx('poolMembers')}</h3><div className="provider-models">{members.map((member) => <div key={member.id}><span><b>{member.modelId}</b><small>{member.providerId.slice(0,8)}</small></span><span className="resource-row-actions"><em>P{member.priority} · W{member.weight}</em><button onClick={() => openConfirm({ kind: 'member', id: member.id, name: member.modelId })}>{tx('remove')}</button></span></div>)}{!members.length && <p>{tx('noPoolMembers')}</p>}</div></section><form className="pool-member-form" onSubmit={submitMember}><h3>{tx('addPoolMember')}</h3><label><span>{tx('providerModel')}</span><select name="providerModelId" required><option value="">—</option>{allModels.map(({ provider, model }) => <option key={model.id} value={model.id}>{provider.name} / {model.displayName || model.modelId}</option>)}</select></label><div><label><span>{tx('priority')}</span><input name="priority" type="number" min="0" max="10000" defaultValue="0" /></label><label><span>{tx('weight')}</span><input name="weight" type="number" min="1" max="1000" defaultValue="100" /></label></div><button className="submit" disabled={busy || !allModels.length}>{tx('addPoolMember')}</button></form></div></div>}
    {dialog === 'budget' && budgetPolicy && <div className="model-modal" role="dialog" aria-modal="true"><form className="model-dialog compact" onSubmit={submitBudget}><DialogHeader kicker={`BUDGET / REV ${budgetPolicy.revision}`} title={tx('configureBudget')} close={() => setDialog(null)} />{dialogError && <div className="model-dialog-error" role="alert">{dialogError}</div>}<label className="check"><input name="enabled" type="checkbox" defaultChecked={budgetPolicy.enabled} /><span>{tx('enforceBudget')}</span></label><label><span>{tx('monthlyRequestLimit')}</span><input name="monthlyRequestLimit" type="number" min="0" defaultValue={budgetPolicy.monthlyRequestLimit} required /></label><label><span>{tx('monthlyTokenLimit')}</span><input name="monthlyTokenLimit" type="number" min="0" defaultValue={budgetPolicy.monthlyTokenLimit} required /></label><label><span>{tx('monthlyCostLimitMicros')}</span><input name="monthlyCostLimitMicros" type="number" min="0" defaultValue={budgetPolicy.monthlyCostLimitMicros} required /></label><p className="model-boundary-note">{tx('budgetAuthorityHint')}</p><button className="submit" disabled={busy}>{busy ? tx('saving') : tx('saveChanges')}</button></form></div>}
    {dialog === 'pricing' && pricingModel && <div className="model-modal" role="dialog" aria-modal="true"><div className="model-dialog detail pricing-dialog"><DialogHeader kicker={`MODEL / ${pricingModel.modelId}`} title={tx('modelPricing')} close={() => { setPricingModel(null); setDialog('provider') }} />{dialogError && <div className="model-dialog-error" role="alert">{dialogError}</div>}<section><h3>{tx('priceVersions')}</h3><div className="price-list">{prices.map((price) => <article key={price.id}><span><b>V{price.version}</b><small>{new Date(price.effectiveFrom).toLocaleString()} → {price.effectiveUntil ? new Date(price.effectiveUntil).toLocaleString() : '∞'}</small></span><dl><div><dt>INPUT / 1M</dt><dd>${usd(price.inputMicrosPerMillionTokens)}</dd></div><div><dt>OUTPUT / 1M</dt><dd>${usd(price.outputMicrosPerMillionTokens)}</dd></div></dl></article>)}{!prices.length && <p>{tx('noModelPrices')}</p>}</div></section><form className="price-form" onSubmit={submitPrice}><h3>{tx('createPriceVersion')}</h3><p>{tx('immutablePriceHint')}</p><div><label><span>{tx('inputMicrosPerMillion')}</span><input name="inputMicrosPerMillionTokens" type="number" min="0" required /></label><label><span>{tx('outputMicrosPerMillion')}</span><input name="outputMicrosPerMillionTokens" type="number" min="0" required /></label></div><div><label><span>{tx('effectiveFrom')}</span><input name="effectiveFrom" type="datetime-local" /></label><label><span>{tx('effectiveUntil')}</span><input name="effectiveUntil" type="datetime-local" /></label></div><button className="submit" disabled={busy}>{busy ? tx('saving') : tx('createPriceVersion')}</button></form></div></div>}
    {confirmTarget && <div className="model-modal confirm-layer" role="dialog" aria-modal="true"><div className="model-dialog compact"><DialogHeader kicker={tx('systemConfirmation')} title={tx(confirmTarget.kind === 'provider' ? 'confirmDeleteProvider' : confirmTarget.kind === 'model' ? 'confirmDeleteModel' : 'confirmRemovePoolMember')} close={() => setConfirmTarget(null)} />{dialogError && <div className="model-dialog-error" role="alert">{dialogError}</div>}<p className="confirm-resource-name">{confirmTarget.name}</p><div className="confirm-actions"><button onClick={() => setConfirmTarget(null)}>{tx('cancel')}</button><button className="danger" disabled={busy} onClick={() => void confirmRemoval()}>{busy ? tx('processing') : tx('confirmRemove')}</button></div></div></div>}
  </section>
}

function DialogHeader({ kicker, title, close }: { kicker: string; title: string; close: () => void }) {
  return <header className="model-dialog-header"><div><span>{kicker}</span><h2>{title}</h2></div><button type="button" onClick={close}>×</button></header>
}

function ModelFields({ defaultModelId = '' }: { defaultModelId?: string }) {
  return <div className="model-fields"><label><span>MODEL ID</span><input name="modelId" defaultValue={defaultModelId} placeholder="qwen-plus" required /></label><label><span>DISPLAY NAME</span><input name="displayName" defaultValue={defaultModelId} placeholder="Qwen Plus" required /></label><label><span>MAX CONTEXT</span><input name="maxContextTokens" type="number" min="1024" max="2000000" defaultValue={String(DEFAULT_MODEL_CONTEXT_TOKENS)} required /></label><label className="check"><input type="checkbox" name="isDefault" defaultChecked /><span>DEFAULT</span></label></div>
}
