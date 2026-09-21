import { createElement } from 'react'
import { renderToStaticMarkup } from 'react-dom/server'
import { describe, expect, it, vi } from 'vitest'
import { TokenUsageCard, usageSegments } from './TokenUsageCard'
import type { MonitoringOverview } from './types'
import { formatUsageCost } from './usageCopy'
const overview = { totalInputTokens: 80, totalOutputTokens: 20, totalCacheReadTokens: 30, totalCacheCreateTokens: 10, totalCostUsd: null, hasIncompleteCost: true } as MonitoringOverview

describe('authoritative usage display', () => {
  it('keeps cache usage in the total and does not manufacture a cost', () => {
    expect(usageSegments(overview)).toEqual([80, 20, 30, 10])
    const html = renderToStaticMarkup(createElement(TokenUsageCard, { overview, loading: false, range: '1d', onRange: vi.fn(), language: 'en', incompleteLabel: 'Incomplete pricing' }))
    expect(html).toContain('140 Tokens')
    expect(html).toContain('Cache read')
    expect(html).toContain('Incomplete pricing')
    expect(html).not.toContain('$0.00')
  })
  it.each(['zh', 'en', 'ja'] as const)('renders the three supported time options in %s', language => {
    const html = renderToStaticMarkup(createElement(TokenUsageCard, { overview, loading: false, range: 'all', onRange: vi.fn(), language, incompleteLabel: '' }))
    expect(html).toContain('value="1d"')
    expect(html).toContain('value="7d"')
    expect(html).toContain('value="all" selected=""')
    expect(html).not.toContain('value="today"')
  })
  it('distinguishes unknown, zero and sub-cent known costs', () => {
    expect(formatUsageCost(null)).toBe('—')
    expect(formatUsageCost(0)).toBe('$0.00')
    expect(formatUsageCost(0.001)).toBe('<$0.01')
    expect(formatUsageCost(1.2)).toBe('$1.20')
  })
  it('distinguishes unavailable evidence from a settled zero', () => {
    const html = renderToStaticMarkup(createElement(TokenUsageCard, { loading: true, range: '7d', onRange: vi.fn(), language: 'en', incompleteLabel: '' }))
    expect(html).toContain('Usage unavailable')
    expect(html).toContain('aria-busy="true"')
    expect(html).not.toContain('usage-marquee')
  })
})
