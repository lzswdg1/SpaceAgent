import {describe,it,expect,vi} from 'vitest'
import {ChatStreamOperation} from './streamOperation'
import type {AuthenticatedRequest} from './types'

describe('stream cancellation identity',()=>{
  it('pins the accepted Run and ignores a different conversation or later Run',async()=>{
    const operation=new ChatStreamOperation();operation.conversationId='A'
    operation.accept({type:'run_accepted',data:{conversationId:'B',agentRunId:'run-B'}})
    expect(operation.runId).toBeNull()
    operation.accept({type:'run_accepted',data:{conversationId:'A',agentRunId:'run-A'}})
    operation.accept({type:'runtime_started',data:{agentRunId:'another-run'}})
    const request=vi.fn(async()=>({})) as unknown as AuthenticatedRequest
    await operation.cancel(request)
    expect(request).toHaveBeenCalledWith('/api/v1/chat/runs/run-A/cancel',{method:'POST'})
    expect(operation.controller.signal.aborted).toBe(true)
  })
  it('does not abort transport when cancellation fails, and allows explicit retry',async()=>{
    const operation=new ChatStreamOperation();operation.conversationId='A'
    operation.accept({type:'run_accepted',data:{agentRunId:'run-A'}})
    const request=vi.fn().mockRejectedValueOnce(Error('offline')).mockResolvedValue({})
    await expect(operation.cancel(request)).rejects.toThrow('offline')
    expect(operation.controller.signal.aborted).toBe(false)
    await operation.cancel(request)
    expect(operation.controller.signal.aborted).toBe(true)
  })
  it('does not cancel an inferred Run before admission, and coalesces repeated clicks',async()=>{
    const operation=new ChatStreamOperation(),request=vi.fn(async()=>({})) as unknown as AuthenticatedRequest
    await expect(operation.cancel(request)).rejects.toThrow('not been accepted')
    expect(request).not.toHaveBeenCalled()
    operation.conversationId='A';operation.accept({type:'run_accepted',data:{agentRunId:'run-A'}})
    await Promise.all([operation.cancel(request),operation.cancel(request)])
    expect(request).toHaveBeenCalledTimes(1)
  })
})
