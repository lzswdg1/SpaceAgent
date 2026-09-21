import type { RuntimeCapability } from './types'

export const canToggleCapability = (capability: RuntimeCapability, selected: boolean) =>
  capability.available || selected

export const selectedToolRequirements = (
  tools: RuntimeCapability[],
  enabledToolIds: string[],
) => {
  const selected = tools.filter(({ id }) => enabledToolIds.includes(id))
  return {
    requiresNetwork: selected.some(({ requiresNetwork }) => requiresNetwork),
    requiresWorkspace: selected.some(({ requiresWorkspace }) => requiresWorkspace),
    requiresKnowledge: selected.some(({ id }) => id === 'knowledge_search'),
    hasUnavailable: selected.some(({ available }) => !available),
  }
}

export const toolConfigurationIssue = (
  requirements: ReturnType<typeof selectedToolRequirements>,
  networkEnabled: boolean,
  knowledgeEnabled: boolean,
) => {
  if (requirements.hasUnavailable) return 'UNAVAILABLE' as const
  if (requirements.requiresNetwork && !networkEnabled) return 'NETWORK' as const
  if (requirements.requiresKnowledge && !knowledgeEnabled) return 'KNOWLEDGE' as const
  return null
}
