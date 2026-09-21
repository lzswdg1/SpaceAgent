import { useCallback, useEffect, useMemo, useRef, useState, type FormEvent } from 'react'
import type { AuthenticatedRequest } from '../overview/types'
import { archiveAutomationSchedule, createAutomationSchedule, listAutomationAgents, listAutomationExecutions, listAutomationSchedules, pauseAutomationSchedule, resumeAutomationSchedule, triggerAutomationSchedule, updateAutomationSchedule } from './automationApi'
import type { AutomationAgent, AutomationExecution, AutomationSchedule, AutomationScheduleType } from './types'
import './automation.css'

type Props = { request: AuthenticatedRequest; tx: (key: string) => string }
type Confirmation = { kind: 'trigger' | 'archive'; schedule: AutomationSchedule }

const stamp = (value: string | null) => value ? new Date(value).toLocaleString() : '—'

export function AutomationPage({ request, tx }: Props) {
  const scheduleSequence = useRef(0)
  const executionSequence = useRef(0)
  const [agents, setAgents] = useState<AutomationAgent[]>([])
  const [selectedAgentId, setSelectedAgentId] = useState('')
  const [schedules, setSchedules] = useState<AutomationSchedule[]>([])
  const [selectedScheduleId, setSelectedScheduleId] = useState('')
  const [executions, setExecutions] = useState<AutomationExecution[]>([])
  const [editor, setEditor] = useState<AutomationSchedule | 'new' | null>(null)
  const [editorType, setEditorType] = useState<AutomationScheduleType>('one_time')
  const [confirmation, setConfirmation] = useState<Confirmation | null>(null)
  const [loading, setLoading] = useState(true)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const selectedAgent = useMemo(
    () => agents.find(({ id }) => id === selectedAgentId) ?? null,
    [agents, selectedAgentId],
  )
  const selectedSchedule = useMemo(
    () => schedules.find(({ id }) => id === selectedScheduleId) ?? null,
    [schedules, selectedScheduleId],
  )

  const loadSchedules = useCallback(async (agentId: string, preferredId?: string) => {
    const sequence = ++scheduleSequence.current
    setLoading(true); setError(null)
    try {
      const rows = await listAutomationSchedules(request, agentId)
      if (sequence !== scheduleSequence.current) return
      setSchedules(rows)
      setSelectedScheduleId((current) => {
        const candidate = preferredId ?? current
        return rows.some(({ id }) => id === candidate) ? candidate : rows[0]?.id ?? ''
      })
    } catch (reason) {
      if (sequence === scheduleSequence.current) {
        setError(reason instanceof Error ? reason.message : tx('automationLoadFailed'))
        setSchedules([]); setSelectedScheduleId('')
      }
    } finally { if (sequence === scheduleSequence.current) setLoading(false) }
  }, [request, tx])

  const loadExecutions = useCallback(async (agentId: string, scheduleId: string) => {
    const sequence = ++executionSequence.current
    try {
      const rows = await listAutomationExecutions(request, agentId, scheduleId, 20)
      if (sequence === executionSequence.current) setExecutions(rows)
    } catch (reason) {
      if (sequence === executionSequence.current) {
        setExecutions([])
        setError(reason instanceof Error ? reason.message : tx('automationExecutionsLoadFailed'))
      }
    }
  }, [request, tx])

  useEffect(() => {
    let active = true
    setLoading(true); setError(null)
    listAutomationAgents(request).then((rows) => {
      if (!active) return
      const usable = rows.filter(({ status }) => status !== 'ARCHIVED')
      setAgents(usable)
      setSelectedAgentId((current) => usable.some(({ id }) => id === current) ? current : usable[0]?.id ?? '')
    }).catch((reason) => active && setError(reason instanceof Error ? reason.message : tx('automationLoadFailed')))
      .finally(() => active && setLoading(false))
    return () => { active = false }
  }, [request, tx])

  useEffect(() => {
    setSchedules([]); setSelectedScheduleId(''); setExecutions([]); setEditor(null); setConfirmation(null)
    if (selectedAgentId) void loadSchedules(selectedAgentId)
  }, [loadSchedules, selectedAgentId])

  useEffect(() => {
    setExecutions([])
    if (selectedAgentId && selectedScheduleId) void loadExecutions(selectedAgentId, selectedScheduleId)
  }, [loadExecutions, selectedAgentId, selectedScheduleId])

  const submitSchedule = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    if (!selectedAgent || !editor || busy) return
    const data = new FormData(event.currentTarget)
    const type = editor === 'new' ? editorType : editor.type
    const scheduledValue = String(data.get('scheduledAt') ?? '')
    const scheduledAt = type === 'one_time' && scheduledValue
      ? new Date(scheduledValue).toISOString() : null
    const cronExpr = type === 'periodic' ? String(data.get('cronExpr') ?? '').trim() : null
    setBusy(true); setError(null)
    try {
      let saved: AutomationSchedule
      if (editor === 'new') {
        saved = await createAutomationSchedule(request, selectedAgent.id, {
          description: String(data.get('description') ?? '').trim(),
          prompt: String(data.get('prompt') ?? '').trim(), type, cronExpr, scheduledAt,
          timezone: String(data.get('timezone') ?? '').trim(),
          maxRetries: Number(data.get('maxRetries')),
        })
      } else {
        saved = await updateAutomationSchedule(request, selectedAgent.id, editor.id, {
          description: String(data.get('description') ?? '').trim(),
          prompt: String(data.get('prompt') ?? '').trim(), cronExpr, scheduledAt,
          timezone: String(data.get('timezone') ?? '').trim(),
          maxRetries: Number(data.get('maxRetries')), expectedRevision: editor.revision,
        })
      }
      setEditor(null)
      await loadSchedules(selectedAgent.id, saved.id)
    } catch (reason) { setError(reason instanceof Error ? reason.message : tx('automationSaveFailed')) }
    finally { setBusy(false) }
  }

  const transitionSchedule = async (action: 'pause' | 'resume') => {
    if (!selectedAgent || !selectedSchedule || busy) return
    setBusy(true); setError(null)
    try {
      const saved = action === 'pause'
        ? await pauseAutomationSchedule(request, selectedAgent.id, selectedSchedule.id)
        : await resumeAutomationSchedule(request, selectedAgent.id, selectedSchedule.id)
      await loadSchedules(selectedAgent.id, saved.id)
    } catch (reason) { setError(reason instanceof Error ? reason.message : tx('automationActionFailed')) }
    finally { setBusy(false) }
  }

  const runConfirmedAction = async () => {
    if (!selectedAgent || !confirmation || busy) return
    setBusy(true); setError(null)
    try {
      if (confirmation.kind === 'archive') {
        await archiveAutomationSchedule(request, selectedAgent.id, confirmation.schedule.id)
        setConfirmation(null)
        await loadSchedules(selectedAgent.id)
      } else {
        const execution = await triggerAutomationSchedule(
          request, selectedAgent.id, confirmation.schedule.id, crypto.randomUUID(),
        )
        setConfirmation(null)
        setSelectedScheduleId(confirmation.schedule.id)
        setExecutions((current) => [execution, ...current.filter(({ id }) => id !== execution.id)].slice(0, 20))
        await loadExecutions(selectedAgent.id, confirmation.schedule.id)
      }
    } catch (reason) { setError(reason instanceof Error ? reason.message : tx('automationActionFailed')) }
    finally { setBusy(false) }
  }

  return <section className="automation-page">
    <header className="automation-heading"><div><span>AUTOMATION / SCHEDULED TASKS</span><h1>{tx('automationControl')}</h1><p>{tx('automationControlHint')}</p></div><button disabled={!selectedAgent || busy} onClick={() => { setEditorType('one_time'); setEditor('new') }}>{tx('newAutomation')}</button></header>
    {error && <div className="automation-error" role="alert"><span>{error}</span><button onClick={() => setError(null)}>×</button></div>}
    <div className="automation-toolbar"><label><span>{tx('agent')}</span><select value={selectedAgentId} disabled={loading || busy} onChange={(event) => setSelectedAgentId(event.target.value)}>{!agents.length && <option value="">{tx('noAgents')}</option>}{agents.map((agent) => <option key={agent.id} value={agent.id}>{agent.name}</option>)}</select></label><button disabled={!selectedAgent || loading} onClick={() => selectedAgent && void loadSchedules(selectedAgent.id, selectedScheduleId)}>{tx('refresh')}</button></div>
    <div className="automation-workspace">
      <section className="automation-schedules"><header><b>{tx('automationSchedules')}</b><span>{schedules.length.toString().padStart(2, '0')}</span></header><div>{schedules.map((schedule) => <button key={schedule.id} className={schedule.id === selectedScheduleId ? 'active' : ''} onClick={() => setSelectedScheduleId(schedule.id)}><span><b>{schedule.description}</b><small>{schedule.type.toUpperCase()} · REV {schedule.revision}</small></span><em className={`state-${schedule.status}`}>{schedule.status.toUpperCase()}</em><small>{stamp(schedule.nextRunAt)}</small></button>)}{!loading && !schedules.length && <div className="automation-empty">{tx('noAutomationSchedules')}</div>}</div></section>
      <section className="automation-inspector"><header><div><span>AUTOMATION / {selectedSchedule?.id.slice(0, 8) || '—'}</span><h2>{selectedSchedule?.description || tx('selectAutomation')}</h2></div>{selectedSchedule && <em>{selectedSchedule.status.toUpperCase()}</em>}</header>{selectedSchedule && <><dl><div><dt>{tx('scheduleType')}</dt><dd>{selectedSchedule.type.toUpperCase()}</dd></div><div><dt>{tx('nextRun')}</dt><dd>{stamp(selectedSchedule.nextRunAt)}</dd></div><div><dt>{tx('runCount')}</dt><dd>{selectedSchedule.runCount}</dd></div><div><dt>{tx('lastRun')}</dt><dd>{stamp(selectedSchedule.lastRunAt)}</dd></div></dl><div className="automation-actions"><button disabled={busy} onClick={() => { setEditorType(selectedSchedule.type); setEditor(selectedSchedule) }}>{tx('edit')}</button>{selectedSchedule.status === 'active' && <button disabled={busy} onClick={() => void transitionSchedule('pause')}>{tx('pause')}</button>}{selectedSchedule.status === 'paused' && <button disabled={busy} onClick={() => void transitionSchedule('resume')}>{tx('resume')}</button>}{(selectedSchedule.status === 'active' || selectedSchedule.status === 'paused') && <button disabled={busy} onClick={() => setConfirmation({ kind: 'trigger', schedule: selectedSchedule })}>{tx('runNow')}</button>}{selectedSchedule.status !== 'archived' && <button className="danger" disabled={busy} onClick={() => setConfirmation({ kind: 'archive', schedule: selectedSchedule })}>{tx('archive')}</button>}</div><section className="automation-executions"><header><b>{tx('automationExecutions')}</b><button disabled={busy} onClick={() => void loadExecutions(selectedSchedule.agentId, selectedSchedule.id)}>↻</button></header>{executions.map((execution) => <article key={execution.id}><span><b>{execution.status.toUpperCase()}</b><small>{stamp(execution.createdAt)}</small></span><dl><div><dt>TOKENS</dt><dd>{execution.tokenUsage.inputTokens + execution.tokenUsage.outputTokens}</dd></div><div><dt>RUN</dt><dd>{execution.agentRunId?.slice(0, 8) || '—'}</dd></div></dl>{execution.approvalId && <small>{tx('approvalRequired')} · {execution.approvalId}</small>}{execution.error && <small className="bad">{execution.error}</small>}</article>)}{!executions.length && <div className="automation-empty">{tx('noAutomationExecutions')}</div>}</section></>}</section>
    </div>

    {editor && selectedAgent && <div className="automation-modal" role="dialog" aria-modal="true"><form className="automation-dialog" onSubmit={submitSchedule}><header><div><span>AUTOMATION / {editor === 'new' ? 'NEW' : `REV ${editor.revision}`}</span><h2>{tx(editor === 'new' ? 'newAutomation' : 'editAutomation')}</h2></div><button type="button" onClick={() => setEditor(null)}>×</button></header><label><span>{tx('description')}</span><input name="description" maxLength={200} defaultValue={editor === 'new' ? '' : editor.description} required autoFocus /></label><label><span>{tx('automationPrompt')}</span><textarea name="prompt" maxLength={32000} defaultValue={editor === 'new' ? '' : editor.prompt} required /></label><div className="automation-form-grid"><label><span>{tx('scheduleType')}</span><select value={editor === 'new' ? editorType : editor.type} disabled={editor !== 'new'} onChange={(event) => setEditorType(event.target.value as AutomationScheduleType)}><option value="one_time">ONE TIME</option><option value="periodic">PERIODIC</option></select></label><label><span>{tx('timezone')}</span><input name="timezone" maxLength={80} defaultValue={editor === 'new' ? Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC' : editor.timezone} required /></label><label><span>{tx('maxRetries')}</span><input name="maxRetries" type="number" min="0" max="10" defaultValue={editor === 'new' ? 1 : editor.maxRetries} required /></label></div>{(editor === 'new' ? editorType : editor.type) === 'one_time' ? <label><span>{tx('executeAt')}</span><input name="scheduledAt" type="datetime-local" defaultValue={editor !== 'new' && editor.scheduledAt ? new Date(new Date(editor.scheduledAt).getTime() - new Date(editor.scheduledAt).getTimezoneOffset() * 60000).toISOString().slice(0, 16) : ''} required /></label> : <label><span>{tx('cronExpression')}</span><input name="cronExpr" maxLength={120} defaultValue={editor === 'new' ? '0 0 9 * * *' : editor.cronExpr || ''} required /></label>}<button className="submit" disabled={busy}>{busy ? tx('saving') : tx('save')}</button></form></div>}
    {confirmation && <div className="automation-modal" role="dialog" aria-modal="true"><div className="automation-dialog compact"><span>{tx('systemConfirmation')}</span><h2>{tx(confirmation.kind === 'trigger' ? 'runAutomationNow' : 'archiveAutomation')}</h2><p>{confirmation.schedule.description}</p><small>{tx(confirmation.kind === 'trigger' ? 'automationRunWarning' : 'automationArchiveWarning')}</small><div><button disabled={busy} onClick={() => setConfirmation(null)}>{tx('cancel')}</button><button className="danger" disabled={busy} onClick={() => void runConfirmedAction()}>{busy ? tx('processing') : tx('confirm')}</button></div></div></div>}
  </section>
}
