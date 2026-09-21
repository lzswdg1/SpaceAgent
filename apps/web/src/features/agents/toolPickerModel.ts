import type { RuntimeCapability } from './types'

export const toolCategories = ['All', 'Network', 'Knowledge', 'Files', 'Git', 'Mcp', 'Coding', 'Other'] as const
export type ToolCategory = typeof toolCategories[number]
export type ToolFilter = 'All' | 'Selected' | 'Available'

// Presentation-only classification. Permissions/availability always come from the catalog.
export function toolCategory(tool: RuntimeCapability): ToolCategory {
  const kind = `${tool.id} ${tool.executionMode}`.toLowerCase()
  if (/github|mcp/.test(kind)) return 'Mcp'
  if (/knowledge/.test(kind)) return 'Knowledge'
  if (/git/.test(kind)) return 'Git'
  if (/coding|command|exec/.test(kind)) return 'Coding'
  if (/file|document|workspace/.test(kind)) return 'Files'
  if (tool.requiresNetwork || /search|http/.test(kind)) return 'Network'
  return 'Other'
}

export function filterTools(tools: RuntimeCapability[], selected: string[], query: string, category: ToolCategory, filter: ToolFilter) {
  const needle = query.trim().toLowerCase()
  return tools.filter(tool =>
    (category === 'All' || toolCategory(tool) === category)
    && (filter !== 'Selected' || selected.includes(tool.id))
    && (filter !== 'Available' || tool.available)
    && (!needle || `${tool.name} ${tool.description} ${tool.id}`.toLowerCase().includes(needle)))
}
