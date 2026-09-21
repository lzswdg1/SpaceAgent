import type { AgentModelOption } from './types'

export function matchModelOptions(options: AgentModelOption[], query: string, limit = 8) {
  const normalized = query.trim()
  if (!normalized) return []
  const matcher = new RegExp(normalized.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'), 'i')
  return options
    .filter((option) => matcher.test(`${option.modelId} ${option.displayName} ${option.providerName}`))
    .slice(0, limit)
}
