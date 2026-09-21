import { errorText } from './errorText'
import { useState, type FormEvent } from 'react'
import type { AdminApi } from './adminApi'
import type { AdminSession, Language } from './types'

type Props = {
  api: AdminApi
  language: Language
  onLanguageChange: () => void
  theme: 'dark' | 'light'
  onThemeChange: () => void
  onAuthenticated: (session: AdminSession) => void
  tx: (key: string) => string
  initialError?: string | null
}

export function AuthGate({ api, language, onLanguageChange, theme, onThemeChange, onAuthenticated, tx, initialError }: Props) {
  const [loginName, setLoginName] = useState('')
  const [password, setPassword] = useState('')
  const [submitting, setSubmitting] = useState(false)
  const [error, setError] = useState<string | null>(initialError ?? null)

  const begin = async (event: FormEvent) => {
    event.preventDefault(); if (submitting) return; setSubmitting(true); setError(null)
    try { onAuthenticated(await api.login(loginName.trim(), password)) }
    catch (reason) { setError(reason instanceof Error ? reason.message : 'Authentication failed') }
    finally { setPassword(''); setSubmitting(false) }
  }

  return <main className="auth-gate">
    <section className="auth-form-panel"><span className="wordmark">SpaceAgent</span>
      <div className="auth-controls"><button type="button" onClick={onThemeChange} aria-label={tx("Toggle theme")}>{theme === 'dark' ? '☀' : '☾'}</button><button type="button" onClick={onLanguageChange} aria-label={tx("Change language")}>{language === 'zh' ? '中' : language === 'ja' ? '日' : 'EN'}</button></div>
      <form className="auth-form" onSubmit={begin}>
        <h2>{tx('loginTitle')}</h2><p>{tx('loginCopy')}</p>
        <label><span>{tx('loginName')}</span><input autoComplete="username" value={loginName} onChange={(event) => setLoginName(event.target.value)} required maxLength={120} /></label>
        <label><span>{tx('password')}</span><input type="password" autoComplete="current-password" value={password} onChange={(event) => setPassword(event.target.value)} required maxLength={512} /></label>
        {error && <div className="form-error" role="alert">{errorText(error, tx)}</div>}
        <button className="submit" disabled={submitting}>{submitting ? tx('loading') : tx('continue')}<b>→</b></button>
      </form>
      <div className="auth-boundary"><b>{tx('securityBoundary')}</b><span>{tx('securityBoundaryCopy')}</span></div>
    </section>
  </main>
}
