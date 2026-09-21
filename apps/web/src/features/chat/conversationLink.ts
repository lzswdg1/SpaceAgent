import { ApiError } from '../../lib/api'
import { getConversation } from './chatApi'
import type { AuthenticatedRequest, ConversationSummary } from './types'

export async function loadLinkedConversation(request: AuthenticatedRequest, id: string,
  scope: 'CHAT' | 'PROJECT', fallbackTitle: string,
  expected?: { projectId: string | null; directoryId: string | null },
): Promise<ConversationSummary> {
  const detail = await getConversation(request, id)
  if (detail.conversationId !== id || detail.status !== 'ACTIVE'
    || (scope === 'CHAT' ? Boolean(detail.projectId) : !detail.projectId || !detail.projectDirectoryId)
    || (expected?.projectId && detail.projectId !== expected.projectId)
    || (expected?.directoryId && detail.projectDirectoryId !== expected.directoryId)) {
    throw new ApiError('Conversation is unavailable in this workspace', 404, 'CONVERSATION_LINK_UNAVAILABLE')
  }
  // Older servers may omit the additive title field.
  return { id, agentId: detail.agentId, userId: detail.userId, projectId: detail.projectId,
    projectDirectoryId: detail.projectDirectoryId, activeTaskId: detail.activeTaskId,
    status: detail.status, createdAt: detail.startedAt, updatedAt: detail.lastMessageAt, title: detail.title || fallbackTitle }
}

export function includeLinkedConversation<T>(items: T[], linked: T | null, id: (item: T) => string): T[] {
  return linked && !items.some(item => id(item) === id(linked)) ? [linked, ...items] : items
}
