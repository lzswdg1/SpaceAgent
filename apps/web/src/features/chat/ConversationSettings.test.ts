import {createElement} from 'react'
import {renderToStaticMarkup} from 'react-dom/server'
import {describe,it,expect,vi} from 'vitest'
import {ConversationSettings} from './ConversationSettings'
import type {AuthenticatedRequest} from './types'
const props={request:vi.fn() as unknown as AuthenticatedRequest,
  conversation:{id:'c',title:'My conversation',agentId:'a',status:'ACTIVE'},
  agents:[{id:'a',name:'Agent A',status:'ACTIVE',description:null,modelPoolId:null,modelProviderId:null,modelId:null,maxTurns:null}],
  locked:false,tx:(key:string)=>key,onUpdated:vi.fn(),onBusyChange:vi.fn(),onDelete:vi.fn()}
describe('conversation-only settings',()=>{
  it('exposes name and Agent selection, not a duplicate Agent definition editor',()=>{
    const html=renderToStaticMarkup(createElement(ConversationSettings,props))
    expect(html).toContain('name="conversationName"')
    expect(html).toContain('maxLength="200"')
    expect(html).toContain('aria-label="conversationAgent"')
    expect(html).toContain('href="/app/agents"')
    for(const field of ['systemPrompt','temperature','maxTokens','knowledgeBaseIds','enabledToolIds'])expect(html).not.toContain(`name="${field}"`)
  })
  it('locks settings during execution and shows an honest empty state without a conversation',()=>{
    expect(renderToStaticMarkup(createElement(ConversationSettings,{...props,locked:true}))).toContain('conversationSettingsLocked')
    const empty=renderToStaticMarkup(createElement(ConversationSettings,{...props,conversation:null,
      newConversationAgentId:'a',onNewConversationAgentChange:vi.fn()}))
    expect(empty).toContain('selectConversationToConfigure')
    expect(empty).not.toContain('<input')
    expect(empty).toContain('aria-label="conversationAgent"')
    expect(empty).toContain('<option value="a" selected="">Agent A</option>')
  })
})
