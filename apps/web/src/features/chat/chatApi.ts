import { consumeSse } from '../../lib/sse'
import { ApiError } from '../../lib/api'
import type {
  AgentSummary,
  AuthenticatedRequest,
  ChatDone,
  ChatExecution,
  ChatSuspended,
  Conversation,
  ConversationSummary,
  Message,
  OpenStream,
  PageResponse,
  RuntimeEvent,
} from './types'

export const listAgents = (request: AuthenticatedRequest, offset = 0, signal?: AbortSignal) => signal
  ? request<AgentSummary[]>(`/api/v1/agents?summary=true&offset=${offset}&limit=100`, { signal })
  : request<AgentSummary[]>(`/api/v1/agents?summary=true&offset=${offset}&limit=100`)
export const listConversations = (request: AuthenticatedRequest, page=1, scope?:'CHAT'|'PROJECT', query='') => request<PageResponse<ConversationSummary>>(`/api/v1/chat/conversations?page=${page}&size=100${scope?`&scope=${scope}`:''}${query?`&query=${encodeURIComponent(query)}`:''}`)
export type MessagePage = { items: (Message & { id:string; sequence:number })[]; beforeSequence:number|null; hasMore:boolean }
export const loadMessagePage = (request:AuthenticatedRequest,conversationId:string,beforeSequence?:number|null) => request<MessagePage>(
  `/api/v1/chat/conversations/${encodeURIComponent(conversationId)}/messages/page?size=50${beforeSequence==null?'':`&beforeSequence=${beforeSequence}`}`)
export const listMessages = (request: AuthenticatedRequest, conversationId: string) => request<Message[]>(`/api/v1/chat/conversations/${encodeURIComponent(conversationId)}/messages`)
export const getConversation = (request: AuthenticatedRequest, conversationId: string) => request<Conversation>(`/api/v1/chat/conversations/${encodeURIComponent(conversationId)}`)
export const createConversation = (
  request: AuthenticatedRequest,
  agentId: string,
  name: string,
) => request<Conversation>('/api/v1/chat/conversations', {
  method: 'POST',
  body: JSON.stringify({ agentId, name, projectId: null, activeTaskId: null }),
})

export const deleteConversation = (request: AuthenticatedRequest, conversationId: string) => request<void>(
  `/api/v1/chat/conversations/${encodeURIComponent(conversationId)}`,
  { method: 'DELETE' },
)

export const renameConversation = (request: AuthenticatedRequest, conversationId: string, name: string) => request<ConversationSummary>(
  `/api/v1/chat/conversations/${encodeURIComponent(conversationId)}/title`,
  { method: 'PUT', body: JSON.stringify({ name: name.trim() }) },
)

export const switchConversationAgent = (
  request: AuthenticatedRequest,
  conversationId: string,
  agentId: string,
) => request<Conversation>(`/api/v1/chat/conversations/${encodeURIComponent(conversationId)}/agent`, {
  method: 'PUT',
  body: JSON.stringify({ agentId }),
})

export async function streamMessage(
  openStream: OpenStream,
  input: { conversationId: string; agentId: string; message: string; modelId?: string | null; workspaceId?: string | null },
  handlers: {
    onReasoningDelta: (content: string) => void
    onDelta: (content: string) => void
    onRuntimeEvent: (event: RuntimeEvent) => void
    onDone: (done: ChatDone) => void
    onSuspended?: (suspended: ChatSuspended) => void
  },
  signal: AbortSignal,
) {
  const response = await openStream('/api/v1/chat/messages/stream', {
    method: 'POST',
    signal,
    body: JSON.stringify({
      conversationId: input.conversationId,
      agentId: input.agentId,
      message: input.message,
      modelId: input.modelId || null,
      imageIds: [],
      ...(input.workspaceId ? { workspaceId: input.workspaceId } : {}),
    }),
  })
  let terminal = false
  await consumeSse(response, ({ event, data }) => {
    if (event === 'reasoning_delta' && typeof data === 'object' && data !== null && 'content' in data) {
      handlers.onReasoningDelta(String((data as { content: unknown }).content))
    } else if (event === 'delta' && typeof data === 'object' && data !== null && 'content' in data) {
      handlers.onDelta(String((data as { content: unknown }).content))
    } else if (event === 'done') {
      terminal = true
      handlers.onDone(data as ChatDone)
    } else if (event === 'suspended') {
      terminal = true
      if (handlers.onSuspended) handlers.onSuspended(data as ChatSuspended)
      else handlers.onRuntimeEvent({ type: event, data })
    } else if (event === 'error') {
      const value = data as { message?: string;code?:string;status?:number }
      throw new ApiError(value.message || 'Chat stream failed',value.status||0,value.code)
    } else {
      handlers.onRuntimeEvent({ type: event, data })
    }
  }, signal)
  if (!terminal) throw new ApiError('Connection ended before the response was confirmed complete', 0, 'CHAT_STREAM_INCOMPLETE')
}

export const resumeChatApproval = (request: AuthenticatedRequest, agentRunId: string, approvalId: string) => request<ChatExecution>(
  `/api/v1/chat/runs/${encodeURIComponent(agentRunId)}/resume-approval`, { method: 'POST', body: JSON.stringify({ approvalId }) },
)

export const reconcileChatTool = (request: AuthenticatedRequest, agentRunId: string, toolCallId: string, expectedRevision: number, reason: string) => request<ChatExecution>(
  `/api/v1/chat/runs/${encodeURIComponent(agentRunId)}/tools/${encodeURIComponent(toolCallId)}/reconcile`, { method: 'POST', body: JSON.stringify({ expectedRevision, reason }) },
)
