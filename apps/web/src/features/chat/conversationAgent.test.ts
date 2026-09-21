import { describe, expect, it } from 'vitest'
import { availableConversationAgents, selectNewConversationAgent } from './conversationAgent'
import type { AgentSummary } from './types'

const agent = (id: string, status = 'ACTIVE'): AgentSummary => ({
  id,
  name: id,
  status,
  description: null,
  modelPoolId: null,
  modelProviderId: null,
  modelId: null,
  maxTurns: null,
})

describe('new conversation Agent selection', () => {
  it('keeps an explicit active selection and otherwise falls back to the first active Agent', () => {
    const agents = [agent('archived', 'ARCHIVED'), agent('first'), agent('preferred')]
    expect(availableConversationAgents(agents).map(({ id }) => id)).toEqual(['first', 'preferred'])
    expect(selectNewConversationAgent(agents, 'preferred')?.id).toBe('preferred')
    expect(selectNewConversationAgent(agents, 'missing')?.id).toBe('first')
  })

  it('returns no selection when no active Agent exists', () => {
    expect(selectNewConversationAgent([agent('archived', 'ARCHIVED')], '')).toBeNull()
  })
})
