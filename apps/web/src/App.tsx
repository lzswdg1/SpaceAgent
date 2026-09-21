import { lazy, Suspense, useCallback, useEffect, useRef, useState, type FormEvent } from 'react'
import { languageLabels, languageOrder, text, type Language } from './copy'
import { ParticleField } from './ParticleField'
import { ApplicationShell } from './app/ApplicationShell'
import { BrandLockup } from './components/BrandLockup'
import { useAuthSession } from './features/auth/useAuthSession'
import type { AuthenticatedSession, LoginInput, RegisterInput, SessionPersistence } from './features/auth/types'

const OverviewPage = lazy(() => import('./features/overview/OverviewPage').then((module) => ({ default: module.OverviewPage })))
const ChatPage = lazy(() => import('./features/chat/ChatPage').then((module) => ({ default: module.ChatPage })))
const ProjectPage = lazy(() => import('./features/projects/ProjectPage').then((module) => ({ default: module.ProjectPage })))
const AgentPage = lazy(() => import('./features/agents/AgentPage').then((module) => ({ default: module.AgentPage })))
const AutomationPage = lazy(() => import('./features/automation/AutomationPage').then((module) => ({ default: module.AutomationPage })))
const MemoryPage = lazy(() => import('./features/memory/MemoryPage').then((module) => ({ default: module.MemoryPage })))
const KnowledgePage = lazy(() => import('./features/knowledge/KnowledgePage').then((module) => ({ default: module.KnowledgePage })))
const ModelResourcesPage = lazy(() => import('./features/models/ModelResourcesPage').then((module) => ({ default: module.ModelResourcesPage })))
const McpPage = lazy(() => import('./features/mcp/McpPage').then((module) => ({ default: module.McpPage })))
const TracingPage = lazy(() => import('./features/tracing/TracingPage').then((module) => ({ default: module.TracingPage })))
const OrganizationSettingsPage = lazy(() => import('./features/organizations/OrganizationSettingsPage').then((module) => ({ default: module.OrganizationSettingsPage })))

type AuthMode = 'signin' | 'register'
type SubmitState = 'idle' | 'loading' | 'success'

const clamp = (value: number, min = 0, max = 1) => Math.min(max, Math.max(min, value))
const smooth = (from: number, to: number, value: number) => {
  const amount = clamp((value - from) / (to - from))
  return amount * amount * (3 - 2 * amount)
}

function BrandWordmark({ className = '', showMotto = false }: { className?: string; showMotto?: boolean }) {
  return <BrandLockup className={className} showMotto={showMotto} />
}

function LanguageButton({ language, onChange }: { language: Language; onChange: () => void }) {
  const next = languageOrder[(languageOrder.indexOf(language) + 1) % languageOrder.length]
  const nextLabel = next === 'zh' ? '中文' : next === 'ja' ? '日本語' : 'English'
  return (
    <button className="language-button" type="button" onClick={onChange} aria-label={`Switch to ${nextLabel}`}>
      {languageLabels[language]}
    </button>
  )
}

function AuthPortal({
  mode,
  language,
  onLanguageChange,
  onModeChange,
  onClose,
  onAuthenticate,
  sessionError,
  tx,
}: {
  mode: AuthMode | null
  language: Language
  onLanguageChange: () => void
  onModeChange: (mode: AuthMode) => void
  onClose: () => void
  onAuthenticate: (
    mode: AuthMode,
    input: LoginInput | RegisterInput,
    persistence: SessionPersistence,
  ) => Promise<void>
  sessionError?: string | null
  tx: (key: string) => string
}) {
  const [signinPasswordVisible, setSigninPasswordVisible] = useState(false)
  const [registerPasswordVisible, setRegisterPasswordVisible] = useState(false)
  const [signinState, setSigninState] = useState<SubmitState>('idle')
  const [registerState, setRegisterState] = useState<SubmitState>('idle')
  const [signinError, setSigninError] = useState<string | null>(null)
  const [registerError, setRegisterError] = useState<string | null>(null)

  const submit = async (event: FormEvent<HTMLFormElement>, panel: AuthMode) => {
    event.preventDefault()
    const setState = panel === 'signin' ? setSigninState : setRegisterState
    const setError = panel === 'signin' ? setSigninError : setRegisterError
    const data = new FormData(event.currentTarget)
    const username = String(data.get('username') ?? '').trim()
    const password = String(data.get('password') ?? '')
    const persistence: SessionPersistence = 'session'
    setError(null)
    setState('loading')
    try {
      const input = panel === 'signin'
        ? { username, password }
        : { username, password, displayName: String(data.get('displayName') ?? '').trim() }
      await onAuthenticate(panel, input, persistence)
      setState('success')
    } catch (error) {
      setError(error instanceof Error ? error.message : tx('authFailed'))
      setState('idle')
    }
  }

  const submitLabel = (panel: AuthMode, state: SubmitState) => {
    if (panel === 'signin') {
      if (state === 'loading') return language === 'zh' ? '正在打开工作区' : language === 'ja' ? 'ワークスペースを開いています' : 'Opening workspace'
      if (state === 'success') return language === 'zh' ? '工作区已就绪' : language === 'ja' ? '準備ができました' : 'Workspace ready'
      return tx('enterWorkspace')
    }
    if (state === 'loading') return language === 'zh' ? '正在创建工作区' : language === 'ja' ? 'ワークスペースを作成中' : 'Creating workspace'
    if (state === 'success') return language === 'zh' ? '账户已就绪' : language === 'ja' ? 'アカウント準備完了' : 'Account ready'
    return tx('createWorkspace')
  }

  return (
    <section className={`auth-portal ${mode ? 'is-open' : ''}`} aria-hidden={!mode} aria-label="Account access">
      <div className="auth-layout">
        <aside className="auth-visual">
          {mode && <ParticleField variant="auth" />}
          <div className="auth-visual-top">
            <button className="wordmark-button" type="button" onClick={onClose}><BrandWordmark showMotto /></button>
            <button className="auth-back" type="button" onClick={onClose}>{tx('back')}</button>
          </div>
          <div className="auth-statement">
            <div className="eyebrow">{tx('privatePlane')}</div>
            <h2>{mode === 'register' ? tx('createLine1') : tx('welcomeLine1')}<br />{mode === 'register' ? tx('createLine2') : tx('welcomeLine2')}</h2>
            <p>{mode === 'register' ? tx('createCopy') : tx('welcomeCopy')}</p>
          </div>
          <div className="auth-signal" aria-hidden="true" />
        </aside>

        <div className="auth-form-side">
          <div className="auth-form-top" role="tablist" aria-label="Account mode">
            <LanguageButton language={language} onChange={onLanguageChange} />
            <button className={`mode-button ${mode === 'signin' ? 'is-active' : ''}`} type="button" onClick={() => onModeChange('signin')} role="tab">{tx('signIn')}</button>
            <button className={`mode-button ${mode === 'register' ? 'is-active' : ''}`} type="button" onClick={() => onModeChange('register')} role="tab">{tx('createAccount')}</button>
          </div>

          <form className={`auth-panel ${mode === 'signin' ? 'is-active' : ''}`} onSubmit={(event) => submit(event, 'signin')}>
            <div className="auth-kicker">{tx('accountAccess')}</div>
            <h1>{tx('welcomeBack')}</h1>
            <p className="auth-subtitle">{tx('continueControl')}</p>
            <label className="field">
              <span>{tx('identity')}</span>
              <input name="username" autoComplete="username" placeholder={tx('identityPlaceholder')} required />
            </label>
            <label className="field">
              <span>{tx('password')}</span>
              <input name="password" type={signinPasswordVisible ? 'text' : 'password'} autoComplete="current-password" placeholder={tx('passwordPlaceholder')} required />
              <button className="show-password" type="button" onClick={() => setSigninPasswordVisible((visible) => !visible)}>{tx(signinPasswordVisible ? 'hide' : 'show')}</button>
            </label>
            <div className="form-meta"><span>{tx('trustedBeta')}</span></div>
            {(signinError || sessionError) && <p className="auth-error" role="alert">{signinError || sessionError}</p>}
            <button className={`auth-submit state-${signinState}`} type="submit" disabled={signinState !== 'idle'}><span>{submitLabel('signin', signinState)}</span><span>→</span></button>
            <p className="auth-switch">{tx('newHere')} <button type="button" onClick={() => onModeChange('register')}>{tx('createAccount')}</button></p>
          </form>

          <form className={`auth-panel ${mode === 'register' ? 'is-active' : ''}`} onSubmit={(event) => submit(event, 'register')}>
            <div className="auth-kicker">{tx('createAccess')}</div>
            <h1>{tx('startBuilding')}</h1>
            <p className="auth-subtitle">{tx('createOrg')}</p>
            <label className="field"><span>{tx('displayName')}</span><input name="displayName" autoComplete="name" placeholder={tx('namePlaceholder')} required /></label>
            <label className="field"><span>{tx('email')}</span><input name="username" type="email" autoComplete="email" placeholder={tx('identityPlaceholder')} required /></label>
            <label className="field">
              <span>{tx('password')}</span>
              <input name="password" type={registerPasswordVisible ? 'text' : 'password'} minLength={10} autoComplete="new-password" placeholder={tx('newPasswordPlaceholder')} required />
              <button className="show-password" type="button" onClick={() => setRegisterPasswordVisible((visible) => !visible)}>{tx(registerPasswordVisible ? 'hide' : 'show')}</button>
            </label>
            <div className="form-meta"><label className="check"><input type="checkbox" required /><span>{tx('acceptTerms')}</span></label><span>{tx('trustedBeta')}</span></div>
            {registerError && <p className="auth-error" role="alert">{registerError}</p>}
            <button className={`auth-submit state-${registerState}`} type="submit" disabled={registerState !== 'idle'}><span>{submitLabel('register', registerState)}</span><span>→</span></button>
            <p className="auth-switch">{tx('already')} <button type="button" onClick={() => onModeChange('signin')}>{tx('signIn')}</button></p>
          </form>

          <div className="prototype-note">{tx('nativeSession')}</div>
        </div>
      </div>
    </section>
  )
}

function SessionLoading({ tx }: { tx: (key: string) => string }) {
  return (
    <main className="session-screen" aria-live="polite">
      <BrandWordmark />
      <div className="session-loading"><i />{tx('restoringSession')}</div>
    </main>
  )
}

function AuthenticatedHome({
  session,
  language,
  onLanguageChange,
  onLogout,
  tx,
}: {
  session: AuthenticatedSession
  language: Language
  onLanguageChange: () => void
  onLogout: () => Promise<void>
  tx: (key: string) => string
}) {
  const activeOrganization = session.organizations.find(
    ({ organization }) => organization.id === session.user.tenantId,
  )?.organization
  return (
    <main className="session-screen authenticated" id="app-shell">
      <header className="session-header">
        <BrandWordmark />
        <div>
          <LanguageButton language={language} onChange={onLanguageChange} />
          <button type="button" onClick={() => void onLogout()}>{tx('signOut')}</button>
        </div>
      </header>
      <section className="session-card">
        <span>{tx('nativeSession')}</span>
        <h1>{tx('sessionReady')}</h1>
        <dl>
          <div><dt>{tx('displayName')}</dt><dd>{session.user.displayName || session.user.username}</dd></div>
          <div><dt>{tx('organization')}</dt><dd>{activeOrganization?.name || session.user.tenantId}</dd></div>
          <div><dt>{tx('role')}</dt><dd>{session.user.tenantRole}</dd></div>
        </dl>
        <p>{tx('shellNext')}</p>
      </section>
    </main>
  )
}

export function App() {
  const auth = useAuthSession()
  const [path, setPath] = useState(() => window.location.pathname)
  const [chatNavigationCollapsed, setChatNavigationCollapsed] = useState(() => {
    const stored = window.localStorage.getItem('spaceagent-chat-nav-collapsed')
    return stored === null ? true : stored === 'true'
  })
  const [language, setLanguage] = useState<Language>(() => {
    const stored = window.localStorage.getItem('spaceagent-language') as Language | null
    if (stored && languageOrder.includes(stored)) return stored
    if (window.navigator.language.startsWith('zh')) return 'zh'
    if (window.navigator.language.startsWith('ja')) return 'ja'
    return 'en'
  })
  const [authMode, setAuthMode] = useState<AuthMode | null>(() => {
    if (window.location.hash === '#signin') return 'signin'
    if (window.location.hash === '#register') return 'register'
    return null
  })
  const [heroProgress, setHeroProgress] = useState(0)
  const [runStage, setRunStage] = useState(0)
  const [runStarted, setRunStarted] = useState(false)
  const heroProgressRef = useRef(0)
  const immersiveRef = useRef<HTMLElement>(null)
  const consoleRef = useRef<HTMLDivElement>(null)
  const tx = useCallback((key: string) => text(language, key), [language])

  useEffect(() => {
    const onPopState = () => setPath(window.location.pathname)
    window.addEventListener('popstate', onPopState)
    return () => window.removeEventListener('popstate', onPopState)
  }, [])

  useEffect(() => {
    document.documentElement.lang = language === 'zh' ? 'zh-CN' : language
    document.title = language === 'zh' ? 'SpaceAgent — 构建持续行动的智能系统' : language === 'ja' ? 'SpaceAgent — 動き続ける知性' : 'SpaceAgent — Build intelligence that keeps moving'
    window.localStorage.setItem('spaceagent-language', language)
  }, [language])

  useEffect(() => {
    let scheduled = false
    const update = () => {
      scheduled = false
      const section = immersiveRef.current
      if (!section) return
      const rect = section.getBoundingClientRect()
      const range = Math.max(1, rect.height - window.innerHeight)
      const progress = clamp(-rect.top / range)
      heroProgressRef.current = progress
      setHeroProgress(progress)
    }
    const onScroll = () => {
      if (scheduled) return
      scheduled = true
      window.requestAnimationFrame(update)
    }
    update()
    window.addEventListener('scroll', onScroll, { passive: true })
    window.addEventListener('resize', onScroll)
    return () => {
      window.removeEventListener('scroll', onScroll)
      window.removeEventListener('resize', onScroll)
    }
  }, [])

  useEffect(() => {
    const node = consoleRef.current
    if (!node) return
    const observer = new IntersectionObserver((entries) => {
      if (entries.some((entry) => entry.isIntersecting)) {
        setRunStarted(true)
        observer.disconnect()
      }
    }, { threshold: 0.25 })
    observer.observe(node)
    return () => observer.disconnect()
  }, [])

  useEffect(() => {
    if (!runStarted) return
    if (window.matchMedia('(prefers-reduced-motion: reduce)').matches) {
      setRunStage(3)
      return
    }
    const interval = window.setInterval(() => setRunStage((stage) => (stage + 1) % 4), 1550)
    return () => window.clearInterval(interval)
  }, [runStarted])

  useEffect(() => {
    document.body.style.overflow = authMode ? 'hidden' : ''
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') closeAuth()
    }
    document.addEventListener('keydown', onKeyDown)
    return () => {
      document.removeEventListener('keydown', onKeyDown)
      document.body.style.overflow = ''
    }
  }, [authMode])

  const changeLanguage = () => setLanguage((current) => languageOrder[(languageOrder.indexOf(current) + 1) % languageOrder.length])
  const toggleChatNavigation = () => setChatNavigationCollapsed((current) => {
    const next = !current
    window.localStorage.setItem('spaceagent-chat-nav-collapsed', String(next))
    return next
  })
  const navigate = (nextPath: string, replace = false) => {
    if (replace) window.history.replaceState(null, '', nextPath)
    else window.history.pushState(null, '', nextPath)
    setPath(nextPath)
  }
  const openAuth = (mode: AuthMode) => {
    setAuthMode(mode)
    window.history.replaceState(null, '', `${window.location.pathname}${window.location.search}#${mode}`)
  }
  const closeAuth = () => {
    setAuthMode(null)
    window.history.replaceState(null, '', window.location.pathname + window.location.search)
  }
  const authenticate = async (
    mode: AuthMode,
    input: LoginInput | RegisterInput,
    persistence: SessionPersistence,
  ) => {
    await auth.authenticate(mode, input, persistence)
    setAuthMode(null)
    navigate('/app')
  }
  const logout = async () => {
    await auth.logout()
    navigate('/', true)
  }

  useEffect(() => {
    if (!path.startsWith('/app') || auth.state.status !== 'anonymous') return
    navigate('/', true)
    setAuthMode('signin')
    window.history.replaceState(null, '', '/#signin')
  }, [auth.state.status, path])

  const openingOpacity = 1 - smooth(0.05, 0.18, heroProgress)
  const heroOpacity = smooth(0.72, 0.86, heroProgress) * (1 - smooth(0.95, 1, heroProgress))
  const navVisible = heroProgress > 0.65 || window.scrollY > window.innerHeight * 1.5
  const tasks = [
    [tx('task1'), tx('task1Copy'), tx('done'), 'done'],
    [tx('task2'), tx('task2Copy'), tx('done'), 'done'],
    [tx('task3'), tx('task3Copy'), tx('running'), 'active'],
    [tx('task4'), tx('task4Copy'), tx('queued'), ''],
  ]
  const runEvents = [
    [tx('event1'), tx('event1Copy'), '10:42:08'],
    [tx('event2'), tx('event2Copy'), '10:42:11'],
    [tx('event3'), tx('event3Copy'), '10:42:16'],
    [tx('event4'), tx('event4Copy'), '10:42:28'],
  ]
  const elapsedSeconds = 102 + runStage * 7
  const runTime = `00:${String(Math.floor(elapsedSeconds / 60)).padStart(2, '0')}:${String(elapsedSeconds % 60).padStart(2, '0')}`
  const principles = [
    [tx('intent'), tx('intentLine1'), tx('intentLine2'), tx('intentCopy')],
    [tx('execution'), tx('executionLine1'), tx('executionLine2'), tx('executionCopy')],
    [tx('memory'), tx('memoryLine1'), tx('memoryLine2'), tx('memoryCopy')],
  ]

  if (path.startsWith('/app')) {
    if (auth.state.status !== 'authenticated') return <SessionLoading tx={tx} />
    const session = auth.state.session
    const activeSection = path.startsWith('/app/organization')
      ? 'organization'
      : path.startsWith('/app/automation')
      ? 'automation'
      : path.startsWith('/app/memory')
      ? 'memory'
      : path.startsWith('/app/tracing')
      ? 'tracing'
      : path.startsWith('/app/mcp')
      ? 'mcp'
      : path.startsWith('/app/models')
      ? 'models'
      : path.startsWith('/app/knowledge')
      ? 'knowledge'
      : path.startsWith('/app/agents')
      ? 'agents'
      : path.startsWith('/app/project')
        ? 'project'
        : path.startsWith('/app/chat')
          ? 'chat'
          : 'overview'
    return (
      <ApplicationShell
        key={`${session.user.userId}:${session.user.tenantId}`}
        session={session}
        language={language}
        onLanguageChange={changeLanguage}
        onLogout={logout}
        onSwitchOrganization={auth.switchOrganization}
        activeSection={activeSection}
        sidebarCollapsed={chatNavigationCollapsed}
        onToggleNavigation={toggleChatNavigation}
        onNavigate={navigate}
        tx={tx}
      >
        <Suspense fallback={<div className="session-loading"><i />{tx('restoringSession')}</div>}>
          {activeSection === 'chat' && <ChatPage language={language} request={auth.request} openStream={auth.openStream} navigationCollapsed={chatNavigationCollapsed} onToggleNavigation={toggleChatNavigation} tx={tx} />}
          {activeSection === 'project' && <ProjectPage language={language} request={auth.request} openStream={auth.openStream} navigationCollapsed={chatNavigationCollapsed} onToggleNavigation={toggleChatNavigation} tx={tx} />}
          {activeSection === 'agents' && <AgentPage request={auth.request} userId={session.user.userId} tenantRole={session.user.tenantRole} tx={tx} />}
          {activeSection === 'automation' && <AutomationPage request={auth.request} tx={tx} />}
          {activeSection === 'memory' && <MemoryPage request={auth.request} userId={session.user.userId} tx={tx} />}
          {activeSection === 'knowledge' && <KnowledgePage request={auth.request} tx={tx} />}
          {activeSection === 'models' && <ModelResourcesPage request={auth.request} tenantRole={session.user.tenantRole} tx={tx} />}
          {activeSection === 'mcp' && <McpPage request={auth.request} tx={tx} />}
          {activeSection === 'tracing' && <TracingPage request={auth.request} initialSessionId={new URLSearchParams(window.location.search).get('session')} tx={tx} />}
          {activeSection === 'organization' && <OrganizationSettingsPage session={auth.state.session} request={auth.request} onSwitchOrganization={auth.switchOrganization} tx={tx} />}
          {activeSection === 'overview' && <OverviewPage language={language} request={auth.request} onNavigate={navigate} tx={tx} />}
        </Suspense>
      </ApplicationShell>
    )
  }

  return (
    <>
      <ParticleField progressRef={heroProgressRef} />
      <div className="noise" aria-hidden="true" />
      <nav className={`nav ${navVisible ? 'visible' : ''}`}>
        <a className="nav-brand" href="#top"><BrandWordmark /></a>
        <div className="nav-links"><a href="#product">{tx('product')}</a><a href="#principles">{tx('principles')}</a><a href="#system">{tx('system')}</a><a href="#access">{tx('access')}</a></div>
        <div className="nav-actions"><LanguageButton language={language} onChange={changeLanguage} /><button type="button" onClick={() => openAuth('signin')}>{tx('signIn')}</button><button className="nav-enter" type="button" onClick={() => openAuth('register')}>{tx('enter')}</button></div>
      </nav>

      <main id="top">
        <section className="immersive" ref={immersiveRef}>
          <div className="stage">
            <div className="brand-opening" style={{ opacity: openingOpacity, transform: `translateY(${-20 * smooth(0.05, 0.22, heroProgress)}px)` }}>
              <BrandWordmark className="opening-wordmark" showMotto />
              <div className="scroll-cue" style={{ opacity: 1 - smooth(0.01, 0.08, heroProgress) }}><span>{tx('scroll')}</span><span className="scroll-line" /></div>
            </div>
            <div className="hero" style={{ opacity: heroOpacity, pointerEvents: heroOpacity > 0.8 ? 'auto' : 'none' }}>
              <div className="hero-content"><h1>{tx('heroLine1')}<br />{tx('heroLine2')}</h1><p>{tx('heroCopy')}</p><div className="hero-actions"><button type="button" onClick={() => openAuth('register')}>{tx('start')}</button><a href="#product">{tx('seeHow')}</a></div></div>
            </div>
          </div>
        </section>

        <div className="story" id="product">
          <section className="principles-section" id="principles">
            <div className="principles-intro"><div className="eyebrow">{tx('durable')}</div><p>{tx('durableCopy')}</p></div>
            <div className="principle-list">{principles.map(([index, line1, line2, description]) => <article className="principle" key={index}><span>{index}</span><h2>{line1}<br />{line2}</h2><p>{description}</p></article>)}</div>
          </section>

          <section className="system-section" id="system">
            <div className="system-head"><h2>{tx('systemLine1')}<br />{tx('systemLine2')}</h2><div><p>{tx('systemCopy')}</p><span className="system-status"><i />{tx('liveDurable')}</span></div></div>
            <div className="console" ref={consoleRef}>
              <aside className="console-sidebar"><div className="console-brand"><BrandWordmark /><span>SA/01</span></div><div className="console-nav"><span className="active">{tx('work')} <small>04</small></span><span>{tx('agents')} <small>08</small></span><span>{tx('resources')} <small>12</small></span><span>{tx('operations')} <small>03</small></span></div><div className="console-user">Orbit Lab / Core team</div></aside>
              <div className="console-main">
                <div className="console-top"><strong>{tx('project')}</strong><div><span className={`status-pill ${runStage === 3 ? 'waiting' : ''}`}>{runStage === 3 ? tx('waiting') : tx('runActive')}</span><span className="status-pill">Qwen pool</span></div></div>
                <div className="task-line">{tx('task')}</div>
                <div className="workspace">
                  <section className="task-panel"><header><span>{tx('plan')}</span><span>60%</span></header><div className="task-items">{tasks.map(([title, description, status, state]) => <div className={`task-item ${state}`} key={title}><i /><div><b>{title}</b><p>{description}</p></div><em>{status}</em></div>)}</div></section>
                  <section className="run-panel"><header><span>{tx('timeline')}</span><span>{runTime}</span></header><div className="run-chain">{[tx('context'), tx('model'), tx('tool'), tx('approval')].map((label, index) => <div className="chain-fragment" key={label}><span className={index < runStage ? 'complete' : index === runStage ? 'active' : ''}>{label}</span>{index < 3 && <i className={index < runStage ? 'complete' : ''} />}</div>)}</div><div className="run-stream"><span className="run-progress" style={{ transform: `scaleY(${runStage / 3})` }} />{runEvents.map(([title, description, time], index) => <div className={`run-event ${index < runStage ? 'complete' : index === runStage ? 'active' : ''} ${index === 3 ? 'approval' : ''}`} key={title}><time>{time}</time><div><strong>{title}</strong><span>{description}</span></div></div>)}</div></section>
                </div>
              </div>
            </div>
          </section>

          <section className="capabilities"><div className="cap-head"><div><div className="eyebrow">{tx('operatingLayer')}</div><h2>{tx('controlLine1')}<br />{tx('controlLine2')}</h2></div><p>{tx('controlCopy')}</p></div><div className="cap-grid">{[[tx('cap1'), tx('cap1Copy')], [tx('cap2'), tx('cap2Copy')], [tx('cap3'), tx('cap3Copy')]].map(([title, description], index) => <article className="cap-card" key={title}><span>0{index + 1}</span><div className="cap-orbit" /><h3>{title}</h3><p>{description}</p></article>)}</div></section>
          <section className="closing" id="access"><div className="closing-copy"><h2>{tx('closingLine1')}<br /><span>{tx('closingLine2')}</span></h2><button type="button" onClick={() => openAuth('register')}>{tx('enterSpaceAgent')}</button></div><footer><span>SpaceAgent / 2026</span><span>{tx('stack')}</span><span>{tx('trustedBeta')}</span></footer></section>
        </div>
      </main>

      <AuthPortal
        mode={authMode}
        language={language}
        onLanguageChange={changeLanguage}
        onModeChange={openAuth}
        onClose={closeAuth}
        onAuthenticate={authenticate}
        sessionError={auth.state.status === 'anonymous' ? auth.state.error : null}
        tx={tx}
      />
    </>
  )
}
