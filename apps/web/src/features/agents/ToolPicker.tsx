import { useState } from 'react'
import type { RuntimeCapability } from './types'
import { canToggleCapability } from './toolPresentation'
import { filterTools, toolCategories, type ToolCategory, type ToolFilter } from './toolPickerModel'
import './toolPicker.css'

type Props = { tools: RuntimeCapability[]; selected: string[]; disabled?: boolean;
  onToggle: (id: string) => void; tx: (key: string) => string }

export function ToolPicker({ tools, selected, disabled = false, onToggle, tx }: Props) {
  const [query, setQuery] = useState('')
  const [category, setCategory] = useState<ToolCategory>('All')
  const [filter, setFilter] = useState<ToolFilter>('All')
  const visible = filterTools(tools, selected, query, category, filter)
  const toggle = (tool: RuntimeCapability) => {
    if (!disabled && canToggleCapability(tool, selected.includes(tool.id))) onToggle(tool.id)
  }
  return <div className="tool-picker">
    <div className="tool-picker-summary"><b>{tx('toolPickerSelected')} · {selected.length}</b>
</div>
    <div className="tool-picker-chips">
      {selected.map(id => {
        const tool = tools.find(tool => tool.id === id)
        const name = tool?.name || id
        return <span key={id} className={!tool?.available ? 'tool-chip-unavailable' : ''}>
          <span>{name}{!tool && ` · ${tx('toolPickerMissing')}`}{tool && !tool.available && ` · ${tx('unavailableTool')}`}</span>
          <button type="button" disabled={disabled} aria-label={`${tx('toolPickerRemove')}: ${name}`}
            onClick={() => { if (!disabled) onToggle(id) }}>×</button>
        </span>
      })}
      {!selected.length && <p>{tx('toolPickerEmpty')}</p>}
    </div>
      <div className="tool-picker-controls">
        <input type="search" maxLength={200} value={query} aria-label={tx('toolPickerSearch')} placeholder={tx('toolPickerSearch')}
          onChange={event => setQuery(event.target.value)} onKeyDown={event => { if (event.key === 'Enter') event.preventDefault() }} />
        <div><label>{tx('toolPickerCategory')}<select value={category} onChange={event => setCategory(event.target.value as ToolCategory)}>
          {toolCategories.map(value => <option key={value} value={value}>{tx(`toolPicker${value}`)}</option>)}
        </select></label><label>{tx('toolPickerFilter')}<select value={filter} onChange={event => setFilter(event.target.value as ToolFilter)}>
          {(['All', 'Selected', 'Available'] as const).map(value => <option key={value} value={value}>{tx(`toolPicker${value}`)}</option>)}
        </select></label></div>
        <small role="status">{tx('toolPickerResults')} · {visible.length}</small>
      </div>
      <div className="tool-picker-list">
        {visible.map(tool => {
          const checked = selected.includes(tool.id)
          return <article key={tool.id} className={checked ? 'selected' : ''} onClick={event => {
            if (event.target instanceof Element && !event.target.closest('label, input, details, button')) toggle(tool)
          }}>
            <label className="tool-picker-option"><input type="checkbox" checked={checked}
              disabled={disabled || !canToggleCapability(tool, checked)} onChange={() => toggle(tool)} />
              <span><b>{tool.name}</b><span className="tool-picker-description">{tool.description}</span></span></label>
            <div className="tool-picker-badges"><span className={!tool.readOnly ? 'mutating' : ''}>{tx(tool.readOnly ? 'readOnlyTool' : 'mutatingTool')}</span>
              {tool.requiresNetwork && <span>{tx('networkTool')}</span>}{tool.requiresWorkspace && <span>{tx('workspaceTool')}</span>}</div>
            {!tool.available && <p className="tool-picker-unavailable">{tx('toolPickerUnavailable')}</p>}
            <details><summary>{tx('toolPickerDetails')}</summary><p>{tool.description}</p><dl><dt>ID</dt><dd>{tool.id}</dd><dt>Mode</dt><dd>{tool.executionMode}</dd></dl></details>
          </article>
        })}
        {!visible.length && <p>{tx('toolPickerNoMatch')}</p>}
      </div>
      <p className="tool-picker-hint">{tx('toolPickerDraft')}</p>
  </div>
}
