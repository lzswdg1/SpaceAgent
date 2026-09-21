import { useEffect, useRef, useState } from 'react'
import { createRoot } from 'react-dom/client'
import { ApplicationShell } from '../../apps/web/src/app/ApplicationShell'
import { ConversationViewport } from '../../apps/web/src/features/chat/ConversationViewport'
import { ConversationMessage } from '../../apps/web/src/features/chat/ConversationMessage'
import { messages, type Language } from '../../apps/web/src/copy'
import '../../apps/web/src/styles.css'
import '../../apps/web/src/features/projects/project.css'
import '../../apps/web/src/features/projects/project-panels.css'

// Real production components/CSS; synthetic bounded text, no accounts, API or paid models.
const settings = new URLSearchParams(location.search)
const language = (settings.get('language') || 'en') as Language
const theme = settings.get('theme') || 'light'
localStorage.setItem('spaceagent-theme', theme)
const tx = (key: string) => messages[key]?.[['en', 'zh', 'ja'].indexOf(language)] || key, noop = () => {}
const session: any = { user: { userId: 'fixture', tenantId: 'fixture', username: 'Local fixture', tenantRole: 'OWNER' },
  organizations: [{ organization: { id: 'fixture', name: 'Local fixture' }, membership: { role: 'OWNER' } }] }
const paragraph = '当前仓库通过模块边界、权限过滤与不可变执行证据实现隔离。这里仅使用本地生成的文字，验证长回答的历史窗口、Markdown 渲染与滚动行为。'
const section = `## 架构说明\n\n${paragraph.repeat(6)}\n\n- 模块归属\n- 租户隔离\n\n| 模块 | 说明 |\n| --- | --- |\n| Runtime | 持久化执行证据 |\n\n\`\`\`typescript\nconst names = records.map(record => record.name)\n\`\`\`\n\n`
const history = Array.from({ length: 120 }, (_, index) => ({ id: `history-${index}`,
  role: index % 4 === 0 ? 'USER' : 'ASSISTANT', content: index % 4 === 0 ? '请分析当前模块。' : section.repeat(6) }))
const sleep = (ms: number) => new Promise(resolve => setTimeout(resolve, ms))
const frames = (root: HTMLElement, scroll: boolean) => new Promise<number[]>(resolve => {
  const gaps: number[] = []
  let last = 0, count = 0
  const frame = (now: number) => {
    if (last) gaps.push(now - last)
    last = now
    if (scroll) root.scrollTop += 13
    if (++count < 91) requestAnimationFrame(frame)
    else resolve(gaps)
  }
  requestAnimationFrame(frame)
})
const quantiles = (gaps: number[]) => {
  const sorted = [...gaps].sort((a, b) => a - b)
  return { frames: gaps.length, p50Ms: sorted[Math.floor(sorted.length * .5)], p95Ms: sorted[Math.floor(sorted.length * .95)],
    over25ms: gaps.filter(value => value > 25).length, maxMs: Math.max(...gaps) }
}

function Fixture() {
  const log = useRef<HTMLDivElement>(null)
  const live = useRef('')
  const [content, setContent] = useState('')
  const [streaming, setStreaming] = useState(false)
  useEffect(() => {
    let disposed = false
    const tasks: number[] = []
    const longTasks: number[] = []
    const observer = typeof PerformanceObserver === 'undefined' ? null : new PerformanceObserver(list => {
      for (const entry of list.getEntries()) longTasks.push(entry.duration)
    })
    try { observer?.observe({ type: 'longtask', buffered: false }) } catch {}
    async function run() {
      await sleep(700)
      if (disposed) return
      const root = log.current!
      root.scrollTop = 0
      await sleep(400)
      const checks: string[] = []
      const assert = (condition: boolean, name: string) => { if (!condition) throw Error(name); checks.push(name) }
      const placeholderCount = () => [...root.querySelectorAll('article')].filter(node => node.children.length === 0).length
      const initialLayout = { rootHeight: root.clientHeight, scrollHeight: root.scrollHeight,
        first: [...root.querySelectorAll('article')].slice(0, 3).map(node => ({ children: node.children.length,
          inlineHeight: node.style.height, measuredHeight: node.getBoundingClientRect().height })) }
      ;(window as any).__acceptanceLayout = initialLayout
      assert(root.clientHeight > 200, 'fixture has a real visible scroll viewport')
      assert(root.querySelectorAll('article')[0].children.length > 0, 'visible history actually mounts before sampling')
      assert(placeholderCount() > 100, 'offscreen history releases Markdown AST/DOM')
      const initialNodes = document.querySelectorAll('*').length
      assert(initialNodes < 1800, 'initial DOM bounded despite 120 historical messages')
      setStreaming(true)
      for (let i = 0; i < 48; i++) {
        tasks.push(window.setTimeout(() => {
          if (!disposed) { live.current += section; setContent(live.current) }
        }, i * 30))
      }
      const streamGaps = await frames(root, false)
      await sleep(200)
      assert(root.scrollTop === 0, 'streaming output never moves the reader down')
      assert(live.current.length > 20_000, 'long streamed text is incrementally accepted')
      const liveRenderedCharacters = root.querySelector('.conversation-transcript > article:last-child .markdown-message')?.textContent?.length || 0
      assert(liveRenderedCharacters > 20_000, 'the actual live message renders before terminal completion')
      setStreaming(false)
      await sleep(200)
      const settledNodes = document.querySelectorAll('*').length
      const scrollGaps = await frames(root, true)
      assert(placeholderCount() > 95, 'history window remains bounded after scrolling')
      assert(root.scrollTop > 0, 'reader controls scroll independently of output')
      assert(document.documentElement.scrollWidth <= innerWidth + 1, 'no document horizontal overflow')
      assert(document.querySelector('.message-jump-latest') !== null, 'latest-message jump remains explicit')
      ;(window as any).__workflowRegression = { done: true, ok: true, checks, historyMessages: history.length,
        historicalCharacters: history.reduce((sum, message) => sum + message.content.length, 0),
        streamedCharacters: live.current.length, liveRenderedCharacters, placeholders: placeholderCount(), initialNodes, settledNodes,
        initialLayout, language, theme, metrics: { streaming: quantiles(streamGaps), scrolling: quantiles(scrollGaps),
          longTasks: longTasks.length, maxLongTaskMs: Math.max(0, ...longTasks) }, viewport: innerWidth, dpr: devicePixelRatio }
    }
    run().catch(error => { (window as any).__workflowRegression = { done: true, ok: false, error: String(error), layout: (window as any).__acceptanceLayout } })
    return () => { disposed = true; tasks.forEach(clearTimeout); observer?.disconnect() }
  }, [])
  return <ApplicationShell session={session} language={language} onLanguageChange={noop} onLogout={async () => {}}
    onSwitchOrganization={async () => session} activeSection="project" sidebarCollapsed={false} onToggleNavigation={noop} onNavigate={noop} tx={tx}>
    <section className="project-workspace inspector-collapsed"><main className="project-conversation">
      <header>本地长历史与流式滚动验收</header>
      <ConversationViewport className="project-message-log" logRef={log} tx={tx}>
        {history.map(message => <ConversationMessage key={message.id} message={message} mode="project" logRef={log} tx={tx} toggleReasoning={noop} />)}
        <ConversationMessage message={{ id: 'live', role: 'ASSISTANT', content, streaming }} mode="project" logRef={log} tx={tx} toggleReasoning={noop} />
      </ConversationViewport>
      <form className="project-composer"><textarea readOnly placeholder="本地夹具不会发送任何请求" /></form>
    </main></section>
  </ApplicationShell>
}
createRoot(document.getElementById('root')!).render(<Fixture />)
