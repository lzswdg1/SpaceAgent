// Deterministic real components with an injected fake registry. Never calls live APIs.
import { useState } from 'react'
import { createRoot } from 'react-dom/client'
import { ApplicationShell } from '../src/app/ApplicationShell'
import { AgentSkills } from '../src/features/agents/AgentSkills'
import { SkillEvidence } from '../src/features/tracing/SkillEvidence'
import { messages, type Language } from '../src/copy'
import type { Skill } from '../src/features/agents/skillApi'
import type { AuthenticatedRequest } from '../src/features/overview/types'
import '../src/styles.css'
import '../src/features/agents/agent.css'

const params = new URLSearchParams(location.search), language = (params.get('language') || 'en') as Language
localStorage.setItem('spaceagent-theme', params.get('theme') || 'light')
const tx = (key: string) => messages[key]?.[['en','zh','ja'].indexOf(language)] || key
const checks: string[] = [], calls: string[] = [], errors: string[] = []
window.addEventListener('error', event => errors.push(event.message))
window.addEventListener('unhandledrejection', () => errors.push('unhandled rejection'))
const sleep = (ms=25) => new Promise(resolve=>setTimeout(resolve,ms))
const wait = async (condition:()=>unknown) => {for(let i=0;i<100;i++){if(condition())return;await sleep()}throw Error(`Timed out: ${condition}`)}
const assert = (condition:unknown,label:string) => {if(!condition)throw Error(label);checks.push(label)}
const button = (key:string) => [...document.querySelectorAll<HTMLButtonElement>('button')].find(b=>b.textContent===tx(key))!
const fill = (element:HTMLInputElement|HTMLTextAreaElement,value:string) => {
  Object.getOwnPropertyDescriptor(element instanceof HTMLTextAreaElement?HTMLTextAreaElement.prototype:HTMLInputElement.prototype,'value')!.set!.call(element,value)
  element.dispatchEvent(new Event('input',{bubbles:true}))
}
let skills:Skill[]=[],publishAttempts=0, failPublish=true
const request:AuthenticatedRequest = async<T,>(path:string,init?:RequestInit):Promise<T> => {
  calls.push(`${init?.method || 'GET'} ${path}`);await sleep(15)
  if(path.includes('skill-evidence'))return {state:'CONTEXT_PREPARED',preparedAt:'2026-09-15T00:00:00Z',skills:[{skillVersionId:'v1',configHash:'a'.repeat(64)}]} as T
  if(path==='/api/v1/skills' && init?.method==='POST'){
    const body=JSON.parse(String(init.body))
    skills=[{...body,id:'s1',ownerUserId:'owner',lifecycle:'ACTIVE',currentVersionId:null,versions:[{...body,id:'v1',skillId:'s1',versionNumber:1,status:'DRAFT',configHash:'a'.repeat(64)}]}]
    return structuredClone(skills[0]) as T
  }
  if(path.endsWith('/publish')){
    publishAttempts++;skills[0].currentVersionId='v1';skills[0].versions[0].status='PUBLISHED'
    if(failPublish){failPublish=false;throw Error('simulated lost response')}
    return structuredClone(skills[0]) as T
  }
  if(path==='/api/v1/skills')return structuredClone(skills) as T
  if(path==='/api/v1/skills/s1')return structuredClone(skills[0]) as T
  throw Error(`Unexpected ${path}`)
}
const tool={id:'http_fetch',name:'HTTP Fetch',description:'Read HTTPS',executionMode:'BOUNDED_HTTP',available:true,readOnly:true,requiresNetwork:true,requiresWorkspace:false,inputSchema:{}}
const session:any={user:{userId:'owner',tenantId:'t',displayName:'Fixture',username:'Fixture',tenantRole:'OWNER'},organizations:[]}
const noop=()=>{}
function Fixture(){
  const [selected,setSelected]=useState<string[]>([]),[enabled,setEnabled]=useState(false),[readonly,setReadonly]=useState(false)
  return <ApplicationShell session={session} language={language} onLanguageChange={noop} onLogout={async()=>{}}
    onSwitchOrganization={async()=>session} activeSection="agents" onNavigate={noop} tx={tx}>
    <div className="agent-card"><button data-enable onClick={()=>setEnabled(true)}>Enable required Tool (fixture)</button>
      <button data-readonly onClick={()=>setReadonly(true)}>Readonly (fixture)</button>
      <AgentSkills request={request} userId="owner" selected={selected} enabledTools={enabled?['http_fetch']:[]} tools={[tool]}
        disabled={readonly} onChange={setSelected} tx={tx}/><output>{selected.join(',')}</output>
      <SkillEvidence runId="run1" request={request} tx={tx}/></div>
  </ApplicationShell>
}
createRoot(document.getElementById('root')!).render(<Fixture/> )
async function main(){
  await wait(()=>button('skillCreate') && !document.querySelector('.agent-skills [role=status]'))
  assert(document.querySelector('.agent-skills')?.textContent?.includes(tx('skillNoRegistered')),'real registry empty state')
  button('skillCreate').click();await sleep()
  fill(document.querySelector<HTMLInputElement>('.skill-editor input')!,'Evidence practice');await sleep()
  const upload=document.querySelector<HTMLInputElement>('.skill-editor input[type=file]')!
  const invalid=new DataTransfer();invalid.items.add(new File([new Uint8Array([255,255])],'invalid.md'))
  upload.files=invalid.files;upload.dispatchEvent(new Event('change',{bubbles:true}));await sleep(50)
  assert(document.querySelector('.agent-skills')?.textContent?.includes(tx('skillFileInvalid')),'invalid UTF-8 is rejected')
  const valid=new DataTransfer();valid.items.add(new File(['Always cite inspected sources.'],'SKILL.md'))
  upload.files=valid.files;upload.dispatchEvent(new Event('change',{bubbles:true}))
  await wait(()=>document.querySelector<HTMLTextAreaElement>('.skill-editor textarea')?.value==='Always cite inspected sources.')
  assert(!document.querySelector('.agent-skills')?.textContent?.includes(tx('skillFileInvalid')),'UTF-8 Markdown imports as instruction text')
  ;(document.querySelector('.skill-tool-check input') as HTMLInputElement).click();await sleep()
  button('skillSave').click();await wait(()=>button('skillResumePublish')&&!button('skillResumePublish').disabled)
  assert(document.querySelector('.agent-skills')?.textContent?.includes(tx('skillResumeHint')),'lost publication response has explicit recovery')
  button('skillResumePublish').click();await wait(()=>!document.querySelector('.skill-editor'))
  assert(publishAttempts===1,'already-published exact draft is reconciled without repeat publication')
  assert(calls.filter(c=>c==='POST /api/v1/skills').length===1,'resume does not duplicate creation')
  const checkbox=()=>document.querySelector<HTMLInputElement>('.skill-catalog input')!
  assert(checkbox().disabled,'missing dependency blocks binding without granting Tool')
  ;(document.querySelector('[data-enable]') as HTMLButtonElement).click();await sleep();checkbox().click();await sleep()
  assert(document.querySelector('output')?.textContent==='v1','binding is exact published content ID')
  assert(!calls.some(c=>c.includes('/api/v1/agents/')),'selection changes draft only')
  ;(document.querySelector('.skill-catalog summary') as HTMLElement).click();await sleep()
  assert(document.querySelector('.skill-catalog pre')?.textContent==='Always cite inspected sources.','full instruction preview')
  ;(document.querySelector('[data-readonly]') as HTMLButtonElement).click();await sleep()
  assert(checkbox().disabled && button('skillCreate').disabled,'readonly Agent cannot mutate bindings or editor')
  button('skillEvidenceLoad').click();await wait(()=>document.querySelector('.trace-skill-evidence code'))
  assert(document.querySelector('.trace-skill-evidence')?.textContent?.includes(tx('skillEvidencePrepared')),'prepared evidence is not claimed as compliance')
  assert(!document.querySelector('.trace-skill-evidence')?.textContent?.includes('Always cite'),'evidence excludes instructions')
  assert(document.documentElement.scrollWidth<=innerWidth,'no page horizontal overflow')
  assert(errors.length===0,'no browser errors')
}
main().then(()=>{(window as any).__workflowRegression={done:true,ok:true,checks}})
  .catch(error=>{(window as any).__workflowRegression={done:true,ok:false,checks,error:String(error)}})
