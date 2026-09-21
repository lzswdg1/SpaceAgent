// Real Admin login/client/session management with fake transport only.
import { useState } from 'react'
import { createRoot } from 'react-dom/client'
import { AuthGate } from '../src/AuthGate'
import { AdminApi } from '../src/adminApi'
import { AdministratorsPage } from '../src/pages/AdministratorsPage'
import { text } from '../src/copy'
import type { AdminSession, Language } from '../src/types'
import '../src/styles.css'
import '../src/adminRefinement.css'

const params=new URLSearchParams(location.search),language=(params.get('language')||'en') as Language
const theme=(params.get('theme')||'light') as 'light'|'dark';document.documentElement.dataset.theme=theme
const tx=(key:string)=>text(language,key),checks:string[]=[],calls:string[]=[],errors:string[]=[]
window.addEventListener('error',e=>errors.push(e.message));window.addEventListener('unhandledrejection',()=>errors.push('rejection'))
const session:AdminSession={accessToken:'fake-access',csrfToken:'fake-csrf',accessTokenExpiresAt:'2099-01-01T00:00:00Z',administrator:{id:'admin',loginName:'configured-admin',displayName:'Administrator',role:'PLATFORM_SUPER_ADMIN',status:'ACTIVE',mustChangePassword:false,lastSuccessfulLoginAt:null}}
let revoked=false
const api=new AdminApi(async(input,init)=>{
  const path=String(input);calls.push(path)
  let data:unknown=session,status=200
  if(path.endsWith('/login')){if(JSON.parse(String(init?.body)).password!=='Fixture-Password-2026!')status=401}
  else if(path.includes('/administrators?'))data={items:[{...session.administrator,credentialVersion:1,activeSessions:2,remainingRecoveryCodes:0,createdAt:null,updatedAt:null}],total:1}
  else if(path.includes('/sessions?'))data={items:[{id:'current',active:true,current:true,createdAt:null,expiresAt:null},{id:'other',active:!revoked,current:false,createdAt:null,expiresAt:null}],total:2}
  else if(path.endsWith('/revocations')){revoked=true;data={state:'SUCCEEDED'}}
  else throw Error(`Unexpected request ${path}`)
  return new Response(JSON.stringify({success:status===200,data,code:status===200?'OK':'ADMIN_INVALID_CREDENTIALS',message:status===200?'OK':'Administrator credentials are invalid'}),{status,headers:{'Content-Type':'application/json'}})
},()=> 'fake-csrf')
function Fixture(){const [authenticated,setAuthenticated]=useState<AdminSession|null>(null)
  return authenticated?<div className="admin-shell"><aside className="sidebar"><header><span className="wordmark">SpaceAgent</span></header></aside><main className="admin-main"><div className="admin-content"><AdministratorsPage api={api} currentAdminId="admin" tx={tx}/></div></main></div>:<AuthGate api={api} language={language} onLanguageChange={()=>{}} theme={theme} onThemeChange={()=>{}} onAuthenticated={setAuthenticated} tx={tx}/>
}
createRoot(document.getElementById('root')!).render(<Fixture/> )
const sleep=(ms=25)=>new Promise(r=>setTimeout(r,ms)),wait=async(c:()=>unknown)=>{for(let i=0;i<100;i++){if(c())return;await sleep()}throw Error(`Timeout ${c}`)}
const assert=(v:unknown,label:string)=>{if(!v)throw Error(label);checks.push(label)}
const fill=(el:HTMLInputElement|HTMLTextAreaElement,v:string)=>{Object.getOwnPropertyDescriptor(el instanceof HTMLTextAreaElement?HTMLTextAreaElement.prototype:HTMLInputElement.prototype,'value')!.set!.call(el,v);el.dispatchEvent(new Event('input',{bubbles:true}))}
async function main(){
  await wait(()=>document.querySelector('.auth-form'))
  assert(document.querySelectorAll('.auth-form input').length===2,'login has only username and password')
  const inputs=document.querySelectorAll<HTMLInputElement>('.auth-form input')
  fill(inputs[0],'configured-admin');fill(inputs[1],'wrong-password');await sleep()
  document.querySelector<HTMLFormElement>('.auth-form')!.requestSubmit();await wait(()=>document.querySelector('[role=alert]'))
  assert(!api.currentSession(),'bad credentials cannot create a browser session')
  assert(inputs[1].value==='','password cleared after failed attempt')
  fill(inputs[1],'Fixture-Password-2026!');await sleep()
  document.querySelector<HTMLFormElement>('.auth-form')!.requestSubmit();await wait(()=>document.querySelector('.admin-session-list'))
  assert(calls.filter(path=>path.endsWith('/login')).length===2,'one request per password login attempt')
  assert(!calls.some(path=>path.includes('mfa')),'no MFA request or challenge')
  assert(!document.querySelector('[autocomplete=one-time-code]'),'no OTP fields after login')
  assert(!document.querySelector('.recovery-control'),'no recovery-code control')
  assert(!JSON.stringify({...localStorage,...sessionStorage}).includes('fake-access'),'access token not persisted')
  const revoke=[...document.querySelectorAll<HTMLButtonElement>('button')].find(b=>b.textContent===tx('revokeSession'))!
  revoke.click();await sleep()
  assert(!document.querySelector('.modal input'),'session command needs only a reason, not MFA/password')
  fill(document.querySelector<HTMLTextAreaElement>('.modal textarea')!,'Revoke unrecognized session');await sleep()
  document.querySelector<HTMLFormElement>('.modal')!.requestSubmit();await wait(()=>revoked&&!document.querySelector('.modal'))
  assert(calls.filter(path=>path.endsWith('/revocations')).length===1,'session revocation sends one audited command')
  assert(document.documentElement.scrollWidth<=innerWidth,'no page horizontal overflow')
  assert(errors.length===0,'no browser errors')
  document.querySelector('.page-head')?.scrollIntoView({block:'start'})
}
main().then(()=>{(window as any).__workflowRegression={done:true,ok:true,checks}}).catch(error=>{(window as any).__workflowRegression={done:true,ok:false,checks,error:String(error)}})
