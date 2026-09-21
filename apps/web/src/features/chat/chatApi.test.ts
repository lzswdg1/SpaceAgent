import { describe, expect, it, vi } from 'vitest'
import type { AuthenticatedRequest } from './types'
import { deleteConversation, renameConversation, reconcileChatTool, resumeChatApproval, streamMessage, switchConversationAgent } from './chatApi'

describe('chat API lifecycle', () => {
  it('renames only the selected conversation through its owned metadata endpoint',async()=>{
    const request=vi.fn(async()=>({id:'conversation/1',title:'New name'})) as unknown as AuthenticatedRequest
    await renameConversation(request,'conversation/1','  New name  ')
    expect(request).toHaveBeenCalledWith('/api/v1/chat/conversations/conversation%2F1/title',{
      method:'PUT',body:JSON.stringify({name:'New name'}),
    })
  })
  it('rejects EOF without a terminal event instead of presenting a partial stream as complete',async()=>{
    const open=vi.fn(async()=>new Response('event: delta\ndata: {"content":"unfinished"}\n\n'))
    await expect(streamMessage(open,{conversationId:'c',agentId:'a',message:'q'},
      {onReasoningDelta:vi.fn(),onDelta:vi.fn(),onDone:vi.fn(),onRuntimeEvent:vi.fn()},new AbortController().signal))
      .rejects.toMatchObject({code:'CHAT_STREAM_INCOMPLETE'})
  })
  it('sends the selected repository workspace through the SSE contract', async () => {
    const openStream = vi.fn(async () => new Response('event: done\ndata: {}\n\n'))
    await streamMessage(openStream, { conversationId: 'c', agentId: 'a', message: 'read', workspaceId: 'w' },
      { onReasoningDelta: vi.fn(), onDelta: vi.fn(), onDone: vi.fn(), onRuntimeEvent: vi.fn() }, new AbortController().signal)
    expect(openStream).toHaveBeenCalledWith('/api/v1/chat/messages/stream', expect.objectContaining({
      body: expect.stringContaining('"workspaceId":"w"'),
    }))
  })
  it('deletes through the canonical encoded Conversation route', async () => {
    const request = vi.fn(async () => undefined) as unknown as AuthenticatedRequest
    await deleteConversation(request, 'conversation/1')
    expect(request).toHaveBeenCalledWith('/api/v1/chat/conversations/conversation%2F1', { method: 'DELETE' })
  })

  it('persists the selected Agent through the owned Conversation route', async () => {
    const request = vi.fn(async () => ({ agentId: 'agent-2' })) as unknown as AuthenticatedRequest
    await switchConversationAgent(request, 'conversation/1', 'agent-2')
    expect(request).toHaveBeenCalledWith('/api/v1/chat/conversations/conversation%2F1/agent', {
      method: 'PUT',
      body: JSON.stringify({ agentId: 'agent-2' }),
    })
  })

  it('routes reasoning and answer deltas through separate streaming handlers', async () => {
    const encoder = new TextEncoder()
    const response = new Response(new ReadableStream<Uint8Array>({
      start(controller) {
        controller.enqueue(encoder.encode('event: reasoning_delta\ndata: {"content":"checking"}\n\n'))
        controller.enqueue(encoder.encode('event: delta\ndata: {"content":"answer"}\n\n'))
        controller.enqueue(encoder.encode('event: done\ndata: {"totalTokenCount":9}\n\n'))
        controller.close()
      },
    }))
    const onReasoningDelta = vi.fn()
    const onDelta = vi.fn()
    const onDone = vi.fn()
    await streamMessage(vi.fn(async () => response), {
      conversationId: 'conversation-1', agentId: 'agent-1', message: 'search',
    }, {
      onReasoningDelta, onDelta, onDone, onRuntimeEvent: vi.fn(),
    }, new AbortController().signal)

    expect(onReasoningDelta).toHaveBeenCalledWith('checking')
    expect(onDelta).toHaveBeenCalledWith('answer')
    expect(onDone).toHaveBeenCalledWith({ totalTokenCount: 9 })
  })

  it('surfaces durable suspension and maps approval/reconciliation resumes', async () => {
    const encoder = new TextEncoder()
    const response = new Response(new ReadableStream<Uint8Array>({ start(controller) { controller.enqueue(encoder.encode('event: suspended\ndata: {"agentRunId":"run/1","executionState":"WAITING_RECONCILIATION","toolCallId":"tool/1","toolName":"workspace-write-file","toolRevision":2}\n\n')); controller.close() } }))
    const onSuspended = vi.fn()
    await streamMessage(vi.fn(async () => response), { conversationId: 'conversation-1', agentId: 'agent-1', message: 'write' }, { onReasoningDelta: vi.fn(), onDelta: vi.fn(), onDone: vi.fn(), onRuntimeEvent: vi.fn(), onSuspended }, new AbortController().signal)
    expect(onSuspended).toHaveBeenCalledWith(expect.objectContaining({ executionState: 'WAITING_RECONCILIATION', toolRevision: 2 }))

    const request = vi.fn(async () => ({})) as unknown as AuthenticatedRequest
    await resumeChatApproval(request, 'run/1', 'approval/1')
    await reconcileChatTool(request, 'run/1', 'tool/1', 2, 'verify')
    expect(request).toHaveBeenNthCalledWith(1, '/api/v1/chat/runs/run%2F1/resume-approval', expect.objectContaining({ method: 'POST' }))
    expect(request).toHaveBeenNthCalledWith(2, '/api/v1/chat/runs/run%2F1/tools/tool%2F1/reconcile', expect.objectContaining({ method: 'POST' }))
  })
})
