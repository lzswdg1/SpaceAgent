// Real Agent page and inline grid; isolated fake public API transport only.
import { createRoot } from 'react-dom/client'
import { ApplicationShell } from '../src/app/ApplicationShell'
import { AgentPage } from '../src/features/agents/AgentPage'
import { emptyAgentDraft } from '../src/features/agents/agentApi'
import { messages, type Language } from '../src/copy'
import type { AuthenticatedRequest } from '../src/features/overview/types'
import type { RuntimeCapability } from '../src/features/agents/types'
import '../src/styles.css'

const params = new URLSearchParams(location.search), language = (params.get('language') || 'en') as Language
localStorage.setItem('spaceagent-theme', params.get('theme') || 'light')
const tx = (key: string) => messages[key]?.[['en','zh','ja'].indexOf(language)] || key
const checks: string[] = [], errors: string[] = [], writes: {path: string; body: any}[] = []
window.addEventListener('error', event => errors.push(event.message))
window.addEventListener('unhandledrejection', () => errors.push('unhandled rejection'))
const sleep = (ms = 25) => new Promise(resolve => setTimeout(resolve, ms))
const wait = async (condition: () => unknown) => { for (let i=0;i<100;i++) { if(condition())return;await sleep() } throw Error(`Timed out: ${condition}`) }
const assert = (condition: unknown, label: string) => { if(!condition)throw Error(label);checks.push(label) }
const tool = (id: string, extra: Partial<RuntimeCapability> = {}): RuntimeCapability => ({id,name:id,
  description:'A bounded operation with server-enforced permissions.',executionMode:'TEST',inputSchema:{},
  available:true,readOnly:true,requiresNetwork:false,requiresWorkspace:false,...extra})
const tools = [tool('echo'), tool('web_search',{requiresNetwork:true}), tool('file_read',{available:false,requiresWorkspace:true}),
  tool('coding_command',{readOnly:false,requiresWorkspace:true}), ...Array.from({length:25},(_,i)=>tool(`mcp_fixture_${i}`))]
let agent = {...emptyAgentDraft('Fixture Agent'),id:'a',ownerId:'u',status:'ACTIVE',revision:1,
  createdAt:'2026-09-15T00:00:00Z',updatedAt:'2026-09-15T00:00:00Z',networkEnabled:true,enabledToolIds:['echo','file_read']}
let writeMode = 'DIRECT'
const request: AuthenticatedRequest = async <T,>(path: string, init?: RequestInit): Promise<T> => {
  if(init?.method === 'PUT'){
    const body = JSON.parse(String(init.body));writes.push({path,body})
    if(writeMode === 'OWNER_APPROVAL_REQUIRED')return {outcome:'PENDING_APPROVAL',agent,changeRequest:null} as T
    agent = {...agent,...body};return agent as T
  }
  if(init?.method && init.method !== 'GET')throw Error('Unexpected mutation')
  if(path === '/api/v1/agents/organization')return [{agent,writeMode}] as T
  if(path === '/api/v1/tooling/capabilities')return {tools,skills:[],sandbox:{mode:'IN_PROCESS',isolation:'PROCESS_COMPATIBILITY',containerized:false,codingCommandAvailable:false}} as T
  if(['/api/v1/skills','/api/v1/model-pools','/api/v1/knowledge/documents','/api/v1/model-providers'].includes(path) || path.includes('/configuration-changes?'))return [] as T
  throw Error(`Unexpected route ${path}`)
}
const root=createRoot(document.getElementById('root')!),noop=()=>{}
const session:any={user:{userId:'u',tenantId:'t',username:'Fixture',displayName:'Fixture',tenantRole:'OWNER'},organizations:[]}
function mount(key: string){root.render(<ApplicationShell session={session} language={language} onLanguageChange={noop}
  onLogout={async()=>{}} onSwitchOrganization={async()=>session} activeSection="agents" onNavigate={noop} tx={tx}>
  <AgentPage key={key} request={request} userId="u" tenantRole="OWNER" tx={tx}/></ApplicationShell>)}
const picker=()=>document.querySelector<HTMLElement>('.tool-picker')!
const checkbox=(id:string)=>[...document.querySelectorAll<HTMLInputElement>('.tool-picker-option input')].find(input=>input.closest('label')?.querySelector('b')?.textContent===id)!
const change=(element:HTMLSelectElement,value:string)=>{element.value=value;element.dispatchEvent(new Event('change',{bubbles:true}))}
async function main(){
  mount('direct');await wait(()=>picker())
  assert(!document.querySelector('dialog'), 'no drawer or extra opening click')
  const grid = document.querySelector<HTMLElement>('.tool-picker-list')!
  const columns = getComputedStyle(grid).gridTemplateColumns.split(' ').length
  assert(columns >= (innerWidth > 700 ? 2 : 1), 'desktop tools occupy multiple columns')
  assert(checkbox('file_read').checked && !checkbox('file_read').disabled,'unavailable selected tool remains removable')
  checkbox('file_read').click();await sleep()
  assert(checkbox('file_read').disabled,'unavailable tool cannot be re-added')
  checkbox('web_search').click();await sleep()
  const search=document.querySelector<HTMLInputElement>('.tool-picker-controls input')!
  Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value')!.set!.call(search,'no such tool')
  search.dispatchEvent(new Event('input',{bubbles:true}));await sleep()
  assert(document.querySelector('.tool-picker-list')?.textContent===tx('toolPickerNoMatch'),'search empty state')
  Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value')!.set!.call(search,'')
  search.dispatchEvent(new Event('input',{bubbles:true}));await sleep()
  const filters=document.querySelectorAll<HTMLSelectElement>('.tool-picker-controls select')
  change(filters[0],'Network');await sleep()
  assert(document.querySelectorAll('.tool-picker-option').length===1,'category filters tools')
  change(filters[0],'All');change(filters[1],'Selected');await sleep()
  assert(document.querySelectorAll('.tool-picker-option').length===2,'selected filter preserves both draft selections')
  assert(writes.length===0,'selection does not automatically save')
  assert(document.querySelector('.tool-picker-chips')?.textContent?.includes('web_search'),'draft summary updates')
  const save=()=>[...document.querySelectorAll<HTMLButtonElement>('.agent-titlebar button')].find(b=>b.textContent===tx(writeMode==='DIRECT'?'saveDefinition':'submitAgentChange'))!
  save().click();await wait(()=>writes.length===1)
  assert(writes[0].path==='/api/v1/agents/a' && writes[0].body.enabledToolIds.join(',')==='echo,web_search','existing PUT saves exact selected IDs')
  writeMode='OWNER_APPROVAL_REQUIRED';mount('approval');await wait(()=>save());await sleep(80)
  save().click();await wait(()=>writes.length===2)
  await wait(()=>document.querySelector('.agent-save-notice')?.textContent===tx('agentChangePending'))
  assert(true,'OWNER approval result remains pending')
  writeMode='READ_ONLY';mount('readonly');await sleep(100)
  assert([...document.querySelectorAll<HTMLInputElement>('.tool-picker-option input')].every(input=>input.disabled),'read-only Agent cannot toggle tools')
  assert([...document.querySelectorAll<HTMLButtonElement>('.tool-picker-chips button')].every(b=>b.disabled),'read-only cannot remove chips')
  writeMode='DIRECT';mount('final');await sleep(100)
  const list=document.querySelector<HTMLElement>('.tool-picker-list')!
  assert(list.scrollHeight>list.clientHeight && getComputedStyle(list).overflowY==='auto','long catalog scrolls inside grid')
  const target = checkbox('coding_command')
  ;(target.closest('article')!.querySelector('.tool-picker-badges') as HTMLElement).click();await sleep()
  assert(target.checked,'clicking card metadata toggles its tool once')
  const summary = target.closest('article')!.querySelector('summary')!;summary.click();await sleep()
  assert(target.checked,'expanding details does not toggle selection')
  assert(document.documentElement.scrollWidth<=innerWidth,'no page horizontal overflow')
  assert(errors.length===0,'no browser errors')
  picker().scrollIntoView({block:'center'})
}
main().then(()=>{(window as any).__workflowRegression={done:true,ok:true,checks}})
  .catch(error=>{(window as any).__workflowRegression={done:true,ok:false,checks,error:String(error)}})
