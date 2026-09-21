import type { AgentSummary } from './types'

export const availableConversationAgents = (agents: AgentSummary[]) =>
  agents.filter((agent) => !agent.status || agent.status === 'ACTIVE')

export const selectNewConversationAgent = (
  agents: AgentSummary[],
  preferredAgentId: string,
) => {
  const available = availableConversationAgents(agents)
  return available.find((agent) => agent.id === preferredAgentId) ?? available[0] ?? null
}
