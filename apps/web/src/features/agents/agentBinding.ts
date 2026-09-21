import type { AgentDraft } from './types'
export function selectAgentModelPool(draft: AgentDraft, modelPoolId: string | null): AgentDraft {
  return { ...draft, modelPoolId, modelProviderId: null, modelId: null }
}
export function normalizedAgentBinding(draft: AgentDraft): AgentDraft {
  return draft.modelPoolId ? { ...draft, modelProviderId: null, modelId: null } : draft
}
