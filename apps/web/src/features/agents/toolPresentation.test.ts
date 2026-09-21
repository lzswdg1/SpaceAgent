import { describe, expect, it } from 'vitest'
import { canToggleCapability, selectedToolRequirements, toolConfigurationIssue } from './toolPresentation'
import type { RuntimeCapability } from './types'

const tool = (input: Partial<RuntimeCapability> & Pick<RuntimeCapability, 'id'>): RuntimeCapability => ({
  name: input.id,
  description: '',
  executionMode: 'TEST',
  inputSchema: {},
  available: true,
  readOnly: true,
  requiresNetwork: false,
  requiresWorkspace: false,
  ...input,
  id: input.id,
})

describe('Tool catalog presentation', () => {
  it('fails closed for unavailable additions while allowing removal', () => {
    const unavailable = tool({ id: 'web_search', available: false })
    expect(canToggleCapability(unavailable, false)).toBe(false)
    expect(canToggleCapability(unavailable, true)).toBe(true)
  })

  it('derives network and Workspace requirements from selected backend Tools', () => {
    const requirements = selectedToolRequirements([
      tool({ id: 'http_fetch', requiresNetwork: true }),
      tool({ id: 'file_read', requiresWorkspace: true }),
      tool({ id: 'knowledge_search' }),
      tool({ id: 'web_search', available: false }),
    ], ['http_fetch', 'file_read', 'knowledge_search', 'web_search'])
    expect(requirements).toEqual({
      requiresNetwork: true,
      requiresWorkspace: true,
      requiresKnowledge: true,
      hasUnavailable: true,
    })
  })

  it('returns an actionable save issue instead of silently disabling submit', () => {
    expect(toolConfigurationIssue({
      requiresNetwork: true, requiresWorkspace: false, requiresKnowledge: false, hasUnavailable: false,
    }, false, false)).toBe('NETWORK')
    expect(toolConfigurationIssue({
      requiresNetwork: false, requiresWorkspace: false, requiresKnowledge: false, hasUnavailable: true,
    }, true, true)).toBe('UNAVAILABLE')
    expect(toolConfigurationIssue({
      requiresNetwork: false, requiresWorkspace: false, requiresKnowledge: true, hasUnavailable: false,
    }, true, false)).toBe('KNOWLEDGE')
    expect(toolConfigurationIssue({
      requiresNetwork: true, requiresWorkspace: true, requiresKnowledge: true, hasUnavailable: false,
    }, true, true)).toBeNull()
  })
})
