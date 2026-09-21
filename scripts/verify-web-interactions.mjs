// Deterministic browser regressions: real React/CSS, fake public APIs, isolated Chrome profile.
// No user profile, provider credentials, live mutations or additional browser framework.
import { build } from 'esbuild'
import { mkdtemp, readFile, writeFile } from 'node:fs/promises'
import { spawn } from 'node:child_process'
import { createServer } from 'node:http'
import { tmpdir } from 'node:os'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const repo = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const out = await mkdtemp(path.join(tmpdir(), 'spaceagent-web-regression-'))
const conversationPerformanceRun = process.env.WEB_TEST_FIXTURE === 'conversation-performance'
const performanceRun = process.env.WEB_TEST_FIXTURE === 'scroll-performance' || conversationPerformanceRun
const navigationRun = process.env.WEB_TEST_FIXTURE === 'navigation-transition'
const settingsRun = process.env.WEB_TEST_FIXTURE === 'conversation-settings'
const usageRun = process.env.WEB_TEST_FIXTURE === 'usage-overview'
const toolsRun = process.env.WEB_TEST_FIXTURE === 'agent-tools'
const skillsRun = process.env.WEB_TEST_FIXTURE === 'agent-skills'
const adminPasswordRun = process.env.WEB_TEST_FIXTURE === 'admin-password'
await build({ entryPoints: [conversationPerformanceRun ? path.join(repo, 'scripts/fixtures/conversation-performance.tsx') : adminPasswordRun ? path.join(repo, 'apps/admin-web/browser/password-login.tsx') : path.join(repo, `apps/web/browser/${skillsRun ? 'agent-skills' : toolsRun ? 'agent-tools' : usageRun ? 'usage-overview' : performanceRun ? 'scroll-performance' : navigationRun ? 'navigation-transition' : settingsRun ? 'conversation-settings' : 'workflow-regressions'}.tsx`)],
  outfile: path.join(out, 'fixture.js'), bundle: true, format: 'iife', jsx: 'automatic',
  define: { 'import.meta.env': '{}', ...(conversationPerformanceRun ? { 'process.env.NODE_ENV': '"production"' } : {}) } })
await writeFile(path.join(out, 'index.html'), `<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><link rel="stylesheet" href="fixture.css">${performanceRun || navigationRun ? '' : '<style>*,*:before,*:after{animation:none!important;transition:none!important}</style>'}</head><body><div id="root"></div><script src="fixture.js"></script></body></html>`)
const files={'/index.html':['index.html','text/html'],'/fixture.js':['fixture.js','application/javascript'],'/fixture.css':['fixture.css','text/css'],'/favicon.png':[path.join(repo,'apps/web/public/favicon.png'),'image/png']}
const server=createServer(async(req,res)=>{
  const file=files[new URL(req.url,'http://localhost').pathname]
  if(!file){res.writeHead(404).end();return}
  try{const body=await readFile(path.isAbsolute(file[0])?file[0]:path.join(out,file[0]));res.writeHead(200,{'Content-Type':file[1]});res.end(body)}catch{res.writeHead(404).end()}
})
await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve))
const origin=`http://127.0.0.1:${server.address().port}`
const chrome = process.env.CHROME_BIN || '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome'
const child = spawn(chrome, ['--headless=new', '--no-first-run', '--disable-background-networking',
  '--disable-component-update', '--disable-sync', '--remote-debugging-port=0', `--user-data-dir=${out}/profile`, 'about:blank'], { stdio: ['ignore', 'ignore', 'pipe'] })
let stderr = ''; child.stderr.on('data', chunk => { stderr += chunk })
const delay = ms => new Promise(resolve => setTimeout(resolve, ms))
let ws
try {
  let endpoint
  for (let i=0; i<100 && !endpoint; i++) {
    try { const [port, socket] = (await readFile(`${out}/profile/DevToolsActivePort`, 'utf8')).trim().split('\n'); endpoint = `ws://127.0.0.1:${port}${socket}` } catch { await delay(100) }
  }
  if (!endpoint) throw Error(`Chrome did not start: ${stderr.slice(-1500)}`)
  ws = new WebSocket(endpoint)
  await new Promise((resolve,reject) => {ws.onopen=resolve;ws.onerror=reject})
  let next=0;const waiting=new Map()
  ws.onmessage=event=>{const value=JSON.parse(event.data);const item=waiting.get(value.id);if(!item)return;waiting.delete(value.id);clearTimeout(item.timer);value.error?item.reject(Error(JSON.stringify(value.error))):item.resolve(value.result)}
  const send=(method,params={},sessionId)=>new Promise((resolve,reject)=>{
    const id=++next,timer=setTimeout(()=>{waiting.delete(id);reject(Error(`Timed out: ${method}`))},15000)
    waiting.set(id,{resolve,reject,timer});ws.send(JSON.stringify({id,method,params,...sessionId?{sessionId}:{}}))
  })
  for(const [width,language,theme] of [[1440,'en','light'],[1100,'zh','dark'],[390,'ja','light']]) {
    if(process.env.WEB_TEST_WIDTH && width!==Number(process.env.WEB_TEST_WIDTH))continue
    const {targetId}=await send('Target.createTarget',{url:'about:blank'})
    const {sessionId}=await send('Target.attachToTarget',{targetId,flatten:true})
    await send('Emulation.setDeviceMetricsOverride',{width,height:950,deviceScaleFactor:performanceRun?2:1,mobile:width<700},sessionId)
    if(navigationRun)await send('Emulation.setEmulatedMedia',{features:[{name:'prefers-reduced-motion',value:'no-preference'}]},sessionId)
    const url=new URL('/index.html',origin);url.search=`language=${language}&theme=${theme}`
    await send('Page.navigate',{url:url.href},sessionId)
    let result
    for(let i=0;i<(performanceRun?600:160);i++) {
      const value=await send('Runtime.evaluate',{expression:'window.__workflowRegression',returnByValue:true},sessionId)
      if(value.result?.value?.done){result=value.result.value;break}await delay(100)
    }
    await writeFile(path.join(out,`${width}-${language}.json`),JSON.stringify(result??{timeout:true},null,2))
    const screenshot=await send('Page.captureScreenshot',{format:'png'},sessionId)
    await writeFile(path.join(out,`${width}-${language}.png`),Buffer.from(screenshot.data,'base64'))
    if(!result?.ok)throw Error(`${width}/${language}: ${JSON.stringify(result)}`)
    if(navigationRun){
      await send('Emulation.setEmulatedMedia',{features:[{name:'prefers-reduced-motion',value:'reduce'}]},sessionId)
      const reduced=await send('Runtime.evaluate',{expression:"getComputedStyle(document.querySelector('.workspace-navigation-view')).animationName === 'none' && getComputedStyle(document.querySelector('.chat-workspace')).animationName === 'none'",returnByValue:true},sessionId)
      if(!reduced.result.value)throw Error('Reduced-motion preference is not respected')
    }
    console.log(`PASS ${width}px ${language}/${theme}: ${result.checks.length} interaction assertions`)
    await send('Target.closeTarget',{targetId})
  }
  await send('Browser.close')
} finally {ws?.close();child.kill();server.close();console.log(`Browser evidence: ${out}`)}
