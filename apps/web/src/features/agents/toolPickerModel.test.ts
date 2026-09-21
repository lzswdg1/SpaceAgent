import { describe, expect, it } from 'vitest'
import { createElement } from 'react'
import { renderToStaticMarkup } from 'react-dom/server'
import { ToolPicker } from './ToolPicker'
import { filterTools, toolCategory } from './toolPickerModel'
import { messages } from '../../copy'
import type { RuntimeCapability } from './types'

const make = (id: string, extra: Partial<RuntimeCapability> = {}): RuntimeCapability => ({
  id, name: id, description: 'Tool description', executionMode: 'TEST', available: true,
  readOnly: true, requiresNetwork: false, requiresWorkspace: false, inputSchema: {}, ...extra,
})
describe('compact tool picker', () => {
  it('classifies tools without deriving or granting permissions', () => {
    for (const [id, group] of [['web_search','Network'], ['http_fetch','Network'], ['knowledge_search','Knowledge'],
      ['file_read','Files'], ['document_write','Files'], ['git_diff','Git'], ['github_repository','Mcp'],
      ['mcp_dynamic_tool','Mcp'], ['coding_command','Coding'], ['echo','Other']]) {
      expect(toolCategory(make(id))).toBe(group)
    }
  })
  it('combines literal search, category and selected/available filters without changing selections', () => {
    const tools = [make('http_fetch', { description: 'Read a web page' }), make('file_read', { available: false })]
    const selected = ['file_read']
    expect(filterTools(tools, selected, 'WEB PAGE', 'Network', 'Available').map(t => t.id)).toEqual(['http_fetch'])
    expect(filterTools(tools, selected, '', 'All', 'Selected').map(t => t.id)).toEqual(['file_read'])
    expect(filterTools(tools, selected, '[', 'All', 'All')).toEqual([])
    expect(selected).toEqual(['file_read'])
  })
  it.each([0, 1, 2])('renders localized labels, unknown selected IDs and disabled controls (%s)', language => {
    const html = renderToStaticMarkup(createElement(ToolPicker, { tools: [make('file_read')], selected: ['removed'],
      disabled: true, onToggle: () => {}, tx: key => messages[key]?.[language] || key }))
    expect(html).toContain(messages.toolPickerSelected[language])
    expect(html).toContain(messages.toolPickerMissing[language])
    expect(html).toContain('disabled=""')
    expect(html).toContain(messages.toolPickerDraft[language])
  })
})
