import { cancelChatRun } from './chatRunApi'
import type { AuthenticatedRequest, RuntimeEvent } from './types'

/** Transport ownership is pinned independently of the currently selected conversation. */
export class ChatStreamOperation {
  readonly controller = new AbortController()
  runId: string | null = null
  conversationId: string | null = null
  private cancelling: Promise<void> | null = null

  accept(event: RuntimeEvent) {
    if (!['run_accepted', 'runtime_started'].includes(event.type) || !this.conversationId) return
    const data = event.data as { agentRunId?: string; conversationId?: string } | null
    if (!data?.agentRunId || (data.conversationId && data.conversationId !== this.conversationId)) return
    if (!this.runId) this.runId = data.agentRunId
  }

  cancel(request: AuthenticatedRequest): Promise<void> {
    if (!this.runId) return Promise.reject(new Error('Run has not been accepted yet'))
    if (this.cancelling) return this.cancelling
    this.cancelling = cancelChatRun(request, this.runId).then(() => {
      this.controller.abort()
    }).finally(() => { this.cancelling = null })
    return this.cancelling
  }
}
