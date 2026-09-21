import { usePagedOptions } from '../../lib/usePagedOptions'
import { useCallback, useEffect, useMemo, useRef, useState, type FormEvent } from 'react'
import { ApiError } from '../../lib/api'
import type { AuthenticatedRequest } from '../overview/types'
import {
  createAgent,
  createAgentApiKey,
  decideAgentConfigurationChange,
  deleteAgent,
  emptyAgentDraft,
  listAgentApiKeys,
  listAgentConfigurationChanges,
  loadAgentResources,
  revokeAgentApiKey,
  toAgentDraft,
  updateAgent,
} from './agentApi'
import type {
  AgentApiKey,
  AgentApiKeyScope,
  AgentConfigurationChange,
  AgentDefinition,
  AgentDraft,
  AgentResources,
  CreatedAgentApiKey,
} from './types'
import { matchModelOptions } from './modelAutocomplete'
import { canToggleCapability, selectedToolRequirements, toolConfigurationIssue } from './toolPresentation'
import './agent.css'
import { ToolPicker } from './ToolPicker'
import { AgentSkills } from './AgentSkills'
import { RequestScope } from '../../lib/RequestScope'
import { selectAgentModelPool } from './agentBinding'

type Props = {
  request: AuthenticatedRequest
  userId: string
  tenantRole: string
  tx: (key: string) => string
}

type PendingDecision = {
  change: AgentConfigurationChange
  decision: 'APPROVE' | 'REJECT'
}

const emptyResources = (): AgentResources => ({
  agents: [],
  access: [],
  modelPools: [],
  knowledgeDocuments: [],
  modelOptions: [],
  capabilities: {
    tools: [],
    skills: [],
    sandbox: {
      mode: 'IN_PROCESS',
      isolation: 'PROCESS_COMPATIBILITY',
      containerized: false,
      codingCommandAvailable: false,
    },
  },
})

const replaceAgent = (resources: AgentResources, updated: AgentDefinition): AgentResources => ({
  ...resources,
  agents: resources.agents.map((agent) => agent.id === updated.id ? updated : agent),
  access: resources.access.map((entry) => entry.agent.id === updated.id
    ? { ...entry, agent: updated }
    : entry),
})

export function AgentPage({ request, userId, tenantRole, tx }: Props) {
  const editorScope = useRef(new RequestScope())
  const changeRequestSequence = useRef(0)
  const [resources, setResources] = useState<AgentResources>(emptyResources)
  const agentPages = usePagedOptions((offset, signal) => request<AgentResources['access']>(`/api/v1/agents/organization?offset=${offset}&limit=100`, { signal }),
    page => setResources(rows => ({ ...rows,
      access: [...rows.access, ...page.filter(item => !rows.agents.some(agent => agent.id === item.agent.id))],
      agents: [...rows.agents, ...page.map(item => item.agent).filter(item => !rows.agents.some(agent => agent.id === item.id))],
    })), reason => setError(reason instanceof Error ? reason.message : tx('agentLoadFailed')))
  const [selectedId, setSelectedId] = useState<string | null>(null)
  const [draft, setDraft] = useState<AgentDraft | null>(null)
  const [loading, setLoading] = useState(true)
  const [saving, setSaving] = useState(false)
  const [creating, setCreating] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [saved, setSaved] = useState(false)
  const [saveNotice, setSaveNotice] = useState<string | null>(null)
  const [deleteConfirm, setDeleteConfirm] = useState(false)
  const [modelSearch, setModelSearch] = useState('')
  const [keyManagerOpen, setKeyManagerOpen] = useState(false)
  const [apiKeys, setApiKeys] = useState<AgentApiKey[]>([])
  const [createdApiKey, setCreatedApiKey] = useState<CreatedAgentApiKey | null>(null)
  const [keyBusy, setKeyBusy] = useState(false)
  const [keyError, setKeyError] = useState<string | null>(null)
  const [revokeKeyId, setRevokeKeyId] = useState<string | null>(null)
  const [changesOpen, setChangesOpen] = useState(false)
  const [changes, setChanges] = useState<AgentConfigurationChange[]>([])
  const [changesLoading, setChangesLoading] = useState(false)
  const [changesError, setChangesError] = useState<string | null>(null)
  const [pendingDecision, setPendingDecision] = useState<PendingDecision | null>(null)

  const selected = useMemo(
    () => resources.agents.find(({ id }) => id === selectedId) ?? null,
    [resources.agents, selectedId],
  )
  const selectedAccess = resources.access.find(({ agent }) => agent.id === selectedId) ?? null
  const ownsSelected = Boolean(selected && selected.ownerId === userId)
  const canEdit = Boolean(selectedAccess && selectedAccess.writeMode !== 'READ_ONLY')
  const canApproveChanges = tenantRole === 'OWNER'
  const canManageKeys = ownsSelected && (tenantRole === 'OWNER' || tenantRole === 'ADMIN')
  const pendingChanges = useMemo(
    () => changes.filter(({ state }) => state === 'PENDING'),
    [changes],
  )
  const selectedPool = resources.modelPools.find(({ id }) => id === draft?.modelPoolId) ?? null
  const modelSuggestions = useMemo(() => {
    const query = modelSearch.trim()
    return query ? matchModelOptions(resources.modelOptions, query) : resources.modelOptions
  }, [modelSearch, resources.modelOptions])
  const toolRequirements = useMemo(() => selectedToolRequirements(
    resources.capabilities.tools,
    draft?.enabledToolIds ?? [],
  ), [draft?.enabledToolIds, resources.capabilities.tools])
  const toolIssue = toolConfigurationIssue(
    toolRequirements,
    Boolean(draft?.networkEnabled),
    Boolean(draft?.ragEnabled && draft.knowledgeBaseIds.length),
  )

  const load = useCallback(async (preferredAgentId?: string) => {
    setLoading(true)
    setError(null)
    try {
      const loaded = await loadAgentResources(request)
      setResources(loaded)
      agentPages.reset(loaded.agents.length)
      setSelectedId((current) => {
        const preferred = preferredAgentId ?? current
        return preferred && loaded.agents.some(({ id }) => id === preferred)
          ? preferred
          : loaded.agents[0]?.id ?? null
      })
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : tx('agentLoadFailed'))
    } finally {
      setLoading(false)
    }
  }, [request, tx])

  const loadChanges = useCallback(async (agentId: string) => {
    const sequence = ++changeRequestSequence.current
    setChangesLoading(true)
    setChangesError(null)
    try {
      const rows = await listAgentConfigurationChanges(request, agentId, 0, 50)
      if (sequence === changeRequestSequence.current) {
        setChanges(rows.filter(({ state }) => state === 'PENDING'))
      }
    } catch (reason) {
      if (sequence === changeRequestSequence.current) {
        setChangesError(reason instanceof Error ? reason.message : tx('agentChangesLoadFailed'))
      }
    } finally {
      if (sequence === changeRequestSequence.current) setChangesLoading(false)
    }
  }, [request, tx])

  useEffect(() => { void load() }, [load])
  useEffect(() => { editorScope.current.advance(); return () => editorScope.current.advance() }, [selectedId])
  useEffect(() => {
    setDraft(selected ? toAgentDraft(selected) : null)
    setModelSearch('')
    setSaved(false)
    setSaveNotice(null)
    setDeleteConfirm(false)
    setKeyManagerOpen(false)
    setApiKeys([])
    setCreatedApiKey(null)
    setKeyError(null)
    setRevokeKeyId(null)
    setPendingDecision(null)
    setChanges([])
    if (selected) void loadChanges(selected.id)
    else changeRequestSequence.current += 1
  }, [loadChanges, selected])

  useEffect(() => {
    if (!keyManagerOpen && !changesOpen && !deleteConfirm) return
    const dismiss = (event: KeyboardEvent) => {
      if (event.key !== 'Escape') return
      if (pendingDecision) setPendingDecision(null)
      else if (deleteConfirm) setDeleteConfirm(false)
      else if (changesOpen) { setChangesOpen(false); setChangesError(null) }
      else if (keyManagerOpen) closeKeyManager()
    }
    window.addEventListener('keydown', dismiss)
    return () => window.removeEventListener('keydown', dismiss)
  })

  const patchDraft = <Key extends keyof AgentDraft>(key: Key, value: AgentDraft[Key]) => {
    editorScope.current.advance()
    setDraft((current) => current ? { ...current, [key]: value } : current)
    setSaved(false)
    setSaveNotice(null)
  }

  const addAgent = async () => {
    if (creating) return
    setCreating(true)
    setError(null)
    try {
      const created = await createAgent(request, emptyAgentDraft(tx('untitledAgent')))
      await load(created.id)
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : tx('agentCreateFailed'))
    } finally {
      setCreating(false)
    }
  }

  const save = async (event: FormEvent) => {
    event.preventDefault()
    if (!selected || !draft || saving || !canEdit) return
    if (toolIssue) {
      setError(tx(toolIssue === 'NETWORK'
        ? 'toolNetworkPolicyWarning'
        : toolIssue === 'KNOWLEDGE'
          ? 'toolKnowledgePolicyWarning'
          : 'unavailableToolWarning'))
      return
    }
    setSaving(true)
    setSaved(false)
    setSaveNotice(null)
    setError(null)
    const isCurrent = editorScope.current.capture()
    try {
      const result = await updateAgent(request, selected.id, draft)
      if (!isCurrent()) return
      setResources((current) => replaceAgent(current, result.agent))
      setDraft(toAgentDraft(result.agent))
      if (result.outcome === 'APPLIED') {
        setSaved(true)
        setSaveNotice(tx('agentChangeApplied'))
      } else {
        setSaveNotice(tx('agentChangePending'))
      }
      await loadChanges(selected.id)
    } catch (reason) {
      if (isCurrent()) setError(reason instanceof Error ? reason.message : tx('agentSaveFailed'))
    } finally {
      setSaving(false)
    }
  }

  const removeAgent = async () => {
    if (!selected || saving || !ownsSelected) return
    setSaving(true)
    setError(null)
    try {
      await deleteAgent(request, selected.id)
      setDeleteConfirm(false)
      await load()
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : tx('agentDeleteFailed'))
    } finally {
      setSaving(false)
    }
  }

  const openKeyManager = async () => {
    if (!selected || keyBusy || !canManageKeys) return
    setKeyManagerOpen(true)
    setCreatedApiKey(null)
    setKeyError(null)
    setKeyBusy(true)
    try { setApiKeys(await listAgentApiKeys(request, selected.id)) }
    catch (reason) { setKeyError(reason instanceof Error ? reason.message : tx('agentKeysLoadFailed')) }
    finally { setKeyBusy(false) }
  }

  const closeKeyManager = () => {
    setKeyManagerOpen(false)
    setCreatedApiKey(null)
    setKeyError(null)
    setRevokeKeyId(null)
  }

  const submitApiKey = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    if (!selected || keyBusy || !canManageKeys) return
    const form = event.currentTarget
    const data = new FormData(form)
    const name = String(data.get('name') || '').trim()
    const scopes = data.getAll('scopes').map(String) as AgentApiKeyScope[]
    const expiresAtValue = String(data.get('expiresAt') || '').trim()
    if (!name) return
    if (!scopes.length) {
      setKeyError(tx('agentKeyScopeRequired'))
      return
    }
    setKeyBusy(true)
    setKeyError(null)
    setCreatedApiKey(null)
    try {
      const created = await createAgentApiKey(request, selected.id, {
        name,
        scopes,
        expiresAt: expiresAtValue ? new Date(expiresAtValue).toISOString() : null,
      })
      setCreatedApiKey(created)
      setApiKeys((current) => [created.apiKey, ...current])
      form.reset()
    } catch (reason) {
      setKeyError(reason instanceof Error ? reason.message : tx('agentKeyCreateFailed'))
    } finally {
      setKeyBusy(false)
    }
  }

  const revokeApiKey = async (keyId: string) => {
    if (!selected || keyBusy || !canManageKeys) return
    setKeyBusy(true)
    setKeyError(null)
    try {
      await revokeAgentApiKey(request, selected.id, keyId)
      setApiKeys(await listAgentApiKeys(request, selected.id))
      setRevokeKeyId(null)
    } catch (reason) {
      setKeyError(reason instanceof Error ? reason.message : tx('agentKeyRevokeFailed'))
    } finally {
      setKeyBusy(false)
    }
  }

  const decideChange = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    if (!selected || !pendingDecision || changesLoading || !canApproveChanges) return
    const data = new FormData(event.currentTarget)
    const note = String(data.get('note') ?? '').trim() || null
    const { change, decision } = pendingDecision
    setChangesLoading(true)
    setChangesError(null)
    try {
      await decideAgentConfigurationChange(request, selected.id, change.id, {
        expectedRevision: change.revision,
        decision,
        note,
      })
      setPendingDecision(null)
      setChangesOpen(false)
      await load(selected.id)
      await loadChanges(selected.id)
    } catch (reason) {
      if (reason instanceof ApiError && reason.status === 409) {
        setPendingDecision(null)
        await loadChanges(selected.id)
        setChangesError(tx('agentChangeConflict'))
      } else {
        setChangesError(reason instanceof Error ? reason.message : tx('agentChangeDecisionFailed'))
      }
    } finally {
      setChangesLoading(false)
    }
  }

  const toggleKnowledge = (documentId: string) => {
    if (!draft) return
    patchDraft('knowledgeBaseIds', draft.knowledgeBaseIds.includes(documentId)
      ? draft.knowledgeBaseIds.filter((id) => id !== documentId)
      : [...draft.knowledgeBaseIds, documentId])
  }

  const toggleCapability = (field: 'enabledToolIds' | 'skillIds', capabilityId: string) => {
    if (!draft) return
    const current = draft[field]
    const capability = field === 'enabledToolIds'
      ? resources.capabilities.tools.find(({ id }) => id === capabilityId)
      : resources.capabilities.skills.find(({ id }) => id === capabilityId)
    if (capability && !canToggleCapability(capability, current.includes(capabilityId))) return
    patchDraft(field, current.includes(capabilityId)
      ? current.filter((id) => id !== capabilityId)
      : [...current, capabilityId])
  }

  const chooseModel = (providerId: string, modelId: string) => {
    editorScope.current.advance()
    setDraft((current) => current
      ? { ...current, modelPoolId: null, modelProviderId: providerId, modelId }
      : current)
    setSaved(false)
    setSaveNotice(null)
    setModelSearch('')
  }

  return (
    <section className="agent-console">
      <aside className="agent-index">
        <header>
          <div><span>AGENT / INDEX</span><b>{tx('agentDefinitions')}</b></div>
          <div className="agent-index-actions"><button type="button" aria-label={tx('createAgent')} disabled={creating} onClick={() => void addAgent()}>＋</button></div>
        </header>
        <div className="agent-index-list">
          {resources.access.map(({ agent, writeMode }) => (
            <button type="button" key={agent.id} className={agent.id === selectedId ? 'active' : ''} onClick={() => setSelectedId(agent.id)}>
              <span>{agent.name.slice(0, 2).toUpperCase()}</span>
              <div><b>{agent.name}</b><small>{agent.description || tx('noDescription')}</small></div>
              <em title={tx(`agentWriteMode${writeMode}`)}>{writeMode === 'DIRECT' ? 'DIRECT' : writeMode === 'OWNER_APPROVAL_REQUIRED' ? 'ASK' : 'VIEW'}</em>
            </button>
          ))}
          {!loading && !resources.agents.length && <p>{tx('noAgents')}</p>}
          {agentPages.hasMore && <button type="button" disabled={loading || agentPages.loading} onClick={()=>void agentPages.loadMore()}>{tx('loadMoreAgents')}</button>}
        </div>
      </aside>

      <main className="agent-definition">
        {error && <div className="agent-error" role="alert"><span>{error}</span><button onClick={() => void load(selected?.id)}>{tx('retry')}</button></div>}
        {saveNotice && <div className="agent-save-notice" role="status">{saveNotice}</div>}
        {!draft || !selected ? (
          <div className="agent-empty"><b>{loading ? tx('loadingAgents') : tx('createFirstAgent')}</b></div>
        ) : (
          <form onSubmit={save}>
            <header className="agent-titlebar">
              <div><span>{tx('currentConfiguration')}</span><input aria-label={tx('agentName')} value={draft.name} maxLength={100} required disabled={!canEdit} onChange={(event) => patchDraft('name', event.target.value)} /></div>
              <div className="agent-save-state">
                <small>{saved ? tx('saved') : tx(`agentWriteMode${selectedAccess?.writeMode ?? 'READ_ONLY'}`)}</small>
                {pendingChanges.length > 0 && <button className="agent-key-button" type="button" disabled={changesLoading} onClick={() => { setChangesOpen(true); void loadChanges(selected.id) }}>{tx(canApproveChanges ? 'agentApprovals' : 'agentApprovalPending')} · {pendingChanges.length}</button>}
                {canManageKeys && <button className="agent-key-button" type="button" disabled={keyBusy} onClick={() => void openKeyManager()}>{tx('manageAgentKeys')}</button>}
                {ownsSelected && <button className="agent-delete-button" type="button" disabled={saving} onClick={() => setDeleteConfirm(true)}>{tx('delete')}</button>}
                {canEdit && <button disabled={saving || !draft.name.trim()}>{saving ? tx('saving') : selectedAccess?.writeMode === 'OWNER_APPROVAL_REQUIRED' ? tx('submitAgentChange') : tx('saveDefinition')}</button>}
              </div>
            </header>

            <fieldset className="agent-definition-fields" disabled={!canEdit}>
              <div className="agent-definition-grid">
                <section className="agent-card agent-identity-card">
                  <header><span>01</span><b>{tx('identityAndPrompt')}</b></header>
                  <label><span>{tx('description')}</span><input value={draft.description ?? ''} maxLength={500} onChange={(event) => patchDraft('description', event.target.value)} /></label>
                  <label><span>{tx('systemInstruction')}</span><textarea value={draft.systemPrompt ?? ''} maxLength={50000} onChange={(event) => patchDraft('systemPrompt', event.target.value)} /></label>
                </section>

                <section className="agent-card">
                  <header><span>02</span><b>{tx('modelPool')}</b></header>
                  <label><span>{tx('routingPool')}</span><select value={draft.modelPoolId ?? ''} onChange={(event) => { const poolId=event.target.value || null; editorScope.current.advance(); setDraft(current=>current?selectAgentModelPool(current,poolId):null); setSaved(false); setSaveNotice(null) }}><option value="">{tx('directBinding')}</option>{resources.modelPools.map((pool) => <option key={pool.id} value={pool.id}>{pool.name}</option>)}</select></label>
                  {selectedPool ? <dl className="agent-pool-facts"><div><dt>{tx('strategy')}</dt><dd>{selectedPool.routingStrategy}</dd></div><div><dt>{tx('status')}</dt><dd>{selectedPool.status}</dd></div></dl> : <div className="agent-direct-model"><label><span>{tx('searchAvailableModels')}</span><input type="search" value={modelSearch} placeholder={tx('searchAvailableModels')} onChange={event => setModelSearch(event.target.value)} /></label><label><span>{tx('providerModel')}</span><select value={draft.modelProviderId && draft.modelId ? `${draft.modelProviderId}:${draft.modelId}` : ''} onChange={event => { const option = resources.modelOptions.find(option => `${option.providerId}:${option.modelId}` === event.target.value); if(option) chooseModel(option.providerId, option.modelId) }}><option value="" disabled>{tx('providerModel')}</option>{draft.modelProviderId && draft.modelId && !modelSuggestions.some(option => option.providerId === draft.modelProviderId && option.modelId === draft.modelId) && <option value={`${draft.modelProviderId}:${draft.modelId}`} disabled>{resources.modelOptions.find(option => option.providerId === draft.modelProviderId && option.modelId === draft.modelId)?.displayName || draft.modelId}</option>}{modelSuggestions.map(option => <option key={`${option.providerId}:${option.modelId}`} value={`${option.providerId}:${option.modelId}`}>{option.providerName} / {option.displayName}</option>)}</select></label></div>}
                  {!selectedPool && <p className="agent-model-hint">{!resources.modelOptions.length ? tx('noActiveModels') : ''}</p>}
                  <label><span>{tx('temperature')}</span><input type="number" min="0" max="2" step="0.1" value={draft.temperature ?? 0.2} onChange={(event) => patchDraft('temperature', Number(event.target.value))} /></label>
                </section>

                <section className="agent-card">
                  <header><span>03</span><b>{tx('executionBoundary')}</b></header>
                  <label><span>{tx('permissionMode')}</span><select value={draft.permissionMode ?? 'ask'} onChange={(event) => patchDraft('permissionMode', event.target.value as AgentDraft['permissionMode'])}><option value="private">PRIVATE</option><option value="ask">ASK</option><option value="auto">AUTO</option><option value="deny">DENY</option></select></label>
                  <div className="agent-switch-row"><span><b>{tx('sandbox')}</b><small>{tx(resources.capabilities.sandbox.containerized ? 'sandboxContainerManaged' : 'sandboxCompatibility')}</small></span><em className={resources.capabilities.sandbox.containerized ? '' : 'compatibility'}>{resources.capabilities.sandbox.isolation.replace('_', ' ')}</em></div>
                  <label className="agent-check"><input type="checkbox" checked={draft.networkEnabled ?? false} onChange={(event) => patchDraft('networkEnabled', event.target.checked)} /><span><b>{tx('networkAccess')}</b><small>{tx('networkAccessHint')}</small></span></label>
                </section>

                <section className="agent-card agent-budget-card">
                  <header><span>04</span><b>{tx('sessionBudget')}</b></header>
                  <div className="agent-number-grid"><label><span>{tx('maxTurns')}</span><input type="number" min="1" max="100" value={draft.maxTurns ?? 40} onChange={(event) => patchDraft('maxTurns', Number(event.target.value))} /></label><label><span>{tx('tokenBudget')}</span><input type="number" min="1" max="32768" value={draft.maxTokens ?? 4096} onChange={(event) => patchDraft('maxTokens', Number(event.target.value))} /></label></div>
                  <label className="agent-check"><input type="checkbox" checked={draft.memoryEnabled ?? false} onChange={(event) => patchDraft('memoryEnabled', event.target.checked)} /><span><b>{tx('memorySetting')}</b><small>{tx('memoryHint')}</small></span></label>
                </section>

                <section className="agent-card agent-knowledge-card">
                  <header><span>05</span><b>{tx('ragKnowledge')}</b></header>
                  <label className="agent-check"><input type="checkbox" checked={draft.ragEnabled ?? false} onChange={(event) => patchDraft('ragEnabled', event.target.checked)} /><span><b>{tx('enableRag')}</b><small>{draft.knowledgeBaseIds.length} {tx('sourcesBound')}</small></span></label>
                  <div className="agent-resource-options">{resources.knowledgeDocuments.map((document) => <button type="button" key={document.id} className={draft.knowledgeBaseIds.includes(document.id) ? 'active' : ''} onClick={() => toggleKnowledge(document.id)}><span>{document.name}</span><small>{document.status}</small></button>)}{!resources.knowledgeDocuments.length && <p>{tx('noKnowledgeSources')}</p>}</div>
                </section>

                <section className="agent-card agent-integrations-card agent-tools-compact">
                  <header><span>06</span><b>{tx('skillsAndTools')}</b></header>
                  <AgentSkills key={selected.id} request={request} userId={userId} selected={draft.skillIds}
                    enabledTools={draft.enabledToolIds} tools={resources.capabilities.tools} disabled={!canEdit || saving}
                    onChange={ids => patchDraft('skillIds', ids)} tx={tx} />
                  <ToolPicker key={selected.id} tools={resources.capabilities.tools} selected={draft.enabledToolIds}
                    disabled={!canEdit || saving} onToggle={id => toggleCapability('enabledToolIds', id)} tx={tx} />
                  {toolRequirements.requiresNetwork && !draft.networkEnabled && <p className="capability-policy-note warning">{tx('toolNetworkPolicyWarning')}</p>}
                  {toolRequirements.requiresKnowledge && (!draft.ragEnabled || !draft.knowledgeBaseIds.length) && <p className="capability-policy-note warning">{tx('toolKnowledgePolicyWarning')}</p>}
                  {toolRequirements.requiresWorkspace && <p className="capability-policy-note">{tx('toolWorkspacePolicyHint')}</p>}
                  {toolRequirements.hasUnavailable && <p className="capability-policy-note warning">{tx('unavailableToolWarning')}</p>}
                </section>
              </div>
            </fieldset>
          </form>
        )}
      </main>

      <aside className="agent-audit">
        <header><b>{tx('definitionStatus')}</b><span>{selected?.status ?? '—'}</span></header>
        {pendingChanges.length > 0 && <section><label>{tx('pendingAgentApprovals')}</label><div className="agent-change-summary">{pendingChanges.slice(0, 8).map((change) => <article key={change.id}><header><b>{tx('agentApprovalPending')}</b></header><small>{new Date(change.updatedAt).toLocaleString()}</small></article>)}</div></section>}
        <section><label>{tx('bindings')}</label><dl><div><dt>{tx('knowledgeNav')}</dt><dd>{draft?.knowledgeBaseIds.length ?? 0}</dd></div><div><dt>{tx('skills')}</dt><dd>{draft?.skillIds.length ?? 0}</dd></div><div><dt>{tx('tools')}</dt><dd>{draft?.enabledToolIds.length ?? 0}</dd></div></dl></section>
      </aside>

      {deleteConfirm && selected && <div className="agent-modal" role="dialog" aria-modal="true" aria-labelledby="agent-delete-dialog-title"><div className="agent-dialog"><span>{tx('systemConfirmation')}</span><h2 id="agent-delete-dialog-title">{tx('deleteAgent')}</h2><p>{tx('agentDeleteWarning')}</p><strong>{selected.name}</strong><div><button disabled={saving} onClick={() => setDeleteConfirm(false)}>{tx('cancel')}</button><button className="danger" disabled={saving} onClick={() => void removeAgent()}>{saving ? tx('processing') : tx('confirmDeleteAgent')}</button></div></div></div>}

      {keyManagerOpen && selected && <div className="agent-modal" role="dialog" aria-modal="true" aria-labelledby="agent-key-dialog-title"><div className="agent-dialog agent-key-dialog">
        <header><div><span>{tx('currentConfiguration')}</span><h2 id="agent-key-dialog-title">{tx('agentKeysTitle')}</h2><p>{tx('agentKeysCopy')}</p></div><button type="button" onClick={closeKeyManager} aria-label={tx('close')}>×</button></header>
        {keyError && <div className="agent-key-error" role="alert">{keyError}</div>}
        {createdApiKey && <section className="agent-key-once"><b>{tx('oneTimeAgentKey')}</b><code>{createdApiKey.rawKey}</code><small>{tx('oneTimeAgentKeyWarning')}</small></section>}
        <form className="agent-key-form" onSubmit={submitApiKey}>
          <label><span>{tx('agentKeyName')}</span><input name="name" maxLength={128} required autoFocus /></label>
          <label><span>{tx('agentKeyExpiry')}</span><input name="expiresAt" type="datetime-local" /></label>
          <fieldset><legend>SCOPES</legend><label><input name="scopes" type="checkbox" value="CHAT" defaultChecked /><span>CHAT</span></label><label><input name="scopes" type="checkbox" value="ADMIN" /><span>ADMIN</span></label></fieldset>
          <button className="primary" disabled={keyBusy}>{keyBusy ? tx('processing') : tx('createAgentKey')}</button>
        </form>
        <section className="agent-key-list" aria-live="polite">
          {apiKeys.map((key) => <article key={key.id}><div><b>{key.name}</b><code>{key.keyPrefix}…</code><small>{key.scopes.join(' · ')} · {key.expiresAt ? new Date(key.expiresAt).toLocaleString() : tx('expiresNever')}</small></div><span className={key.enabled ? 'good' : ''}>{key.enabled ? 'ACTIVE' : 'REVOKED'}</span>{key.enabled && (revokeKeyId === key.id ? <div className="agent-key-revoke"><button type="button" onClick={() => setRevokeKeyId(null)}>{tx('cancel')}</button><button type="button" className="danger" disabled={keyBusy} onClick={() => void revokeApiKey(key.id)}>{tx('confirmRevokeAgentKey')}</button></div> : <button type="button" className="danger" onClick={() => setRevokeKeyId(key.id)}>{tx('revokeAgentKey')}</button>)}</article>)}
          {!keyBusy && !apiKeys.length && <small>{tx('noAgentKeys')}</small>}
        </section>
      </div></div>}

      {changesOpen && selected && <div className="agent-modal" role="dialog" aria-modal="true" aria-labelledby="agent-changes-dialog-title"><div className="agent-dialog agent-change-dialog">
        <header><div><span>{tx('currentConfiguration')}</span><h2 id="agent-changes-dialog-title">{tx('agentChanges')}</h2><p>{tx('agentChangesCopy')}</p></div><button type="button" onClick={() => { setChangesOpen(false); setPendingDecision(null); setChangesError(null) }} aria-label={tx('close')}>×</button></header>
        <div className="agent-change-toolbar"><button type="button" disabled={changesLoading} onClick={() => void loadChanges(selected.id)}>{changesLoading ? tx('processing') : tx('refresh')}</button></div>
        {changesError && <div className="agent-key-error" role="alert">{changesError}</div>}
        <section className="agent-change-list" aria-live="polite">
          {pendingChanges.map((change) => <article key={change.id}>
            <header><div><b>{change.state}</b><code>{change.id}</code></div></header>
            <dl><div><dt>{tx('requester')}</dt><dd>{change.requestedBy}</dd></div><div><dt>{tx('created')}</dt><dd>{new Date(change.createdAt).toLocaleString()}</dd></div><div><dt>{tx('updated')}</dt><dd>{new Date(change.updatedAt).toLocaleString()}</dd></div></dl>
            {change.proposal && <details><summary>{tx('viewProposedConfiguration')}</summary><div className="agent-change-proposal"><p><b>{tx('agentName')}</b><span>{change.proposal.name}</span></p><p><b>{tx('description')}</b><span>{change.proposal.description || '—'}</span></p><p><b>{tx('systemInstruction')}</b><span className="preserve-lines">{change.proposal.systemPrompt || '—'}</span></p><p><b>{tx('modelId')}</b><span>{change.proposal.modelId || change.proposal.modelPoolId || '—'}</span></p><p><b>{tx('bindings')}</b><span>{change.proposal.knowledgeBaseIds.length} KB · {change.proposal.skillIds.length} Skills · {change.proposal.enabledToolIds.length} Tools</span></p></div></details>}
            {pendingDecision?.change.id === change.id ? <form className="agent-change-decision" onSubmit={decideChange}><p>{pendingDecision.decision === 'APPROVE' ? tx('approveAgentChangeWarning') : tx('rejectAgentChangeWarning')}</p><label><span>{tx('decisionNote')}</span><textarea name="note" maxLength={2000} autoFocus /></label><div><button type="button" disabled={changesLoading} onClick={() => setPendingDecision(null)}>{tx('cancel')}</button><button className={pendingDecision.decision === 'REJECT' ? 'danger' : 'primary'} disabled={changesLoading}>{changesLoading ? tx('processing') : tx(pendingDecision.decision === 'APPROVE' ? 'confirmApproveAgentChange' : 'confirmRejectAgentChange')}</button></div></form> : change.state === 'PENDING' && canApproveChanges && <div className="agent-review-actions"><button type="button" disabled={changesLoading} onClick={() => setPendingDecision({ change, decision: 'APPROVE' })}>{tx('approveAgentChange')}</button><button type="button" className="danger" disabled={changesLoading} onClick={() => setPendingDecision({ change, decision: 'REJECT' })}>{tx('rejectAgentChange')}</button></div>}
          </article>)}
          {!changesLoading && !pendingChanges.length && <small>{tx('noAgentChanges')}</small>}
        </section>
      </div></div>}
    </section>
  )
}
