import { useEffect, useState } from 'react'
import './reasoning.css'

type Props = {
  content: string
  open: boolean
  live: boolean
  startedAt: number
  elapsedMs?: number
  onToggle: () => void
  tx: (key: string) => string
}

const duration = (milliseconds: number) => {
  const totalSeconds = Math.max(1, Math.floor(milliseconds / 1000))
  const minutes = Math.floor(totalSeconds / 60)
  const seconds = totalSeconds % 60
  return minutes ? `${minutes}m ${seconds}s` : `${seconds}s`
}

export function ReasoningTrace({ content, open, live, startedAt, elapsedMs, onToggle, tx }: Props) {
  const [clock, setClock] = useState(() => Date.now())
  useEffect(() => {
    if (!live) return
    const timer = window.setInterval(() => setClock(Date.now()), 500)
    return () => window.clearInterval(timer)
  }, [live])
  if (!content) return null
  const elapsed = elapsedMs ?? Math.max(0, clock - startedAt)
  return <section className={`reasoning-trace ${open ? 'open' : ''} ${live ? 'live' : ''}`}>
    <button type="button" aria-expanded={open} onClick={onToggle}>
      <i />
      <span>{live ? tx('reasoningLive') : tx('reasoningComplete')} · {duration(elapsed)}</span>
      <b>{open ? '⌃' : '⌄'}</b>
    </button>
    <div className="reasoning-trace-body"><p>{content}</p></div>
  </section>
}
