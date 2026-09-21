import {useState, type ReactNode} from 'react'
import {createRoot} from 'react-dom/client'
import {ApplicationShell} from '../src/app/ApplicationShell'
import {WorkspaceNavigation} from '../src/app/WorkspaceNavigation'
import {OverviewPage} from '../src/features/overview/OverviewPage'
import {ProjectNavigationTree} from '../src/features/projects/ProjectNavigationTree'
import {ProjectPage} from '../src/features/projects/ProjectPage'
import {ChatPage} from '../src/features/chat/ChatPage'
import {messages, type Language} from '../src/copy'
import type {AuthenticatedRequest, ConversationSummary} from '../src/features/chat/types'
import '../src/styles.css'
import '../src/features/projects/project.css'

const settings=new URLSearchParams(location.search),language=(settings.get('language')||'en') as Language
localStorage.setItem('spaceagent-theme',settings.get('theme')||'light')
localStorage.setItem('spaceagent-project-tree-collapsed','false')
const tx=(key:string)=>messages[key]?.[['en','zh','ja'].indexOf(language)]||key
const root=createRoot(document.getElementById('root')!),checks:string[]=[]
const sleep=(ms=25)=>new Promise(resolve=>setTimeout(resolve,ms))
const wait=async(condition:()=>unknown)=>{for(let i=0;i<100;i++){if(condition())return;await sleep()}throw Error(`Timed out: ${condition}`)}
const assert=(condition:unknown,label:string)=>{if(!condition)throw Error(label);checks.push(label)}
const button=(label:string,scope:ParentNode=document)=>[...scope.querySelectorAll<HTMLButtonElement>('button')].find(b=>b.textContent?.trim()===label)!
const noop=()=>{}
const session:any={user:{userId:'u',tenantId:'t',username:'Fixture',displayName:'Fixture',tenantRole:'OWNER'},organizations:[{organization:{id:'t',name:'Test workspace'},membership:{role:'OWNER'}}]}
function Shell({children,section='chat'}:{children:ReactNode;section?:'overview'|'chat'|'project'}) {
  const [collapsed,setCollapsed]=useState(false)
  return <ApplicationShell session={session} language={language} onLanguageChange={noop} onLogout={async()=>{}} onSwitchOrganization={async()=>session} activeSection={section} sidebarCollapsed={collapsed} onToggleNavigation={()=>setCollapsed(value=>!value)} onNavigate={noop} tx={tx}>{children}</ApplicationShell>
}
const mount=async(element:ReactNode)=>{root.render(element);await sleep(80)}
const setInput=(element:HTMLTextAreaElement,value:string)=>{Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype,'value')!.set!.call(element,value);element.dispatchEvent(new Event('input',{bubbles:true}))}
const row=(id:string):ConversationSummary=>({id,agentId:'a',title:`Conversation ${id}`,status:'ACTIVE',createdAt:'2026-09-13T00:00:00Z',updatedAt:'2026-09-13T00:00:00Z'})
const detail=(id:string)=>({conversationId:id,agentId:'a',userId:'u',projectId:null,projectDirectoryId:null,activeTaskId:null,status:'ACTIVE',startedAt:'2026-09-13T00:00:00Z',lastMessageAt:'2026-09-13T00:00:00Z',messages:[],messagePage:1,messageSize:0,messageTotal:0})
const agent={id:'a',name:'Fixture Agent',status:'ACTIVE',modelId:'fixture-model',modelPoolId:null}
const page=(id:string)=>({items:[{id:`m-${id}`,role:'ASSISTANT',content:`History for ${id}`,sequence:0,createdAt:'2026-09-13T00:00:00Z'}],hasMore:false,beforeSequence:null})
const noStream=async()=>{throw Error('Unexpected inference')}

async function verifyManualStreaming(wire:ReadableStreamDefaultController<Uint8Array>, selector:string, name:string) {
  const log=document.querySelector<HTMLDivElement>(selector)!
  const transcript=log.querySelector('.conversation-transcript')!
  const user=transcript.querySelector<HTMLElement>('article.user:last-of-type') || transcript.querySelector<HTMLElement>('article.user')!
  // History is windowed: inspect geometry only after an explicit reader scroll
  // brings this message into view, rather than requiring all offscreen DOM.
  if (!user.querySelector('.chat-message-content,.project-message-content')) {
    log.scrollTop = user.offsetTop - log.offsetTop
    await wait(()=>user.querySelector('.chat-message-content,.project-message-content'))
    await sleep(100)
  }
  const bubble=user.querySelector<HTMLElement>('.chat-message-content,.project-message-content')!
  const bodyRect=transcript.getBoundingClientRect(),bubbleRect=bubble.getBoundingClientRect()
  assert(Math.abs(bubbleRect.right-bodyRect.right)<2,`${name}: user bubble is right-aligned`)
  assert(bubbleRect.width<160&&bubbleRect.width<bodyRect.width*.6,`${name}: short message shrinks to content`)
  assert(getComputedStyle(user).borderTopWidth==='0px'&&getComputedStyle(user).backgroundColor==='rgba(0, 0, 0, 0)',`${name}: no outer message card`)
  assert(getComputedStyle(user.querySelector(':scope > span')!).display==='none',`${name}: no visible raw USER label`)
  assert(parseFloat(getComputedStyle(bubble).borderTopLeftRadius)>=20,`${name}: rounded message bubble`)
  assert(Math.abs(bodyRect.left+bodyRect.width/2-(log.getBoundingClientRect().left+log.clientWidth/2))<8,`${name}: transcript is centered`)
  const append=async(label:string)=>{
    const content='\n\n'+Array.from({length:25},(_,i)=>`${label} ${i+1}. Streaming text continues here. 内容继续输出，但不会改变阅读位置。`).join('\n\n')
    wire.enqueue(new TextEncoder().encode(`event: delta\ndata: ${JSON.stringify({content})}\n\n`))
    await wait(()=>log.textContent?.includes(label));await sleep(130)
  }
  const initial=log.scrollTop
  await append(`${name}-first`)
  assert(Math.abs(log.scrollTop-initial)<2,`${name}: initial stream growth does not scroll`)
  log.scrollTop=log.scrollHeight;await sleep();const bottom=log.scrollTop
  await append(`${name}-bottom`)
  assert(Math.abs(log.scrollTop-bottom)<2,`${name}: streaming never follows even at the bottom`)
  log.scrollTop=45;await sleep();const reading=log.scrollTop
  await append(`${name}-reading`)
  assert(Math.abs(log.scrollTop-reading)<2,`${name}: manual reading position stays fixed`)
  await wait(()=>document.querySelector<HTMLButtonElement>('.message-jump-latest'))
  document.querySelector<HTMLButtonElement>('.message-jump-latest')!.click();await sleep()
  assert(log.scrollHeight-log.scrollTop-log.clientHeight<2,`${name}: explicit latest button scrolls once`)
  const afterJump=log.scrollTop
  await append(`${name}-after-jump`)
  assert(Math.abs(log.scrollTop-afterJump)<2,`${name}: explicit jump does not enable auto-follow`)
}

async function main(){
  const requests:string[]=[]
  const overviewRequest:AuthenticatedRequest=async<T,>(path:string)=>{
    requests.push(path);await sleep(80)
    return (path.includes('chat/conversations')?{items:[],total:0}:path.includes('/monitoring/overview')?{overview:{totalAgents:1,totalSessions:30,totalInputTokens:100,totalOutputTokens:20,totalCacheReadTokens:0,totalCacheCreateTokens:0,totalCostUsd:1},timeseries:[]}:path.includes('/usage')?{rows:[]}:path.includes('/sessions')?{sessions:[],total:30,pageSize:10}:{}) as T
  }
  await mount(<Shell key="overview" section="overview"><OverviewPage language={language} request={overviewRequest} onNavigate={noop} tx={tx}/></Shell>)
  await wait(()=>button(`${tx('nextPage')} →`)&&!button(`${tx('nextPage')} →`).disabled)
  ;(document.querySelector('.overview-management') as HTMLDetailsElement).open=true
  button(`${tx('nextPage')} →`).click();await wait(()=>document.querySelector('.session-pagination span')?.textContent==='2 / 3');await sleep(120)
  assert(document.querySelector('.session-pagination span')?.textContent==='2 / 3','page 2 survives delayed loading')
  button(`${tx('nextPage')} →`).click();await wait(()=>document.querySelector('.session-pagination span')?.textContent==='3 / 3');await sleep(120)
  assert(document.querySelector('.session-pagination span')?.textContent==='3 / 3','page 3 remains reachable')
  if(innerWidth>700){(document.querySelector('.platform-navigation-toggle') as HTMLElement).click();await sleep();assert(parseFloat(getComputedStyle(document.querySelector('.app-frame')!).gridTemplateColumns)===0,'collapsed sidebar releases its column')}

  const dirs=Array.from({length:3},(_,i)=>({id:`d${i}`,projectId:'p',name:`Root ${i}`,state:'ACTIVE'}))
  const treeProps:any={projects:[{id:'p',status:'ACTIVE'}],directories:dirs,conversations:dirs.flatMap(d=>Array.from({length:3},(_,i)=>({conversationId:`${d.id}c${i}`,projectId:'p',projectDirectoryId:d.id,title:`Conversation ${d.id}/${i}`}))),readySources:[],projectId:'p',directoryId:'d0',conversationId:'d0c0',loading:false,creatingProject:false,creatingDirectory:false,tx,onToggleProjectCreate:noop,onCreateProject:noop,onSelectProject:noop,onSelectDirectory:noop,onSelectConversation:noop,onCreateConversation:noop,onDeleteConversation:noop,onArchiveDirectory:noop,onToggleDirectoryCreate:noop,onCreateDirectory:noop}
  await mount(<Shell key="tree" section="project"><WorkspaceNavigation><ProjectNavigationTree {...treeProps}/></WorkspaceNavigation><p>Project</p></Shell>)
  if(innerWidth<=700){(document.querySelector('.platform-navigation-toggle') as HTMLElement).click();await sleep()}
  const tree=document.querySelector('.project-tree')!,list=document.querySelector('.project-list')!
  assert(getComputedStyle(tree).display!=='none'&&list.clientHeight>62,'mobile/desktop Project roots are visible without old 62px clipping')
  assert(document.querySelectorAll('.platform-tree-name').length===12,'all root and conversation rows stay mounted')

  history.replaceState({},'','?conversation=old')
  const chatRequest:AuthenticatedRequest=async<T,>(path:string)=>{
    if(path==='/api/v1/agents'||path.startsWith('/api/v1/agents?'))return [agent] as T
    if(path.includes('messages/page'))return page(path.includes('/old/')?'old':'A') as T
    if(path.endsWith('/run'))return null as T
    if(path.includes('conversations?page'))return {items:[row('A'),row('B')],total:102} as T
    if(path==='/api/v1/chat/conversations/old')return detail('old') as T
    if(path==='/api/v1/chat/conversations/A')return detail('A') as T
    throw Error(`Unexpected request: ${path}`)
  }
  await mount(<Shell key="old"><ChatPage language={language} request={chatRequest} openStream={noStream} navigationCollapsed={false} onToggleNavigation={noop} tx={tx}/></Shell>)
  await wait(()=>document.querySelector('.message-log')?.textContent?.includes('History for old'))
  assert(document.querySelector('.conversation-list>button.active')?.textContent?.includes(tx('linkedConversationTitle')),'old deep link is selected outside page 1')
  assert(getComputedStyle(document.querySelector('.conversation-rail')!).display!=='none','Chat history remains available on mobile')
  assert(!document.querySelector('.conversation-rail>header')&&!document.querySelector('.conversation-search'), 'Chat sidebar omits the workbench heading, plus and search block')

  history.replaceState({},'','?conversation=missing')
  await mount(<Shell key="missing"><ChatPage language={language} request={chatRequest} openStream={noStream} navigationCollapsed={false} onToggleNavigation={noop} tx={tx}/></Shell>)
  await wait(()=>document.querySelector('.chat-error'))
  assert(!document.querySelector('.conversation-list>button.active')&&(document.querySelector('.chat-composer textarea') as HTMLTextAreaElement).disabled,'unavailable deep link never falls back or enables send')

  history.replaceState({},'','?conversation=A');let wire:ReadableStreamDefaultController<Uint8Array>,streamCalls=0
  const mutations:string[]=[]
  const streamingRequest:AuthenticatedRequest=async<T,>(path:string,options?:RequestInit)=>{
    if(options?.method==='POST'){mutations.push(path);return {} as T}
    return chatRequest<T>(path,options)
  }
  const openStream=async()=>{streamCalls++;return new Response(new ReadableStream<Uint8Array>({start(controller){wire=controller;controller.enqueue(new TextEncoder().encode('event: run_accepted\ndata: {"agentRunId":"run-A","conversationId":"A"}\n\nevent: delta\ndata: {"content":"Live A"}\n\n'))}}),{headers:{'Content-Type':'text/event-stream'}})}
  await mount(<Shell key="stream"><ChatPage language={language} request={streamingRequest} openStream={openStream} navigationCollapsed={false} onToggleNavigation={noop} tx={tx}/></Shell>)
  await wait(()=>document.querySelector('.chat-head-left b')?.textContent==='Conversation A');await sleep(60)
  const composer=document.querySelector('textarea')!;setInput(composer,'中文日本語');await sleep()
  composer.dispatchEvent(new KeyboardEvent('keydown',{key:'Enter',isComposing:true,bubbles:true,cancelable:true}));await sleep()
  assert(streamCalls===0,'composition Enter does not send')
  composer.dispatchEvent(new KeyboardEvent('keydown',{key:'Enter',shiftKey:true,bubbles:true,cancelable:true}));await sleep()
  assert(streamCalls===0,'Shift Enter does not send')
  composer.dispatchEvent(new KeyboardEvent('keydown',{key:'Enter',bubbles:true,cancelable:true}));await wait(()=>button(tx('stop'))&&!button(tx('stop')).disabled)
  const other=[...document.querySelectorAll<HTMLButtonElement>('.conversation-list>button')].find(b=>b.textContent?.includes('Conversation B'))!;other.click();await sleep()
  assert(other.disabled&&document.querySelector('.chat-head-left b')?.textContent==='Conversation A','conversation selection is locked during the active stream')
  await verifyManualStreaming(wire!,'.message-log','Chat')
  button(tx('stop')).click();await wait(()=>mutations.length>0)
  assert(mutations.length===1&&mutations[0]==='/api/v1/chat/runs/run-A/cancel','stop uses the admitted Run, not a selected or inferred Run')
  wire!.close();await wait(()=>!button(tx('stop')))
  assert(!other.disabled,'conversation selection unlocks after stopping')

  history.replaceState({},'','?conversation=project-old&project=p&directory=d')
  let projectWire:ReadableStreamDefaultController<Uint8Array>
  const projectStream=async()=>new Response(new ReadableStream<Uint8Array>({start(controller){projectWire=controller;controller.enqueue(new TextEncoder().encode('event: run_accepted\ndata: {"agentRunId":"project-run","conversationId":"project-old"}\n\nevent: delta\ndata: {"content":"I can help review this project."}\n\n'))}}),{headers:{'Content-Type':'text/event-stream'}})
  const projectRequest:AuthenticatedRequest=async<T,>(path:string)=>{
    const directory={id:'d',projectId:'p',name:'Repository',state:'ACTIVE',sourceRepositoryId:null,rootKind:'EMPTY',storageState:'READY',relativePath:''}
    if(path==='/api/v1/projects')return [{id:'p',name:'Project',status:'ACTIVE'}] as T
    if(path==='/api/v1/project-roots'||path.endsWith('/directories'))return [directory] as T
    if(path==='/api/v1/agents'||path.startsWith('/api/v1/agents?'))return [agent] as T
    if(path.includes('conversations?page'))return {items:[],total:101} as T
    if(path==='/api/v1/chat/conversations/project-old')return {...detail('project-old'),projectId:'p',projectDirectoryId:'d'} as T
    if(path.includes('messages/page'))return page('project-old') as T
    if(path.endsWith('/run'))return null as T
    if(path.includes('run-handoffs')||path.includes('intakes'))return {items:[]} as T
    return [] as T
  }
  await mount(<Shell key="project-link" section="project"><ProjectPage language={language} request={projectRequest} openStream={projectStream} navigationCollapsed={false} onToggleNavigation={noop} tx={tx}/></Shell>)
  await wait(()=>document.querySelector('.project-message-log')?.textContent?.includes('History for project-old'))
  assert(Boolean(document.querySelector('.project-tree-conversation.active')),'Project deep link loads the exact root and older conversation')
  await sleep(80)
  const projectComposer=document.querySelector<HTMLTextAreaElement>('.project-composer textarea')!
  setInput(projectComposer,'?');await sleep()
  projectComposer.form!.requestSubmit();await wait(()=>button(tx('stop'))&&!button(tx('stop')).disabled)
  await verifyManualStreaming(projectWire!,'.project-message-log','Project')
  document.querySelector('.project-message-log')!.scrollTop=0
  assert(document.documentElement.scrollWidth<=innerWidth,'no document-level horizontal overflow')
  ;(window as any).__workflowRegression={done:true,ok:true,checks,width:innerWidth,language}
}
main().catch(error=>{(window as any).__workflowRegression={done:true,ok:false,checks,error:String(error.stack)}})
