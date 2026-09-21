import { describe, expect, it } from 'vitest'
import { DEFAULT_MODEL_CONTEXT_TOKENS, modelContextTokens } from './modelDefaults'

describe('model context defaults', () => {
  it('uses 200K only when the form value is absent', () => {
    expect(DEFAULT_MODEL_CONTEXT_TOKENS).toBe(200_000)
    expect(modelContextTokens(null)).toBe(200_000)
    expect(modelContextTokens('')).toBe(200_000)
    expect(modelContextTokens('131072')).toBe(131_072)
  })
})
