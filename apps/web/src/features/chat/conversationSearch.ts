import type { AgentSummary, ConversationSummary } from './types'

export function filterConversations(
  conversations: ConversationSummary[],
  agents: AgentSummary[],
  query: string,
): ConversationSummary[] {
  const normalized = query.trim().toLowerCase()
  if (!normalized) return conversations
  const agentNames = new Map(agents.map((agent) => [agent.id, agent.name]))
  return conversations.filter((conversation) =>
    `${conversation.title} ${agentNames.get(conversation.agentId) ?? ''}`
      .toLowerCase()
      .includes(normalized))
}
