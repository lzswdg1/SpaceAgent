import {useState} from 'react'
import {createRoot} from 'react-dom/client'
import {ApplicationShell} from '../src/app/ApplicationShell'
import {ChatPage} from '../src/features/chat/ChatPage'
import {ProjectPage} from '../src/features/projects/ProjectPage'
import type {AuthenticatedRequest} from '../src/features/chat/types'
import {messages,type Language} from '../src/copy'
import '../src/styles.css'

const params=new URLSearchParams(location.search),language=(params.get('language')||'en') as Language
localStorage.setItem('spaceagent-theme',params.get('theme')||'light')
localStorage.setItem('spaceagent-project-tree-collapsed','false')
const tx=(key:string)=>messages[key]?.[['en','zh','ja'].indexOf(language)]||key
const noop=()=>{},pause=(ms=20)=>new Promise(resolve=>setTimeout(resolve,ms)),checks:string[]=[]
const assert=(test:unknown,label:string)=>{if(!test)throw Error(label);checks.push(label)}
const wait=async(test:()=>unknown)=>{for(let i=0;i<120;i++){if(test())return;await pause()}throw Error(`Timeout: ${test}`)}
const request:AuthenticatedRequest=async<T,>(path:string)=>{
  await pause(30)
  const project={id:'p',name:'Project',status:'ACTIVE'}
  const root={id:'d',name:'Repository',projectId:'p',sourceRepositoryId:null,state:'ACTIVE',rootKind:'EMPTY',storageState:'READY'}
  const row={id:path.includes('scope=PROJECT')?'project-c':'chat-c',agentId:'a',userId:'u',title:path.includes('scope=PROJECT')?'Repository conversation':'Chat conversation',status:'ACTIVE',createdAt:'2026-09-13T00:00:00Z',updatedAt:'2026-09-13T00:00:00Z',projectId:path.includes('scope=PROJECT')?'p':null,projectDirectoryId:path.includes('scope=PROJECT')?'d':null}
  if(path==='/api/v1/projects')return [project] as T
  if(path==='/api/v1/project-roots'||path.endsWith('/directories'))return [root] as T
  if(path==='/api/v1/agents'||path.startsWith('/api/v1/agents?'))return [{id:'a',name:'Agent',status:'ACTIVE'}] as T
  if(path.includes('conversations?page'))return {items:[row],total:1} as T
  if(path.endsWith('/run'))return null as T
  if(path.includes('messages/page'))return {items:[],hasMore:false,beforeSequence:null} as T
  if(path.includes('run-handoffs')||path.includes('intakes'))return {items:[]} as T
  return [] as T
}
const session:any={user:{userId:'u',username:'Fixture',tenantId:'t',tenantRole:'OWNER'},organizations:[{organization:{id:'t',name:'Workspace'},membership:{role:'OWNER'}}]}
const noStream=async()=>{throw Error('No model calls in navigation tests')}
function App(){
  const [section,setSection]=useState<'chat'|'project'>('chat')
  return <ApplicationShell session={session} language={language} onLanguageChange={noop} onLogout={async()=>{}} onSwitchOrganization={async()=>session} activeSection={section} onToggleNavigation={noop} onNavigate={path=>setSection(path==='/app/project'?'project':'chat')} tx={tx}>
    {section==='chat'?<ChatPage language={language} request={request} openStream={noStream} navigationCollapsed={false} onToggleNavigation={noop} tx={tx}/>:<ProjectPage language={language} request={request} openStream={noStream} navigationCollapsed={false} onToggleNavigation={noop} tx={tx}/>}
  </ApplicationShell>
}
createRoot(document.getElementById('root')!).render(<App/> )
async function main(){
  await wait(()=>document.querySelector('.workspace-navigation-view.is-ready .conversation-list button'))
  await pause(260)
  if(innerWidth<=700){(document.querySelector('.platform-navigation-toggle') as HTMLElement).click();await pause(700)}
  const target=document.querySelector('.workspace-navigation-target')!
  const primary=document.querySelector('.primary-navigation')!,slot=document.querySelector('.workspace-navigation-slot')!
  const positions=[...primary.querySelectorAll('a')].map(link=>link.getBoundingClientRect().top)
  assert(!primary.contains(slot)&&Boolean(primary.compareDocumentPosition(slot)&Node.DOCUMENT_POSITION_FOLLOWING),'details follow all primary navigation links')
  assert(Boolean(slot.compareDocumentPosition(document.querySelector('.app-sidebar>footer')!)&Node.DOCUMENT_POSITION_FOLLOWING),'details remain above the account footer')
  assert(slot.getBoundingClientRect().top>=primary.getBoundingClientRect().bottom-1,'detail region starts below the navigation menu')
  ;(document.querySelector('a[href="/app/project"]') as HTMLElement).click()
  await wait(()=>document.querySelector('.workspace-navigation-view.is-ready .project-tree'))
  const projectView=document.querySelector('.workspace-navigation-view')!
  assert(projectView.getAnimations().some(animation=>animation.playState==='running'),'Project list enters with a real animation after loading')
  assert(getComputedStyle(projectView).filter==='none','list entrance uses no blur')
  assert(document.querySelector('.workspace-navigation-target')===target,'portal host stays stable during route change')
  await pause(260)
  if(innerWidth>700)assert([...primary.querySelectorAll('a')].every((link,i)=>Math.abs(link.getBoundingClientRect().top-positions[i])<1),'primary navigation is not displaced by Project details')
  ;(document.querySelector('a[href="/app/chat"]') as HTMLElement).click()
  await wait(()=>document.querySelector('.workspace-navigation-view.is-ready .conversation-list button'))
  const chatView=document.querySelector('.workspace-navigation-view')!
  assert(chatView.getAnimations().some(animation=>animation.playState==='running'),'Chat list enters with a real animation after loading')
  await pause(280)
  const existingAnimations=new Set(chatView.getAnimations())
  const composer=document.querySelector<HTMLTextAreaElement>('.chat-composer textarea')!
  Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype,'value')!.set!.call(composer,'keep this draft')
  composer.dispatchEvent(new Event('input',{bubbles:true}));await pause(100)
  assert(document.querySelector('.workspace-navigation-view')===chatView&&chatView.getAnimations().every(animation=>existingAnimations.has(animation)),'ordinary updates preserve navigation DOM and do not replay transitions')
  assert(composer.value==='keep this draft','ordinary updates preserve the composer draft')
  assert(document.documentElement.scrollWidth<=innerWidth,'navigation remains bounded at this viewport')
  if(innerWidth<=700){(document.querySelector('.platform-navigation-toggle') as HTMLElement).click();await pause(700)}
  const scroller=document.querySelector('.sidebar-scroll')!;scroller.scrollTop=scroller.scrollHeight
  ;(window as any).__workflowRegression={done:true,ok:true,checks,width:innerWidth,language}
}
main().catch(error=>{(window as any).__workflowRegression={done:true,ok:false,checks,error:String(error.stack)}})
