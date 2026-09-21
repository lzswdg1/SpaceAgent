import { describe, expect, it } from 'vitest'
import { hasMessagesBelow, shouldHydrateMessageHistory } from './chatViewport'

describe('Chat viewport continuity', () => {
  it('never replaces an active stream with a history reload', () => {
    expect(shouldHydrateMessageHistory('conversation-1', true)).toBe(false)
    expect(shouldHydrateMessageHistory('conversation-1', false)).toBe(true)
    expect(shouldHydrateMessageHistory(null, false)).toBe(false)
  })

  it('shows an explicit jump affordance without enabling automatic following', () => {
    expect(hasMessagesBelow(1200, 620, 500)).toBe(true)
    expect(hasMessagesBelow(1200, 200, 500)).toBe(true)
    expect(hasMessagesBelow(1200, 700, 500)).toBe(false)
    expect(hasMessagesBelow(1200, 695, 500)).toBe(false)
    expect(hasMessagesBelow(200, 0, 500)).toBe(false)
  })
})
