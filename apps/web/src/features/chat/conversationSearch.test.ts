import { describe, expect, it } from 'vitest'
import { filterConversations } from './conversationSearch'
import type { AgentSummary, ConversationSummary } from './types'

const conversations: ConversationSummary[] = [
  { id: 'c1', agentId: 'a1', title: 'Release review', status: 'ACTIVE', createdAt: '', updatedAt: '' },
  { id: 'c2', agentId: 'a2', title: 'Knowledge audit', status: 'ACTIVE', createdAt: '', updatedAt: '' },
]
const agents = [
  { id: 'a1', name: 'Guardian' },
  { id: 'a2', name: 'Scout' },
] as AgentSummary[]

describe('conversation search', () => {
  it('matches both Conversation title and Agent name', () => {
    expect(filterConversations(conversations, agents, 'release')).toEqual([conversations[0]])
    expect(filterConversations(conversations, agents, 'scout')).toEqual([conversations[1]])
    expect(filterConversations(conversations, agents, '  ')).toBe(conversations)
  })
})
