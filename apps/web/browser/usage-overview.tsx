// Isolated browser fixture. Fake reads only; no login, real account or Provider requests.
import { createRoot } from 'react-dom/client'
import { ApplicationShell } from '../src/app/ApplicationShell'
import { OverviewPage } from '../src/features/overview/OverviewPage'
import { messages, type Language } from '../src/copy'
import type { AuthenticatedRequest } from '../src/features/overview/types'
import '../src/styles.css'

const settings = new URLSearchParams(location.search), language = (settings.get('language') || 'en') as Language
localStorage.setItem('spaceagent-theme', settings.get('theme') || 'light')
const tx = (key: string) => messages[key]?.[['en', 'zh', 'ja'].indexOf(language)] || key
const checks: string[] = [], calls: string[] = [], errors: string[] = []
window.addEventListener('error', event => errors.push(event.message))
window.addEventListener('unhandledrejection', () => errors.push('unhandled rejection'))
const sleep = (ms = 25) => new Promise(resolve => setTimeout(resolve, ms))
const wait = async (condition: () => unknown) => { for (let i = 0; i < 100; i++) { if (condition()) return; await sleep() } throw Error(`Timed out: ${condition}`) }
const assert = (value: unknown, label: string) => { if (!value) throw Error(label); checks.push(label) }
let failAgents = false, emptyAgents = false
const row = (key: string, tokens: number, costUsd: number | null) => ({ key, name: `Agent ${key}`, totalTokens: tokens,
  inputTokens: tokens - 10, outputTokens: 10, cacheReadTokens: 0, cacheCreateTokens: 0, costUsd, callCount: 2,
  sessionCount: 1, avgLatencyMs: 20, agentCount: null, subLabel: null, hasIncompleteCost: costUsd === null })
const request: AuthenticatedRequest = async <T,>(path: string): Promise<T> => {
  calls.push(path)
  if (path.endsWith('/usage/agents')) {
    await sleep(30)
    if (failAgents) throw Error('fixture failure')
    return { rows: emptyAgents ? [] : [row('A', 900, null), row('B', 100, 0.001)], truncated: !emptyAgents, range: 'all', groupBy: 'agent', totalCostUsd: 0.001 } as T
  }
  const range = new URL(path, location.origin).searchParams.get('range')
  await sleep(range === '1d' ? 220 : 20)
  if (path.includes('chat/conversations')) return { items: [], total: 0 } as T
  if (path.includes('/overview')) return { overview: { totalAgents: 2, totalSessions: 1,
    totalInputTokens: range === 'all' ? 500 : range === '1d' ? 10 : 100, totalOutputTokens: 0,
    totalCacheReadTokens: 0, totalCacheCreateTokens: 0, totalCostUsd: null, hasIncompleteCost: true, range }, timeseries: [] } as T
  if (path.includes('/usage?')) return { rows: [] } as T
  if (path.includes('/sessions')) return { sessions: [], total: 0, pageSize: 10 } as T
  if (path.includes('/realtime')) return { runtimeActive: 0, processing: 0 } as T
  throw Error(`Unexpected request: ${path}`)
}
const noop = () => {}
const session: any = { user: { userId: 'u', tenantId: 't', username: 'Fixture', displayName: 'Fixture', tenantRole: 'OWNER' },
  organizations: [{ organization: { id: 't', name: 'Test workspace' }, membership: { role: 'OWNER' } }] }
createRoot(document.getElementById('root')!).render(<ApplicationShell session={session} language={language}
  onLanguageChange={noop} onLogout={async () => {}} onSwitchOrganization={async () => session}
  activeSection="overview" onNavigate={noop} tx={tx}>
  <OverviewPage language={language} request={request} onNavigate={noop} tx={tx} />
</ApplicationShell>)

async function main() {
  await wait(() => document.querySelector('.agent-usage-card tbody tr'))
  const select = document.querySelector<HTMLSelectElement>('.platform-token-card select')!
  assert(select.value === '7d', 'default is rolling seven days')
  assert([...select.options].map(o => o.value).join(',') === '1d,7d,all', 'three supported ranges')
  assert(document.querySelector('.agent-usage-card tbody th')?.textContent === 'Agent A', 'backend ranking order preserved')
  assert(document.querySelector('.agent-usage-card')?.textContent?.includes('<$0.01'), 'small known cost is not rounded to free')
  assert(document.querySelector('.agent-usage-card small'), 'unknown pricing is explicit')
  assert(document.querySelector('.agent-usage-card [role=status]'), 'truncated ranking is explicit')
  const change = (value: string) => { select.value = value; select.dispatchEvent(new Event('change', { bubbles: true })) }
  change('1d'); await wait(() => calls.includes('/api/v1/monitoring/overview?range=1d'))
  change('all'); await wait(() => document.querySelector('.usage-center strong')?.textContent === '500')
  await sleep(260)
  assert(document.querySelector('.usage-center strong')?.textContent === '500', 'late day response cannot overwrite all-time')
  assert(calls.filter(c => c.endsWith('/usage/agents')).length === 1, 'range changes do not reload lifetime ranking')
  assert(document.querySelector('.overview-metrics article:nth-child(3) b')?.textContent === '500', 'top metric and ring use same range')
  const refresh = document.querySelector<HTMLButtonElement>('.agent-usage-card button')!
  failAgents = true; refresh.click()
  await wait(() => document.querySelector('.agent-usage-card [role=alert]'))
  assert(!document.querySelector('.agent-usage-card tbody'), 'failed refresh clears stale rows')
  assert(document.querySelector('.usage-center strong')?.textContent === '500', 'ranking failure does not erase overview')
  failAgents = false; emptyAgents = true; refresh.click()
  await wait(() => !refresh.disabled && !document.querySelector('.agent-usage-card [role=alert]'))
  assert(!document.querySelector('.agent-usage-card [role=alert]') && !document.querySelector('.agent-usage-card table'), 'empty success differs from failure')
  emptyAgents = false; refresh.click(); await wait(() => document.querySelector('.agent-usage-card tbody'))
  const scroll = document.querySelector<HTMLElement>('.agent-usage-scroll')!
  scroll.focus(); assert(document.activeElement === scroll, 'table scroll region is keyboard focusable')
  assert(getComputedStyle(scroll).overflowX === 'auto', 'wide table owns horizontal scroll')
  assert(document.documentElement.scrollWidth <= innerWidth, 'no page horizontal overflow')
  assert(errors.length === 0, 'no browser errors or unhandled promises')
  scroll.scrollIntoView({ block: 'center' })
}
main().then(() => { (window as any).__workflowRegression = { done: true, ok: true, checks } })
  .catch(error => { (window as any).__workflowRegression = { done: true, ok: false, checks, error: String(error) } })
