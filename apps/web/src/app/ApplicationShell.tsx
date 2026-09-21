import { useEffect, useMemo, useState, type ReactNode } from 'react'
import type { AuthenticatedSession } from '../features/auth/types'
import type { Language } from '../copy'
import { languageLabels, languageOrder } from '../copy'
import { useTheme } from './useTheme'
import { BrandLockup } from '../components/BrandLockup'
import { Blocks, BookOpen, Brain, Bot, ChartNoAxesCombined, Clock3, Folder, LayoutDashboard, MessageSquare, PanelLeft, Settings, SlidersHorizontal, Sun, Moon } from 'lucide-react'
import { WorkspaceNavigationContext } from './WorkspaceNavigation'
import './application-shell.css'
import './platform-theme.css'

type Props = {
  session: AuthenticatedSession
  language: Language
  onLanguageChange: () => void
  onLogout: () => Promise<void>
  onSwitchOrganization: (organizationId: string) => Promise<AuthenticatedSession>
  activeSection: 'overview' | 'chat' | 'project' | 'agents' | 'automation' | 'memory' | 'knowledge' | 'models' | 'mcp' | 'tracing' | 'organization'
  sidebarCollapsed?: boolean
  onToggleNavigation: () => void
  onNavigate: (path: string) => void
  tx: (key: string) => string
  children: ReactNode
}

const Brand = () => <BrandLockup className="app-brand-lockup" />

export function ApplicationShell({
  session,
  language,
  onLanguageChange,
  onLogout,
  onSwitchOrganization,
  activeSection,
  sidebarCollapsed = false,
  onToggleNavigation,
  onNavigate,
  tx,
  children,
}: Props) {
  const { theme, toggleTheme } = useTheme()
  const [navigationTarget, setNavigationTarget] = useState<HTMLDivElement | null>(null)
  const [mobileOpen, setMobileOpen] = useState(false)
  useEffect(() => { setMobileOpen(false) }, [activeSection])
  useEffect(() => {
    const dismiss = (event: KeyboardEvent) => { if (event.key === 'Escape') setMobileOpen(false) }
    window.addEventListener('keydown', dismiss)
    return () => window.removeEventListener('keydown', dismiss)
  }, [])
  const [narrow, setNarrow] = useState(() => window.matchMedia('(max-width: 700px)').matches)
  useEffect(() => { const media = window.matchMedia('(max-width: 700px)'); const update = () => setNarrow(media.matches); media.addEventListener('change', update); return () => media.removeEventListener('change', update) }, [])
  const navigationHidden = narrow ? !mobileOpen : sidebarCollapsed
  const toggleNavigation = () => narrow ? setMobileOpen(value => !value) : onToggleNavigation()
  const [organizationDialogOpen, setOrganizationDialogOpen] = useState(false)
  const [pendingOrganizationId, setPendingOrganizationId] = useState(session.user.tenantId)
  const [switching, setSwitching] = useState(false)
  const [switchError, setSwitchError] = useState<string | null>(null)
  const activeOrganization = useMemo(() => session.organizations.find(
    ({ organization }) => organization.id === session.user.tenantId,
  )?.organization, [session])
  const nextLanguage = languageOrder[(languageOrder.indexOf(language) + 1) % languageOrder.length]
  const adminUrl = import.meta.env.VITE_ADMIN_WEB_URL

  const confirmSwitch = async () => {
    if (pendingOrganizationId === session.user.tenantId) {
      setOrganizationDialogOpen(false)
      return
    }
    setSwitching(true)
    setSwitchError(null)
    try {
      await onSwitchOrganization(pendingOrganizationId)
      setOrganizationDialogOpen(false)
    } catch (error) {
      setSwitchError(error instanceof Error ? error.message : tx('organizationSwitchFailed'))
    } finally {
      setSwitching(false)
    }
  }

  const icons = { overview: LayoutDashboard, chat: MessageSquare, project: Folder, agents: Bot, automation: Clock3, mcp: Blocks, tracing: ChartNoAxesCombined, knowledge: BookOpen, memory: Brain, models: SlidersHorizontal, organization: Settings }
  const navigation = [
    ['overviewNav', 'overview', '/app', true],
    ['chatNav', 'chat', '/app/chat', true],
    ['projectNav', 'project', '/app/project', true],
    ['agentsNav', 'agents', '/app/agents', true],
    ['automationNav', 'automation', '/app/automation', true],
    ['mcpNav', 'mcp', '/app/mcp', true],
    ['tracingNav', 'tracing', '/app/tracing', true],
    ['knowledgeNav', 'knowledge', '/app/knowledge', true],
    ['memoryNav', 'memory', '/app/memory', true],
    ['modelsNav', 'models', '/app/models', true],
    ['organizationNav', 'organization', '/app/organization', true],
  ] as const

  return (
    <WorkspaceNavigationContext.Provider value={navigationTarget}>
    <div className={`app-frame platform-glass ${sidebarCollapsed ? 'chat-sidebar-collapsed' : ''} ${mobileOpen ? 'mobile-navigation-open' : ''}`}>
      <div className="platform-ambient" aria-hidden="true" />
      {mobileOpen && <button className="navigation-backdrop" type="button" aria-label={tx('close')} onClick={() => setMobileOpen(false)} />}
      <aside className="app-sidebar" id="application-navigation" inert={navigationHidden}>
        <header><img className="platform-logo" src="/favicon.png" alt="" /><Brand /></header>
        <button className="sidebar-organization" type="button" onClick={() => setOrganizationDialogOpen(true)}>
          <span className="app-avatar">{(activeOrganization?.name || 'S').slice(0, 1)}</span>
          <span><b>{activeOrganization?.name || tx('organization')}</b><small>{session.user.displayName || session.user.username}</small></span>
        </button>
        <div className="sidebar-scroll">
          <nav className="primary-navigation" aria-label={tx('primaryNavigation')}>
            {navigation.map(([key, section, target]) => {
              const Icon = icons[section]
              return <a key={key} className={activeSection === section ? 'active' : ''} href={target}
                aria-current={activeSection === section ? 'page' : undefined}
                onClick={event => { event.preventDefault(); onNavigate(target) }}><Icon size={18} aria-hidden="true" /><span>{tx(key)}</span></a>
            })}
          </nav>
          <div className={`workspace-navigation-slot ${activeSection === 'project' || activeSection === 'chat' ? 'expanded' : ''}`}>
            <div className="workspace-navigation-clip"><div className="workspace-navigation-target" ref={setNavigationTarget} /></div>
          </div>
        </div>
        <footer>
          <span className="app-avatar">{(session.user.displayName || session.user.username).slice(0, 2).toUpperCase()}</span>
          <span><b>{session.user.displayName || session.user.username}</b><small>{session.user.tenantRole}</small></span>
        </footer>
      </aside>
      <main className="app-main">
        <header className="app-topbar">
          <button type="button" className="platform-navigation-toggle" aria-controls="application-navigation" aria-expanded={!navigationHidden} aria-label={tx(navigationHidden ? 'expandNavigation' : 'collapseNavigation')} onClick={toggleNavigation}><PanelLeft size={18} /></button>
          <button className="organization-context" type="button" onClick={() => setOrganizationDialogOpen(true)}>
            <i /><span>{activeOrganization?.name || session.user.tenantId}</span><small>{tx('change')}</small>
          </button>
          <div className="app-top-actions">
            {adminUrl && <a href={adminUrl}>{tx('adminControl')} ↗</a>}
            <button type="button" onClick={toggleTheme} aria-label={theme === 'dark' ? tx('lightMode') : tx('darkMode')}>{theme === 'dark' ? <Sun size={17} /> : <Moon size={17} />}</button>
            <button type="button" onClick={onLanguageChange} aria-label={`Switch to ${languageLabels[nextLanguage]}`}>{languageLabels[language]}</button>
            <button type="button" onClick={() => void onLogout()}>{tx('signOut')}</button>
          </div>
        </header>
        <div className={`app-content ${activeSection === 'project' || activeSection === 'chat' ? 'workspace-content' : ''}`}>{children}</div>
      </main>

      {organizationDialogOpen && (
        <div className="organization-modal" role="dialog" aria-modal="true" aria-labelledby="organization-dialog-title">
          <div className="organization-dialog">
            <span>{tx('systemConfirmation')}</span>
            <h2 id="organization-dialog-title">{tx('switchOrganization')}</h2>
            <div className="organization-options">
              {session.organizations.map(({ organization, membership }) => (
                <button
                  key={organization.id}
                  type="button"
                  className={pendingOrganizationId === organization.id ? 'active' : ''}
                  onClick={() => setPendingOrganizationId(organization.id)}
                >
                  <b>{organization.name}</b><small>{membership.role}</small>
                </button>
              ))}
            </div>
            {switchError && <p className="organization-error" role="alert">{switchError}</p>}
            <div className="organization-actions">
              <button type="button" onClick={() => setOrganizationDialogOpen(false)}>{tx('cancel')}</button>
              <button className="primary" type="button" disabled={switching} onClick={() => void confirmSwitch()}>{switching ? tx('switching') : tx('confirmSwitch')}</button>
            </div>
          </div>
        </div>
      )}
    </div>
    </WorkspaceNavigationContext.Provider>
  )
}
