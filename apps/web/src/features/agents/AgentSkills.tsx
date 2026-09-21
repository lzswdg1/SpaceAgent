import { useEffect, useRef, useState } from 'react'
import type { AuthenticatedRequest } from '../overview/types'
import type { RuntimeCapability } from './types'
import { createSkill, createSkillContent, currentSkillVersion, getSkill, listSkills, publishSkill,
  replaceSkillBinding, validSkillText, type Skill, type SkillInput } from './skillApi'
import './skills.css'

type Props = { request: AuthenticatedRequest; userId: string; selected: string[]; enabledTools: string[];
  tools: RuntimeCapability[]; disabled: boolean; onChange: (ids: string[]) => void; tx: (key: string) => string }
const blank = (): SkillInput => ({ name: '', description: '', instructions: '', requiredToolIds: [] })

export function AgentSkills({ request, userId, selected, enabledTools, tools, disabled, onChange, tx }: Props) {
  const [skills, setSkills] = useState<Skill[]>([]), [loading, setLoading] = useState(true)
  const [error, setError] = useState(false), [busy, setBusy] = useState(false)
  const [editor, setEditor] = useState<{ id: string | null; input: SkillInput } | null>(null)
  const [pending, setPending] = useState<{ id: string; version: string } | null>(null)
  const [uncertain, setUncertain] = useState(false), [notice, setNotice] = useState('')
  const sequence = useRef(0), running = useRef(false)
  useEffect(() => {
    const current = ++sequence.current
    listSkills(request).then(rows => { if (sequence.current === current) { setSkills(rows); setLoading(false) } })
      .catch(() => { if (sequence.current === current) { setError(true); setLoading(false) } })
    return () => { sequence.current++ }
  }, [request])
  const refresh = async () => {
    if (running.current) return
    const current = ++sequence.current
    setLoading(true); setError(false)
    try {
      const rows = await listSkills(request)
      if (sequence.current !== current) return
      setSkills(rows)
      if (uncertain) { setEditor(null); setPending(null); setUncertain(false) }
    } catch { if (sequence.current === current) setError(true) }
    finally { if (sequence.current === current) setLoading(false) }
  }
  const edit = (skill?: Skill) => {
    const draft = skill?.versions.find(v => v.status === 'DRAFT')
    const version = draft || (skill && currentSkillVersion(skill))
    setPending(draft && skill ? { id: skill.id, version: draft.id } : null)
    setEditor({ id: skill?.id || null, input: skill ? { name: skill.name, description: skill.description || '',
      instructions: version?.instructions || '', requiredToolIds: version?.requiredToolIds || [] } : blank() })
    setNotice(''); setUncertain(false)
  }
  const save = async () => {
    if (!editor || disabled || running.current || uncertain || !editor.input.name.trim() || !validSkillText(editor.input.instructions)) return
    running.current = true; setBusy(true); setNotice('')
    const current = sequence.current
    let publication = pending
    try {
      if (!publication) {
        if (editor.id) {
          const version = await createSkillContent(request, editor.id, editor.input)
          publication = { id: editor.id, version: version.id }
        } else {
          const skill = await createSkill(request, editor.input)
          publication = { id: skill.id, version: skill.versions[0].id }
        }
        if (sequence.current !== current) return
        setPending(publication)
      }
      // Read before resuming an ambiguous publish, so an already-applied write is not repeated.
      const authoritative = await getSkill(request, publication.id)
      const version = authoritative.versions.find(v => v.id === publication!.version)
      let published = authoritative
      if (version?.status === 'DRAFT') published = await publishSkill(request, publication.id, publication.version)
      else if (authoritative.currentVersionId !== publication.version || version?.status !== 'PUBLISHED') throw Error('conflict')
      if (sequence.current !== current) return
      setSkills(rows => [published, ...rows.filter(s => s.id !== published.id)])
      setEditor(null); setPending(null); setNotice('skillSavedBindHint')
    } catch {
      if (sequence.current !== current) return
      setNotice(publication ? 'skillResumeHint' : 'skillUncertainHint')
      if (!publication) setUncertain(true)
    } finally { running.current = false; if (sequence.current === current) setBusy(false) }
  }
  const importText = async (file?: File) => {
    if (!file || !editor || pending || busy) return
    if (file.size > 131072 || !/\.(md|txt)$/i.test(file.name)) { setNotice('skillFileInvalid'); return }
    const current = sequence.current, target = editor
    try {
      const text = new TextDecoder('utf-8', { fatal: true }).decode(await file.arrayBuffer()).replace(/^\uFEFF/, '')
      if (!validSkillText(text)) { if (sequence.current === current) setNotice('skillFileInvalid'); return }
      if (sequence.current === current) {
        setEditor(value => value === target ? { ...value, input: { ...value.input, instructions: text } } : value)
        setNotice('')
      }
    } catch { if (sequence.current === current) setNotice('skillFileInvalid') }
  }
  return <section className="agent-skills" aria-busy={loading || busy} onKeyDown={event => {
    if (event.key === 'Enter' && event.target instanceof HTMLInputElement && event.target.type !== 'checkbox') event.preventDefault()
  }}>
    <header><b>{tx('registeredSkills')} · {loading || error ? '—' : skills.length}</b><div>
      <button type="button" disabled={busy || loading} onClick={() => void refresh()}>{tx('skillRefresh')}</button>
      <button type="button" disabled={disabled || busy || uncertain} onClick={() => edit()}>{tx('skillCreate')}</button>
    </div></header>
    <p>{tx('skillBindingHint')}</p>
    {loading ? <p role="status">{tx('skillLoading')}</p> : error ? <p role="alert">{tx('skillLoadFailed')}</p> : <>
      <div className="skill-bound">{selected.map(id => {
        const skill = skills.find(s => s.versions.some(v => v.id === id))
        return <span key={id}>{skill?.name || id}{skill?.currentVersionId !== id && ` · ${tx('skillHistorical')}`}
          <button type="button" disabled={disabled || busy} aria-label={`${tx('skillUnbind')}: ${skill?.name || id}`}
            onClick={() => onChange(selected.filter(value => value !== id))}>×</button></span>
      })}</div>
      <div className="skill-catalog">{skills.filter(s => s.lifecycle === 'ACTIVE').map(skill => {
        const version = currentSkillVersion(skill), checked = !!version && selected.includes(version.id)
        const missing = version?.requiredToolIds.filter(id => !enabledTools.includes(id) || !tools.some(t => t.id === id && t.available)) || []
        return <article key={skill.id}><label><input type="checkbox" checked={checked}
          disabled={disabled || busy || (!checked && (!version || missing.length > 0 || (selected.length >= 16 && !skill.versions.some(v => selected.includes(v.id)))))}
          onChange={() => { if (version) onChange(checked ? selected.filter(id => id !== version.id) : replaceSkillBinding(selected, skill, version.id)) }} />
          <span><b>{skill.name}</b><small>{skill.description}</small></span></label>
          {!version && <small>{tx('skillDraft')}</small>}
          {missing.length > 0 && <small>{tx('skillMissingTools')}: {missing.map(id => tools.find(t => t.id === id)?.name || id).join(', ')}</small>}
          <details><summary>{tx('skillReadInstructions')}</summary><pre>{version?.instructions || skill.versions[0]?.instructions}</pre></details>
          {skill.ownerUserId === userId && <button type="button" disabled={disabled || busy || uncertain} onClick={() => edit(skill)}>{tx('skillEdit')}</button>}
        </article>
      })}</div>
      {!skills.length && <p>{tx('skillNoRegistered')}</p>}
      {skills.length >= 100 && <p>{tx('skillListBound')}</p>}
    </>}
    {notice && <p role="status">{tx(notice)}</p>}
    {editor && <div className="skill-editor"><label>{tx('skillName')}<input value={editor.input.name} maxLength={100} disabled={busy || !!editor.id || !!pending}
      onChange={e => setEditor({ ...editor, input: { ...editor.input, name: e.target.value } })} /></label>
      <label>{tx('description')}<input value={editor.input.description} maxLength={1000} disabled={busy || !!editor.id || !!pending}
        onChange={e => setEditor({ ...editor, input: { ...editor.input, description: e.target.value } })} /></label>
      <label>{tx('skillImportText')}<input type="file" accept=".md,.txt" disabled={busy || !!pending} onChange={e => { void importText(e.target.files?.[0]); e.target.value = '' }} /></label>
      <label>{tx('skillInstructions')}<textarea value={editor.input.instructions} disabled={busy || !!pending} maxLength={131072}
        onChange={e => setEditor({ ...editor, input: { ...editor.input, instructions: e.target.value } })} /></label>
      <small>{tx('skillPackageLimit')}</small>
      <fieldset disabled={busy || !!pending}><legend>{tx('skillRequiredTools')}</legend>{tools.map(tool => <label key={tool.id} className="skill-tool-check"><input type="checkbox" checked={editor.input.requiredToolIds.includes(tool.id)} disabled={!editor.input.requiredToolIds.includes(tool.id) && editor.input.requiredToolIds.length >= 32}
        onChange={() => setEditor({ ...editor, input: { ...editor.input, requiredToolIds: editor.input.requiredToolIds.includes(tool.id) ? editor.input.requiredToolIds.filter(id => id !== tool.id) : [...editor.input.requiredToolIds, tool.id] } })} />{tool.name}</label>)}</fieldset>
      <div><button type="button" disabled={busy || disabled || uncertain || !editor.input.name.trim() || !validSkillText(editor.input.instructions)} onClick={() => void save()}>{tx(pending ? 'skillResumePublish' : 'skillSave')}</button>
        <button type="button" disabled={busy} onClick={() => { setEditor(null); setPending(null) }}>{tx('cancel')}</button></div>
    </div>}
  </section>
}
