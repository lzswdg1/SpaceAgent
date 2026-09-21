import {describe,it,expect,vi} from 'vitest'
import {selectAgentModelPool} from './agentBinding'
import {emptyAgentDraft,updateAgent} from './agentApi'
import type {AuthenticatedRequest} from '../chat/types'
describe('model binding changes',()=>{
  it('clears mutually exclusive direct fields when selecting a pool and clears the pool on return',async()=>{
    const initial={...emptyAgentDraft('A'),modelId:'direct',modelProviderId:'provider'}
    const pooled=selectAgentModelPool(initial,'pool')
    expect(pooled).toMatchObject({modelPoolId:'pool',modelProviderId:null,modelId:null})
    expect(selectAgentModelPool(pooled,null)).toMatchObject({modelPoolId:null,modelProviderId:null,modelId:null})
    const request=vi.fn(async()=>({id:'A'})) as unknown as AuthenticatedRequest
    await updateAgent(request,'A',{...initial,modelPoolId:'pool'})
    const init=(request as ReturnType<typeof vi.fn>).mock.calls[0][1]
    expect(JSON.parse(init.body)).toMatchObject({modelPoolId:'pool',modelProviderId:null,modelId:null})
  })
})
