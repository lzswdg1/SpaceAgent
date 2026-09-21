import {useEffect} from 'react'
import {createRoot} from 'react-dom/client'
import {ApplicationShell} from '../src/app/ApplicationShell'
import {ConversationViewport} from '../src/features/chat/ConversationViewport'
import {MarkdownMessage} from '../src/components/MarkdownMessage'
import {useRef} from 'react'
import '../src/styles.css'
import '../src/features/projects/project.css'
import '../src/features/projects/project-panels.css'

// Static content only: no inference, SSE, polling or real account data in this scroll experiment.
const tx=(key:string)=>key,noop=()=>{}
const session:any={user:{userId:'fixture',tenantId:'fixture',username:'Scroll test',tenantRole:'OWNER'},organizations:[{organization:{id:'fixture',name:'Scroll performance'},membership:{role:'OWNER'}}]}
const paragraph='这是一段用于测试页面滚动的静态内容。页面包含标题、列表、代码块与表格，滚动过程中不会生成新消息，也没有网络请求。'
const body=`## 项目说明\n\n${paragraph.repeat(4)}\n\n- 文件浏览\n- 用户消息\n- 静态阅读\n\n| 模块 | 内容 |\n| --- | --- |\n| UI | ${paragraph} |\n\n\`\`\`typescript\nconst items = records.map(record => record.id)\n\`\`\`\n\n> ${paragraph}`
const sample=(log:HTMLElement)=>new Promise<number[]>(resolve=>{
  const gaps:number[]=[];let previous=0,frames=0
  const frame=(now:number)=>{
    if(previous)gaps.push(now-previous);previous=now
    log.scrollTop+=7
    if(++frames<121)requestAnimationFrame(frame);else resolve(gaps)
  };requestAnimationFrame(frame)
})
const sleep=(ms:number)=>new Promise(resolve=>setTimeout(resolve,ms))
function Fixture(){
  const log=useRef<HTMLDivElement>(null)
  useEffect(()=>{
    let disposed=false
    async function run(){
      await sleep(700)
      const overrides=document.createElement('style');document.head.append(overrides)
      const variants={current:'',withoutBlur:'.platform-glass *,.platform-glass *::after{backdrop-filter:none!important;filter:none!important}',withoutMotion:'.platform-glass *,.platform-glass *::after{animation:none!important;transition:none!important}',withoutBoth:'.platform-glass *,.platform-glass *::after{backdrop-filter:none!important;filter:none!important;animation:none!important;transition:none!important}'}
      const metrics=[]
      for(const name of ['current','withoutBlur','withoutMotion','withoutBoth','withoutBoth','withoutMotion','withoutBlur','current'] as const){
        if(disposed)return
        overrides.textContent=variants[name];log.current!.scrollTop=100;await sleep(250)
        const gaps=await sample(log.current!),sorted=[...gaps].sort((a,b)=>a-b)
        metrics.push({variant:name,frames:gaps.length,medianMs:sorted[Math.floor(sorted.length*.5)],p95Ms:sorted[Math.floor(sorted.length*.95)],over25ms:gaps.filter(gap=>gap>25).length,maxMs:Math.max(...gaps)})
      }
      overrides.remove()
      const paintPolicy={mainBackdrop:getComputedStyle(document.querySelector('.app-main')!).backdropFilter,
        sidebarBackdrop:getComputedStyle(document.querySelector('.app-sidebar')!).backdropFilter,
        composerBackdrop:getComputedStyle(document.querySelector('.project-composer')!).backdropFilter,
        ambientAnimation:getComputedStyle(document.querySelector('.platform-ambient')!,'::after').animationName}
      const result={done:true,ok:true,checks:['static same-content scroll samples completed'],metrics,paintPolicy,domNodes:document.querySelectorAll('*').length,viewport:innerWidth,dpr:devicePixelRatio}
      ;(window as any).__workflowRegression=result
      if(new URLSearchParams(location.search).get('report')==='1')await fetch('/perf-result',{method:'POST',body:JSON.stringify(result)})
    }
    run().catch(error=>{(window as any).__workflowRegression={done:true,ok:false,error:String(error)}})
    return()=>{disposed=true}
  },[])
  return <ApplicationShell session={session} language="zh" onLanguageChange={noop} onLogout={async()=>{}} onSwitchOrganization={async()=>session} activeSection="project" sidebarCollapsed={false} onToggleNavigation={noop} onNavigate={noop} tx={tx}>
    <section className="project-workspace inspector-collapsed"><main className="project-conversation"><header>静态长页面滚动测试</header>
      <ConversationViewport className="project-message-log" logRef={log} tx={tx}>
        {Array.from({length:40},(_,index)=><article key={index} className={index%3===0?'user':'assistant'}><div className="project-message-content">{index%3===0?'这是一条历史用户消息。':<MarkdownMessage content={body}/>}</div></article>)}
      </ConversationViewport>
      <form className="project-composer"><textarea readOnly placeholder="测试期间不发送任何请求"/></form>
    </main></section>
  </ApplicationShell>
}
createRoot(document.getElementById('root')!).render(<Fixture/> )
