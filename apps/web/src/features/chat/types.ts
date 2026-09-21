import type { AuthenticatedRequest } from '../overview/types'

export type { AuthenticatedRequest }
export type OpenStream = (path: string, init?: RequestInit) => Promise<Response>

export type AgentSummary = {
  id: string
  name: string
  description: string | null
  status?: string
  modelPoolId: string | null
  modelProviderId: string | null
  modelId: string | null
  maxTurns: number | null
}

export type ConversationSummary = {
  id: string
  agentId: string
  userId?: string
  activeTaskId?: string | null
  projectId?: string | null
  projectDirectoryId?: string | null
  title: string
  status: string
  createdAt: string
  updatedAt: string
}

export type Conversation = {
  title?: string
  conversationId: string
  agentId: string
  userId: string
  projectId: string | null
  projectDirectoryId: string | null
  activeTaskId: string | null
  status: string
  startedAt: string
  lastMessageAt: string
  messages: Message[]
  messagePage: number
  messageSize: number
  messageTotal: number
}

export type Message = {
  role: string
  content: string
  createdAt: string
  completionState?: 'PARTIAL' | 'COMPLETE'
}

export type PageResponse<T> = {
  items: T[]
  page: number
  size: number
  total: number
}

export type ChatDone = {
  conversationId: string
  agentRunId: string
  memoryUpdated: boolean
  ragUsed: boolean
  retrievedChunkCount: number
  citations: string[]
  knowledgeCitations: string[]
  inputTokenCount: number
  outputTokenCount: number
  totalTokenCount: number
  usageSource: string
  eventCount: number
}

export type ChatSuspended = {
  conversationId: string
  agentRunId: string
  executionState: 'WAITING_APPROVAL' | 'WAITING_RECONCILIATION'
  toolCallId: string
  toolName: string
  approvalId?: string
  toolRevision?: number
}

export type ChatExecution = {
  conversationId: string
  agentRunId: string
  assistantMessage: string
  executionState: string
  pendingApprovalId: string | null
  pendingToolCallId: string | null
  pendingToolName: string | null
  pendingToolRevision: number | null
  totalTokenCount: number
  runtimeEvents: RuntimeEvent[]
}

export type RuntimeEvent = {
  type: string
  data: unknown
}
