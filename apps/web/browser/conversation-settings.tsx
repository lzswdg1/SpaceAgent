import {createRoot} from 'react-dom/client'
import {ApplicationShell} from '../src/app/ApplicationShell'
import {ChatPage} from '../src/features/chat/ChatPage'
import {ProjectPage} from '../src/features/projects/ProjectPage'
import type {AuthenticatedRequest} from '../src/features/chat/types'
import {messages,type Language} from '../src/copy'
import '../src/styles.css'

const params=new URLSearchParams(location.search),language=(params.get('language')||'en') as Language
localStorage.setItem('spaceagent-theme',params.get('theme')||'light')
localStorage.setItem('spaceagent-chat-inspector-collapsed','false')
localStorage.setItem('spaceagent-project-inspector-collapsed','false')
const tx=(key:string)=>messages[key]?.[['en','zh','ja'].indexOf(language)]||key
const root=createRoot(document.getElementById('root')!),noop=()=>{},pause=(ms=25)=>new Promise(resolve=>setTimeout(resolve,ms)),checks:string[]=[]
const assert=(value:unknown,label:string)=>{if(!value)throw Error(label);checks.push(label)}
const wait=async(test:()=>unknown)=>{for(let i=0;i<120;i++){if(test())return;await pause()}throw Error(`Timeout: ${test}`)}
const setValue=(input:HTMLInputElement|HTMLSelectElement,value:string)=>{
  Object.getOwnPropertyDescriptor(input instanceof HTMLSelectElement?HTMLSelectElement.prototype:HTMLInputElement.prototype,'value')!.set!.call(input,value)
  input.dispatchEvent(new Event(input instanceof HTMLSelectElement?'change':'input',{bubbles:true}))
}
const session:any={user:{userId:'u',username:'Fixture',tenantId:'t',tenantRole:'OWNER'},organizations:[{organization:{id:'t',name:'Workspace'},membership:{role:'OWNER'}}]}
const conversations:any={A:{id:'A',title:'Conversation A',agentId:'a',projectId:null,projectDirectoryId:null},B:{id:'B',title:'Conversation B',agentId:'a',projectId:null,projectDirectoryId:null},P:{id:'P',title:'Project conversation',agentId:'a',projectId:'p',projectDirectoryId:'d'}}
const summary=(id:string)=>({...conversations[id],userId:'u',status:'ACTIVE',createdAt:'2026-09-13T00:00:00Z',updatedAt:'2026-09-13T00:00:00Z'})
const detail=(id:string)=>({...summary(id),conversationId:id,startedAt:'2026-09-13T00:00:00Z',lastMessageAt:'2026-09-13T00:00:00Z',activeTaskId:null,messages:[],messagePage:1,messageSize:0,messageTotal:0})
const mutations:string[]=[];let delayed=false,release:(()=>void)|undefined,deny=false
const request:AuthenticatedRequest=async<T,>(path:string,options?:RequestInit)=>{
  await pause(25)
  const match=path.match(/^\/api\/v1\/chat\/conversations\/([^/]+)(?:\/(title|agent))?$/)
  if(options?.method){
    mutations.push(path)
    if(!match)throw Error('Unexpected mutation '+path)
    const id=match[1],body=JSON.parse(options.body as string)
    if(deny){deny=false;throw Error('Save rejected')}
    if(delayed){delayed=false;await new Promise<void>(resolve=>release=resolve)}
    if(match[2]==='title'){conversations[id].title=body.name;return summary(id) as T}
    conversations[id].agentId=body.agentId;return detail(id) as T
  }
  if(match)return detail(match[1]) as T
  if(path==='/api/v1/agents'||path.startsWith('/api/v1/agents?'))return [{id:'a',name:'Agent A',status:'ACTIVE'},{id:'b',name:'Agent B',status:'ACTIVE'}] as T
  if(path.includes('conversations?page'))return {items:(path.includes('scope=PROJECT')?['P']:['A','B']).map(summary),total:path.includes('scope=PROJECT')?1:2} as T
  if(path.includes('messages/page'))return {items:[],hasMore:false,beforeSequence:null} as T
  if(path.endsWith('/run'))return null as T
  if(path==='/api/v1/projects')return [{id:'p',name:'Project',status:'ACTIVE'}] as T
  if(path==='/api/v1/project-roots'||path.endsWith('/directories'))return [{id:'d',name:'Repository root',projectId:'p',sourceRepositoryId:null,rootKind:'EMPTY',storageState:'READY',state:'ACTIVE'}] as T
  if(path.includes('run-handoffs')||path.includes('intakes'))return {items:[]} as T
  return [] as T
}
const noStream=async()=>{throw Error('No inference in settings tests')}
function Shell({project=false}:{project?:boolean}){return <ApplicationShell session={session} language={language} onLanguageChange={noop} onLogout={async()=>{}} onSwitchOrganization={async()=>session} activeSection={project?'project':'chat'} onToggleNavigation={noop} onNavigate={noop} tx={tx}>
  {project?<ProjectPage language={language} request={request} openStream={noStream} navigationCollapsed={false} onToggleNavigation={noop} tx={tx}/>:<ChatPage language={language} request={request} openStream={noStream} navigationCollapsed={false} onToggleNavigation={noop} tx={tx}/>}
</ApplicationShell>}
const name=()=>document.querySelector<HTMLInputElement>('[name="conversationName"]')!
const save=()=>document.querySelector<HTMLFormElement>('.conversation-settings form')!.requestSubmit()
async function main(){
  history.replaceState({},'','?new=1')
  root.render(<Shell key="new-conversation"/>);await wait(()=>{
    const select=document.querySelector<HTMLSelectElement>('.conversation-settings select')
    return select?.value==='a'&&!select.disabled
  })
  const newConversationAgent=document.querySelector<HTMLSelectElement>('.conversation-settings select')!
  assert(newConversationAgent.value==='a','new Chat conversation defaults to the first active Agent')
  assert(document.querySelector('.chat-header-actions')?.textContent?.includes('Agent A'),'new Chat header shows its Agent before creation')
  setValue(newConversationAgent,'b')
  await wait(()=>document.querySelector('.chat-header-actions')?.textContent?.includes('Agent B'))
  assert(document.querySelector('.composer-route')?.textContent?.includes('Agent B'),'new Chat composer uses the explicit Agent selection')

  history.replaceState({},'','?conversation=A')
  root.render(<Shell key="existing-conversation"/>);await wait(()=>name()?.value==='Conversation A'&&!name().disabled)
  setValue(name(),'Renamed conversation');await pause();save()
  await wait(()=>document.querySelector('.chat-head-left b')?.textContent==='Renamed conversation')
  assert(document.querySelector('.conversation-list')?.textContent?.includes('Renamed conversation'),'Chat rename updates heading and sidebar')
  await wait(()=>!name().disabled)
  setValue(document.querySelector('.conversation-settings select')!,'b')
  await wait(()=>conversations.A.agentId==='b'&&!name().disabled)
  assert(document.querySelector('.chat-header-actions')?.textContent?.includes('Agent B'),'Agent choice updates the active conversation')
  assert(!mutations.some(path=>path.startsWith('/api/v1/agents/')),'conversation settings never mutate Agent definitions')
  const previousCard=document.querySelector('.conversation-settings')
  root.render(<Shell key="reload"/>);await wait(()=>document.querySelector('.conversation-settings')!==previousCard&&name()?.value==='Renamed conversation'&&!name().disabled)
  assert((document.querySelector('.conversation-settings select') as HTMLSelectElement).value==='b','settings restore from API after remount')
  delayed=true;setValue(name(),'Late A title');await pause();save();await wait(()=>release)
  ;([...document.querySelectorAll('.conversation-list button')].find(button=>button.textContent?.includes('Conversation B')) as HTMLElement).click()
  await wait(()=>name()?.value==='Conversation B')
  release!();await pause(100)
  assert(name().value==='Conversation B','late A save does not overwrite B form')
  deny=true;setValue(name(),'Unaccepted name');await pause();save()
  await wait(()=>document.querySelector('.conversation-settings [role=alert]'))
  assert(document.querySelector('.chat-head-left b')?.textContent==='Conversation B','failed save does not rename the conversation locally')
  assert(name().value==='Unaccepted name','failed save preserves the editable draft')

  history.replaceState({},'','?conversation=P&project=p&directory=d')
  root.render(<Shell key="project" project/>);await wait(()=>name()?.value==='Project conversation'&&!name().disabled)
  assert(document.querySelector('.project-inspector')?.firstElementChild?.classList.contains('conversation-settings'),'Project settings are visible above environment information')
  assert(document.querySelectorAll('.project-inspector .conversation-settings select').length===1&&!document.querySelector('.project-agent-control'),'Project has only one conversation Agent selector')
  setValue(name(),'Repository analysis');await pause();save()
  await wait(()=>document.querySelector('.project-head-left b')?.textContent==='Repository analysis')
  assert(document.querySelector('.project-list')?.textContent?.includes('Repository analysis'),'Project rename updates its conversation list')
  assert(document.querySelector('.conversation-root-summary')?.textContent?.includes('Repository root'),'conversation rename retains the root identity')
  assert(!document.querySelector('.conversation-settings textarea')&&!document.querySelector('.conversation-settings [name="temperature"]'),'no duplicated prompt or model-parameter editor')
  assert(document.querySelector('.conversation-settings a')?.getAttribute('href')==='/app/agents','definition editing links to the existing Agent page')
  conversations.P.agentId='missing-agent'
  const previousProjectCard=document.querySelector('.conversation-settings')
  root.render(<Shell key="missing-agent" project/>);await wait(()=>document.querySelector('.conversation-settings')!==previousProjectCard&&name()?.value==='Repository analysis'&&!name().disabled)
  assert(document.querySelector('.project-header-actions')?.textContent?.includes(tx('noAgent')),'missing bound Agent is not silently replaced with the first Agent')
  setValue(document.querySelector('.conversation-settings select')!,'b')
  await wait(()=>document.querySelector('.project-header-actions')?.textContent?.includes('Agent B')&&!name().disabled)
  assert(conversations.P.agentId==='b','explicit Project Agent selection repairs a missing binding')
  assert(document.documentElement.scrollWidth<=innerWidth,'right-side settings remain within the viewport')
  if(innerWidth<=1050){
    const inspector=document.querySelector('.project-inspector')!,rect=inspector.getBoundingClientRect()
    assert(rect.width>=250&&rect.left>=0&&rect.right<=innerWidth+1,'mobile inspector has usable width, not a clipped zero-width grid track')
    const close=document.querySelector<HTMLButtonElement>('.conversation-settings-close')!
    assert(getComputedStyle(close).display!=='none','mobile inspector has a reachable close action')
  }
  ;(window as any).__workflowRegression={done:true,ok:true,checks,width:innerWidth,language}
}
main().catch(error=>{(window as any).__workflowRegression={done:true,ok:false,checks,error:String(error.stack)}})
