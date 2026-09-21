import { describe, expect, it } from 'vitest'
import { cleanupStepTone, deletionBlockers, isDeletionEligible, shortEvidenceId } from './adminViewModel'

describe('Admin evidence view model', () => {
  it('keeps only bounded deletion blocker fields', () => {
    expect(deletionBlockers({ blockers: [
      { code: 'ACTIVE_RUNTIME_BLOCKER', resourceType: 'RUNTIME', count: 2, secret: 'ignored' },
      null,
      'invalid',
    ] })).toEqual([{ code: 'ACTIVE_RUNTIME_BLOCKER', resourceType: 'RUNTIME', count: 2 }])
  })

  it('formats nullable evidence identities without throwing', () => {
    expect(shortEvidenceId(null, 'SYSTEM')).toBe('SYSTEM')
    expect(shortEvidenceId('1234567890')).toBe('12345678')
  })

  it('maps durable cleanup terminal states to visible tones', () => {
    expect(cleanupStepTone('COMPLETED')).toBe('completed')
    expect(cleanupStepTone('BLOCKED')).toBe('blocked')
    expect(cleanupStepTone('PENDING')).toBe('pending')
  })

  it('enables deletion only after an explicitly eligible successful preflight', () => {
    expect(isDeletionEligible({ operation: 'USER_DELETION_PREFLIGHT', state: 'SUCCEEDED', result: { eligible: true } })).toBe(true)
    expect(isDeletionEligible({ operation: 'USER_DELETION_PREFLIGHT', state: 'SUCCEEDED', result: { eligible: false } })).toBe(false)
    expect(isDeletionEligible({ operation: 'USER_DELETION_REQUEST', state: 'SUCCEEDED', result: { eligible: true } })).toBe(false)
  })
})
