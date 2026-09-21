import { expect, it } from 'vitest'
import { markdownBlocks } from './markdownBlocks'

it('keeps completed prose blocks byte-identical while only the tail changes', () => {
  const prefix = '## Title\n\nFirst **paragraph**.\n\n'
  const first = markdownBlocks(prefix + 'new')
  const next = markdownBlocks(prefix + 'new tokens')
  expect(first.slice(0,-1)).toEqual(next.slice(0,-1))
  expect(next.join('')).toBe(prefix + 'new tokens')
})
it('does not split blank lines inside fences or continued list groups', () => {
  const value = '```ts\nconst a = 1\n\nconst b = 2\n```\n\n'
  expect(markdownBlocks(value)).toEqual([value])
  const list = '- first\n\n  continued\n- next\n\n'
  expect(markdownBlocks(list)).toEqual([list])
})
it('preserves document-wide reference definitions and all characters', () => {
  const value = '[guide][ref]\n\n[ref]: https://example.com\n'
  expect(markdownBlocks(value)).toEqual([value])
  for (const content of ['', '\n\n', '### title\n\nText', '| a | b |\n| - | - |\n\n']) expect(markdownBlocks(content).join('')).toBe(content)
})
