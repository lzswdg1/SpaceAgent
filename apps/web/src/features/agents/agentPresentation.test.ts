import { readFileSync } from 'node:fs'
import { describe, expect, it } from 'vitest'

const source = readFileSync(new URL('./AgentPage.tsx', import.meta.url), 'utf8')

describe('Agent current-configuration presentation', () => {
  it('keeps backend revisions internal instead of presenting version-like counters', () => {
    expect(source).not.toContain('R${agent.revision}')
    expect(source).not.toContain('REV ${selected.revision}')
    expect(source).not.toContain('REV {change.revision}')
    expect(source).not.toContain('R{change.baseAgentRevision}')
    expect(source).toContain('expectedRevision: change.revision')
  })

  it('renders approval controls only for the current pending edit', () => {
    expect(source).toContain("setChanges(rows.filter(({ state }) => state === 'PENDING'))")
    expect(source).toContain('pendingChanges.length > 0 && <button')
    expect(source).toContain('pendingChanges.length > 0 && <section')
    expect(source).not.toContain('changes.slice(0, 8).map')
  })
})
