import { useState, type CSSProperties } from 'react'
import { Pause, Play } from 'lucide-react'
import { platformCopy } from '../../app/platformCopy'
import type { Language } from '../../copy'
import type { MonitoringOverview, UsageRange } from './types'
import { usageCopy, formatUsageCost } from './usageCopy'

export function usageSegments(overview: MonitoringOverview | undefined) {
  return [overview?.totalInputTokens ?? 0, overview?.totalOutputTokens ?? 0, overview?.totalCacheReadTokens ?? 0, overview?.totalCacheCreateTokens ?? 0].map(value => Math.max(0, value))
}
export function TokenUsageCard({ overview, loading, range, onRange, language, incompleteLabel }: {
  overview?: MonitoringOverview; loading: boolean; range: UsageRange; onRange: (range: UsageRange) => void; language: Language; incompleteLabel: string
}) {
  const text = platformCopy(language)
  const usageText = usageCopy(language)
  const [paused, setPaused] = useState(false)
  const values = usageSegments(overview), total = values.reduce((sum, value) => sum + value, 0)
  const labels = [text.input, text.output, text.cacheRead, text.cacheCreate]
  const format = new Intl.NumberFormat(language, { notation: 'compact', maximumFractionDigits: 1 })
  let offset = 0
  return <article className={`overview-card platform-token-card ${paused ? 'paused' : ''}`} aria-busy={loading}>
    <header><b>{text.usage}</b><select aria-label={usageText.range} value={range} onChange={event => onRange(event.target.value as UsageRange)}><option value="1d">{usageText.day}</option><option value="7d">{usageText.week}</option><option value="all">{usageText.all}</option></select></header>
    <div className="platform-token-ring" role="img" aria-label={overview ? `${total.toLocaleString()} Tokens` : text.usageError}>
      <svg viewBox="0 0 240 240" aria-hidden="true"><circle className="usage-track" cx="120" cy="120" r="96" /><g transform="rotate(-90 120 120)">{values.map((value, index) => {
        const amount = total ? value / total * 100 : 0, start = offset; offset += amount
        return <circle key={index} className={`usage-segment usage-color-${index}`} cx="120" cy="120" r="96" pathLength="100" style={{ '--segment': amount, strokeDashoffset: -start } as CSSProperties} />
      })}</g></svg>
      {total > 0 && <div className="usage-marquee" aria-hidden="true" />}
      <div className="usage-center"><span>{text.consumed}</span><strong>{overview ? format.format(total) : '—'}</strong><small>Tokens</small></div>
    </div>
    <dl className="usage-legend">{values.map((value,index) => <div key={index}><dt><i className={`usage-color-${index}`} />{labels[index]}</dt><dd>{overview ? value.toLocaleString() : '—'}</dd></div>)}</dl>
    <footer><div><span>{usageText.cost}</span><b>{formatUsageCost(overview?.totalCostUsd)}</b></div><button type="button" aria-label={paused ? text.play : text.pause} aria-pressed={paused} onClick={() => setPaused(value => !value)}>{paused ? <Play size={15} /> : <Pause size={15} />}</button></footer>
    {overview?.hasIncompleteCost && <p className="usage-incomplete">{incompleteLabel}</p>}
  </article>
}
