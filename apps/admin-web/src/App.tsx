import { useCallback, useEffect, useMemo, useState } from 'react'
import { AdminApi } from './adminApi'
import { AuthGate } from './AuthGate'
import { languageLabels, languageOrder, text } from './copy'
import { AuditPage } from './pages/AuditPage'
import { AdministratorsPage } from './pages/AdministratorsPage'
import { McpRegistryPage } from './pages/McpRegistryPage'
import { CommandsPage } from './pages/CommandsPage'
import { CleanupPage } from './pages/CleanupPage'
import { CredentialsPage } from './pages/CredentialsPage'
import { OrganizationsPage } from './pages/OrganizationsPage'
import { OverviewPage } from './pages/OverviewPage'
import { ResourcesPage } from './pages/ResourcesPage'
import { UsersPage } from './pages/UsersPage'
import type { AdminSession, Language, Section } from './types'

const api = new AdminApi()
const sectionPath: Record<Section, string> = {
  overview: '/admin', administrators: '/admin/administrators', users: '/admin/users', organizations: '/admin/organizations',
  resources: '/admin/resources', mcpRegistry: '/admin/mcp-registry', cleanup: '/admin/cleanup', credentials: '/admin/credentials', commands: '/admin/commands', audit: '/admin/audit',
}
const sectionFromPath = (path: string): Section => path.startsWith('/admin/users') ? 'users'
  : path.startsWith('/admin/administrators') ? 'administrators'
  : path.startsWith('/admin/organizations') ? 'organizations'
  : path.startsWith('/admin/resources') ? 'resources'
  : path.startsWith('/admin/mcp-registry') ? 'mcpRegistry'
  : path.startsWith('/admin/cleanup') ? 'cleanup'
  : path.startsWith('/admin/credentials') ? 'credentials'
  : path.startsWith('/admin/commands') ? 'commands'
  : path.startsWith('/admin/audit') ? 'audit' : 'overview'

export function App() {
  const [session, setSession] = useState<AdminSession | null>(null)
  const [restoring, setRestoring] = useState(true)
  const [restoreFailed, setRestoreFailed] = useState(false)
  const [path, setPath] = useState(window.location.pathname)
  const [language, setLanguage] = useState<Language>(() => {
    const stored = localStorage.getItem('spaceagent-admin-language') as Language | null
    return stored && languageOrder.includes(stored) ? stored : navigator.language.startsWith('ja') ? 'ja' : navigator.language.startsWith('zh') ? 'zh' : 'en'
  })
  const [theme, setTheme] = useState<'dark' | 'light'>(() => localStorage.getItem('spaceagent-admin-theme') === 'light' ? 'light' : 'dark')
  const tx = useCallback((key: string) => text(language, key), [language])
  const section = sectionFromPath(path)
  const userPlaneUrl = useMemo(() => import.meta.env.VITE_USER_WEB_URL || (location.hostname === '127.0.0.1' || location.hostname === 'localhost' ? 'http://127.0.0.1:5173/app' : '/'), [])

  useEffect(() => {
    document.documentElement.dataset.theme = theme
    document.documentElement.lang = language === 'zh' ? 'zh-CN' : language
    document.querySelector('meta[name="theme-color"]')?.setAttribute('content', theme === 'dark' ? '#080a0b' : '#f2f3f0')
    localStorage.setItem('spaceagent-admin-theme', theme)
    localStorage.setItem('spaceagent-admin-language', language)
  }, [language, theme])
  useEffect(() => { const listener = () => setPath(location.pathname); addEventListener('popstate', listener); return () => removeEventListener('popstate', listener) }, [])
  useEffect(() => {
    let current = true
    void api.restoreSession().then((restored) => {
      if (!current || !restored) return
      setSession(restored)
      if (!location.pathname.startsWith('/admin')) {
        history.replaceState(null, '', '/admin')
        setPath('/admin')
      }
    }).catch(() => { if (current) setRestoreFailed(true) })
      .finally(() => { if (current) setRestoring(false) })
    return () => { current = false }
  }, [])

  const navigate = (next: Section) => { const nextPath = sectionPath[next]; history.pushState(null, '', nextPath); setPath(nextPath) }
  const changeLanguage = () => setLanguage((current) => languageOrder[(languageOrder.indexOf(current) + 1) % languageOrder.length])
  const logout = async () => { await api.logout(); setSession(null); history.replaceState(null, '', '/'); setPath('/') }

  if (restoring) return <main className="session-restore" role="status" aria-live="polite"><i /><span>{tx('restoringSession')}</span></main>

  if (!session) return <AuthGate api={api} language={language} onLanguageChange={changeLanguage} theme={theme} onThemeChange={() => setTheme((value) => value === 'dark' ? 'light' : 'dark')} onAuthenticated={(value) => { setSession(value); setRestoreFailed(false); history.replaceState(null, '', '/admin'); setPath('/admin') }} tx={tx} initialError={restoreFailed ? tx('sessionRestoreFailed') : null} />


  const navigation: Array<[Section, string, string]> = [
    ['overview', 'overview', '01'], ['administrators', 'administrators', '02'], ['users', 'users', '03'], ['organizations', 'organizations', '04'], ['resources', 'resources', '05'],
    ['mcpRegistry', 'mcpRegistry', '06'], ['cleanup', 'cleanup', '07'], ['credentials', 'credentials', '08'], ['commands', 'commands', '09'], ['audit', 'audit', '10'],
  ]
  return <div className="admin-shell">
    <aside className="sidebar"><header><span className="wordmark">SpaceAgent</span></header><nav>{navigation.map(([value, key], index) => <button key={value} aria-label={tx(key)} className={section === value ? 'active' : ''} onClick={() => navigate(value)}><span>{tx(key)}</span>{index === 3 && <i />}</button>)}</nav><footer><div className="operator"><b>{session.administrator.loginName}</b></div></footer></aside>
    <main className="admin-main"><header className="topbar"><div className="global-scope"><b>{tx('administration')}</b></div><div className="top-actions"><a href={userPlaneUrl}>{tx('returnToPlatform')}</a><button onClick={() => setTheme((value) => value === 'dark' ? 'light' : 'dark')} aria-label={tx("Toggle theme")}>{theme === 'dark' ? '☀' : '☾'}</button><button onClick={changeLanguage} aria-label={tx("Change language")}>{languageLabels[language]}</button><button onClick={() => void logout()}>{tx('signOut')}</button></div></header><div className="admin-content">
      {section === 'overview' && <OverviewPage api={api} tx={tx} />}
      {section === 'administrators' && <AdministratorsPage api={api} tx={tx} currentAdminId={session.administrator.id} />}
      {section === 'users' && <UsersPage api={api} tx={tx} />}
      {section === 'organizations' && <OrganizationsPage api={api} tx={tx} />}
      {section === 'resources' && <ResourcesPage api={api} tx={tx} />}
      {section === 'mcpRegistry' && <McpRegistryPage api={api} tx={tx} />}
      {section === 'cleanup' && <CleanupPage api={api} tx={tx} />}
      {section === 'credentials' && <CredentialsPage api={api} tx={tx} />}
      {section === 'commands' && <CommandsPage api={api} tx={tx} />}
      {section === 'audit' && <AuditPage api={api} tx={tx} />}
    </div></main>
  </div>
}
