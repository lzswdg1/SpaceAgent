import {describe,it,expect,vi} from 'vitest'
import {includeLinkedConversation,loadLinkedConversation} from './conversationLink'
const detail={conversationId:'old/c',agentId:'a',userId:'u',projectId:null,projectDirectoryId:null,
  activeTaskId:null,status:'ACTIVE',startedAt:'2026-09-13',lastMessageAt:'2026-09-13'}
describe('explicit conversation links',()=>{
  it('uses the persisted detail title when available',async()=>{
    const request=vi.fn().mockResolvedValue({...detail,title:'Persisted name'})
    await expect(loadLinkedConversation(request,'old/c','CHAT','Fallback')).resolves.toMatchObject({title:'Persisted name'})
  })
  it('loads an exact ID outside the first summary page and keeps it reachable',async()=>{
    const request=vi.fn(async()=>detail)
    const linked=await loadLinkedConversation(request as never,'old/c','CHAT','Conversation')
    expect(request).toHaveBeenCalledWith('/api/v1/chat/conversations/old%2Fc')
    expect(linked.id).toBe('old/c')
    const rows=includeLinkedConversation([{...linked,id:'new'}],linked,row=>row.id)
    expect(rows.map(row=>row.id)).toEqual(['old/c','new'])
    expect(includeLinkedConversation(rows,linked,row=>row.id)).toEqual(rows)
  })
  it('never falls back to a different conversation after a denied detail request',async()=>{
    await expect(loadLinkedConversation(vi.fn().mockRejectedValue(Error('Forbidden')),'old/c','CHAT','Conversation')).rejects.toThrow('Forbidden')
  })
  it.each([
    {...detail,conversationId:'other'}, {...detail,status:'ARCHIVED'}, {...detail,projectId:'p'},
  ])('rejects mismatched or inactive Chat details',async value=>{
    await expect(loadLinkedConversation(vi.fn().mockResolvedValue(value),'old/c','CHAT','Conversation')).rejects.toMatchObject({code:'CONVERSATION_LINK_UNAVAILABLE'})
  })
  it('validates both Project and root binding before selecting a linked conversation',async()=>{
    const request=vi.fn().mockResolvedValue({...detail,projectId:'p',projectDirectoryId:'d'})
    await expect(loadLinkedConversation(request,'old/c','PROJECT','Conversation',{projectId:'p',directoryId:'other'})).rejects.toMatchObject({status:404})
    await expect(loadLinkedConversation(request,'old/c','PROJECT','Conversation',{projectId:'p',directoryId:'d'})).resolves.toMatchObject({projectId:'p',projectDirectoryId:'d'})
  })
})
