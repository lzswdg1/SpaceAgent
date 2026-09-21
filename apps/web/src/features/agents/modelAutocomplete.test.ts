import { describe, expect, it } from 'vitest'
import { matchModelOptions } from './modelAutocomplete'
import type { AgentModelOption } from './types'

const options: AgentModelOption[] = [
  { providerId: 'p1', providerName: '千问', modelId: 'qwen-plus', displayName: 'Qwen Plus', maxContextTokens: 32768 },
  { providerId: 'p1', providerName: '千问', modelId: 'deepseek-v4-pro-0813', displayName: 'DeepSeek V4 Pro', maxContextTokens: 1000000 },
]

describe('Agent model autocomplete', () => {
  it('matches a partial model query and returns its Provider binding', () => {
    expect(matchModelOptions(options, 'd')).toEqual([options[1]])
    expect(matchModelOptions(options, '千问')).toHaveLength(2)
  })

  it('escapes regular-expression metacharacters from user input', () => {
    expect(matchModelOptions(options, '[')).toEqual([])
  })
})
