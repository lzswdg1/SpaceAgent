import { ConversationMessage } from '../chat/ConversationMessage'
import { listAgents } from '../chat/chatApi'
import { usePagedOptions } from '../../lib/usePagedOptions'
import { WorkspaceNavigation } from '../../app/WorkspaceNavigation'
import { useCallback, useEffect, useRef, useState, type ChangeEvent, type FormEvent, type KeyboardEvent } from 'react'
import type { Language } from '../../copy'
import { deleteConversation, listMessages, reconcileChatTool, resumeChatApproval, streamMessage } from '../chat/chatApi'
import type { ChatDone, ChatSuspended, Message, OpenStream, RuntimeEvent } from '../chat/types'
import { ApiError } from '../../lib/api'
import { startSerialPolling } from '../../lib/serialPolling'
import { ConversationSettings, type ConversationSettingsUpdate } from '../chat/ConversationSettings'
import { shouldSubmitComposer } from '../../lib/composerKeyboard'
import { useChatStreamOperation } from '../chat/useChatStreamOperation'
import { includeLinkedConversation, loadLinkedConversation } from '../chat/conversationLink'
import type { AuthenticatedRequest } from '../overview/types'
import { archiveProjectSource, archiveProjectWorkspace, captureProjectRecovery, controlProjectPlanExecution, createProjectConversation, createProjectRunHandoff, createProjectTask, createTaskPlan, decideProjectApproval, defineProjectPlanStepAssignment, enqueueProjectIntake, executeProjectPlan, getProjectPlanExecutionControl, getProjectPlanStepAssignment, importGithubMcpSource, listGithubRepositories, listProjectCodingJobs, listProjectIntakes, listProjectPlanExecutions, listProjectWorkspaces, listTaskPlans, loadGithubConnectionOptions, loadProjectContext, loadProjectIndex, provisionProjectWorkspace, reviewProjectIntake, setProjectConversationActiveTask, transitionProjectTask, transitionTaskPlan, updateProjectTask, type ProjectExecutionAction, type TaskPlanAction, type TaskTransition } from './projectApi'
import type { GithubConnectionOption, GithubMcpRepository, Project, ProjectCodingJob, ProjectConversation, ProjectDirectory, ProjectIntakeJob, ProjectPlanExecution, ProjectPlanExecutionControl, ProjectPlanStepAssignment, ProjectRunHandoff, RecoveryPackage, SourceRepository, Task, TaskPlan, TaskPlanDraftStep, Workspace } from './types'
import { ProjectNavigationTree } from './ProjectNavigationTree'
import { createProjectRoot, createGithubProjectRoot, deleteProjectRoot, listProjectRoots, prepareProjectRoot, renameProjectRoot } from './projectRootApi'
import { RepositoryWorkbench } from './RepositoryWorkbench'
import { repositoryBranchLabel } from './repositoryBranches'
import { useMessageHistory } from '../chat/useMessageHistory'
import { useChatRun } from '../chat/useChatRun'
import { ChatRunPanel } from '../chat/ChatRunPanel'
import { launchCodingConversation } from './projectCodingConversation'
import { isGithubReauthorizationRequired } from '../mcp/mcpReauthorization'
import { ConversationViewport } from '../chat/ConversationViewport'
import { appendBoundedRuntimeEvent, createStreamingMessageBatcher } from '../chat/streamingMessages'
import { needsRepositoryPreparation, prepareRepositoryChat, repositoryChatWorkspace, resolveWorkbenchSourceId } from './projectRepositoryChat'
import './project.css'
import './project-panels.css'

type Props = { language: Language; request: AuthenticatedRequest; openStream: OpenStream; navigationCollapsed: boolean; onToggleNavigation: () => void; tx: (key: string) => string }
type UiMessage = Message & { id: string; streaming?: boolean; reasoning?: string; reasoningOpen?: boolean; reasoningStartedAt?: number; reasoningElapsedMs?: number }
type PlanDraftRow = TaskPlanDraftStep & { clientId: string; acceptanceCriteriaText: string }
const now = () => new Date().toISOString()
const lineItems = (value: FormDataEntryValue | null) => String(value ?? '').split(/\r?\n/).map((item) => item.trim()).filter(Boolean)
const terminalTaskStates = new Set(['COMPLETED', 'FAILED', 'CANCELLED'])
const terminalExecutionStates = new Set<ProjectPlanExecution['state']>(['CANCELLED', 'COMPLETED', 'FAILED', 'BLOCKED'])
const executionActions = (state: ProjectPlanExecution['state']): ProjectExecutionAction[] => {
  if (state === 'READY' || state === 'RUNNING') return ['pause', 'cancel']
  if (state === 'PAUSED') return ['resume', 'cancel']
  return []
}
const taskActions = (state: string): TaskTransition[] => {
  if (state === 'PENDING' || state === 'BLOCKED') return ['ready', 'cancel']
  if (state === 'READY') return ['start', 'cancel']
  if (state === 'IN_PROGRESS') return ['block', 'complete', 'fail', 'cancel']
  return []
}
const planActions = (status: TaskPlan['status']): TaskPlanAction[] => {
  if (status === 'DRAFT') return ['propose']
  if (status === 'PROPOSED') return ['approve', 'cancel']
  if (status === 'APPROVED') return ['activate', 'cancel']
  if (status === 'ACTIVE') return ['complete', 'cancel']
  return []
}
const taskActionLabels: Record<TaskTransition, string> = {
  ready: 'markReady', start: 'startTask', block: 'blockTask',
  complete: 'completeTask', fail: 'failTask', cancel: 'cancelTask',
}
const planActionLabels: Record<TaskPlanAction, string> = {
  propose: 'proposePlan', approve: 'approvePlan', activate: 'activatePlan',
  complete: 'completePlan', cancel: 'cancelPlan',
}
const resizeComposer = (element: HTMLTextAreaElement | null) => {
  if (!element) return
  element.style.height = '38px'
  element.style.height = `${Math.min(76, Math.max(38, element.scrollHeight))}px`
}
const newPlanDraftRow = (stepKey: string, childTaskId = '', preferredAgentId = ''): PlanDraftRow => ({
  clientId: crypto.randomUUID(), stepKey, childTaskId, dependsOnStepKeys: [], requiredCapability: null,
  preferredAgentId: preferredAgentId || null, expectedOutput: '', acceptanceCriteria: [],
  acceptanceCriteriaText: '', approvalRequired: false,
})
const nextPlanStepKey = (rows: PlanDraftRow[]) => {
  const used = new Set(rows.map(({ stepKey }) => stepKey.trim()))
  let index = 1
  while (used.has(`step-${index}`)) index += 1
  return `step-${index}`
}

export function ProjectPage({ language, request, openStream, navigationCollapsed, onToggleNavigation, tx }: Props) {
  const [inspectorCollapsed, setInspectorCollapsed] = useState(() => {
    const value = localStorage.getItem('spaceagent-project-inspector-collapsed')
    return value === null ? window.innerWidth <= 1000 : value === 'true'
  })
  const [treeCollapsed, setTreeCollapsed] = useState(() => {
    const value = localStorage.getItem('spaceagent-project-tree-collapsed')
    return value === null ? window.innerWidth <= 680 : value === 'true'
  })
  const toggleInspector = () => setInspectorCollapsed((current) => { localStorage.setItem('spaceagent-project-inspector-collapsed', String(!current)); return !current })
  const toggleTree = () => setTreeCollapsed((current) => { localStorage.setItem('spaceagent-project-tree-collapsed', String(!current)); return !current })
  const messageLogRef = useRef<HTMLDivElement>(null)
  const [projects, setProjects] = useState<Project[]>([])
  const [roots, setRoots] = useState<ProjectDirectory[]>([])
  const [rootDeleteTarget, setRootDeleteTarget] = useState<ProjectDirectory | null>(null)
  const pendingRoot = useRef<string | null>(new URLSearchParams(window.location.search).get('directory'))
  const pendingConversation = useRef<string | null>(new URLSearchParams(window.location.search).get('conversation'))
  const requestedLink = useRef({ conversationId: pendingConversation.current,
    projectId: new URLSearchParams(window.location.search).get('project'), directoryId: pendingRoot.current })
  const linkedConversation = useRef<ProjectConversation | null>(null)
  const [linkState, setLinkState] = useState<'loading'|'ready'|'error'>(pendingConversation.current ? 'loading' : 'ready')
  const indexGeneration = useRef(0)
  const streamOperation = useChatStreamOperation(request)
  const rootStartRequest=useRef<{fingerprint:string;key:string}|null>(null)
  const codingStartRequest=useRef<{fingerprint:string;key:string;input:Parameters<typeof launchCodingConversation>[1]}|null>(null)
  const [conversations, setConversations] = useState<ProjectConversation[]>([])
  const [conversationPage,setConversationPage]=useState(1)
  const [conversationTotal,setConversationTotal]=useState(0)
  const [loadingMoreConversations,setLoadingMoreConversations]=useState(false)
  const [agents, setAgents] = useState<Awaited<ReturnType<typeof loadProjectIndex>>['agents']>([])
  const agentPages = usePagedOptions((offset, signal) => listAgents(request, offset, signal),
    page => setAgents(rows => [...rows, ...page.filter(agent => !rows.some(row => row.id === agent.id))]),
    reason => setError(reason instanceof Error ? reason.message : tx('projectLoadFailed')))
  const [tasks, setTasks] = useState<Task[]>([])
  const [workspaces, setWorkspaces] = useState<Workspace[]>([])
  const workspacesRef = useRef<Workspace[]>([])
  const [sources, setSources] = useState<SourceRepository[]>([])
  const [directories, setDirectories] = useState<ProjectDirectory[]>([])
  const [intakes, setIntakes] = useState<ProjectIntakeJob[]>([])
  const [codingJobs, setCodingJobs] = useState<ProjectCodingJob[]>([])
  const [handoffs, setHandoffs] = useState<ProjectRunHandoff[]>([])
  const [recoveryPackage, setRecoveryPackage] = useState<RecoveryPackage | null>(null)
  const [reviewerAgentId, setReviewerAgentId] = useState('')
  const [executions, setExecutions] = useState<ProjectPlanExecution[]>([])
  const [executionControl, setExecutionControl] = useState<ProjectPlanExecutionControl | null>(null)
  const executionControlRef = useRef<ProjectPlanExecutionControl | null>(null)
  const [executionBusyId, setExecutionBusyId] = useState<string | null>(null)
  const [executionActionTarget, setExecutionActionTarget] = useState<{ execution: ProjectPlanExecution; action: 'pause' | 'cancel' } | null>(null)
  const [githubConnections, setGithubConnections] = useState<GithubConnectionOption[]>([])
  const [githubRepositories, setGithubRepositories] = useState<GithubMcpRepository[]>([])
  const [githubMcpConnectionId, setGithubMcpConnectionId] = useState('')
  const [plans, setPlans] = useState<TaskPlan[]>([])
  const [assignmentPlanId, setAssignmentPlanId] = useState('')
  const [assignmentStepId, setAssignmentStepId] = useState('')
  const [assignmentSource, setAssignmentSource] = useState<'default' | 'override'>('override')
  const [stepAssignment, setStepAssignment] = useState<ProjectPlanStepAssignment | null>(null)
  const [assignmentAgentId, setAssignmentAgentId] = useState('')
  const [assignmentReviewerId, setAssignmentReviewerId] = useState('')
  const [assignmentBusy, setAssignmentBusy] = useState(false)
  const [projectId, setProjectId] = useState<string | null>(() => new URLSearchParams(window.location.search).get('project'))
  const [directoryId, setDirectoryId] = useState<string | null>(null)
  const [conversationId, setConversationId] = useState<string | null>(null)
  const [messages, setMessages] = useState<UiMessage[]>([])
  const [draft, setDraft] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [loading, setLoading] = useState(true)
  const [sending, setSending] = useState(false)
  const [settingsBusy, setSettingsBusy] = useState(false)
  const [creatingProject, setCreatingProject] = useState(false)
  const [preparingRepository, setPreparingRepository] = useState(false)
  const [repositorySetup, setRepositorySetup] = useState(false)
  const [repositoryAuthRequired, setRepositoryAuthRequired] = useState(false)
  const [selectedWorkbenchSourceId, setSelectedWorkbenchSourceId] = useState<string | null>(null)
  const [composerMode, setComposerMode] = useState<'code' | 'ask'>('code')
  const [codingGoal, setCodingGoal] = useState<string | null>(null)
  const [launchingCode, setLaunchingCode] = useState(false)
  const [creatingTask, setCreatingTask] = useState(false)
  const [creatingPlan, setCreatingPlan] = useState(false)
  const [planDraftSteps, setPlanDraftSteps] = useState<PlanDraftRow[]>([])
  const [creatingSource, setCreatingSource] = useState(false)
  const [creatingWorkspace, setCreatingWorkspace] = useState(false)
  const [creatingDirectory, setCreatingDirectory] = useState(false)
  const [creatingIntake, setCreatingIntake] = useState(false)
  const [sourceBusy, setSourceBusy] = useState(false)
  const [runtimeEvents, setRuntimeEvents] = useState<RuntimeEvent[]>([])
  const [runtimeEventCount, setRuntimeEventCount] = useState(0)
  const [done, setDone] = useState<ChatDone | null>(null)
  const [projectAction, setProjectAction] = useState<'edit' | null>(null)
  const [conversationDeleteTarget, setConversationDeleteTarget] = useState<ProjectConversation | null>(null)
  const [resourceTarget, setResourceTarget] = useState<{ kind: 'source' | 'workspace'; id: string; name: string } | null>(null)
  const [intakeRejectTarget, setIntakeRejectTarget] = useState<ProjectIntakeJob | null>(null)
  const [taskEditTarget, setTaskEditTarget] = useState<Task | null>(null)
  const [handoffTarget, setHandoffTarget] = useState<ProjectCodingJob | null>(null)
  const [handoffConversationId, setHandoffConversationId] = useState('')
  const [handoffTargetAgentId, setHandoffTargetAgentId] = useState('')
  const controller = useRef<AbortController | null>(null)
  const composerRef = useRef<HTMLTextAreaElement>(null)
  const activeProject = projects.find(({ id }) => id === projectId) ?? null
  const activeDirectory = directories.find(({ id }) => id === directoryId) ?? null
  useEffect(() => {
    const sourceId = activeDirectory?.sourceRepositoryId
    if (!projectId || !sourceId || sources.some(source => source.id === sourceId)) return
    const controller = new AbortController()
    request<SourceRepository>(`/api/v1/projects/${encodeURIComponent(projectId)}/sources/${encodeURIComponent(sourceId)}`, { signal: controller.signal })
      .then(source => { if (!controller.signal.aborted) setSources(rows => rows.some(row => row.id === source.id) ? rows : [...rows, source]) })
      .catch(reason => { if(!controller.signal.aborted)setError(reason instanceof Error ? reason.message : tx('sourceLoadFailed')) })
    return () => controller.abort()
  }, [projectId, activeDirectory?.sourceRepositoryId, request, sources])
  const projectConversations = conversations.filter((conversation) => conversation.projectId === projectId && conversation.projectDirectoryId === directoryId)
  const activeConversation = projectConversations.find(({ conversationId: id }) => id === conversationId) ?? null
  const availableAgents = agents.filter((agent) => !agent.status || agent.status === 'ACTIVE')
  const activeAgent = activeConversation
    ? agents.find(({ id }) => id === activeConversation.agentId) ?? null
    : availableAgents[0] ?? null
  const activeAgentUsable = Boolean(activeAgent && (!activeAgent.status || activeAgent.status === 'ACTIVE'))
  const activeTask = tasks.find((task) => task.id === activeConversation?.activeTaskId) ?? null
  const chatWorkspace = repositoryChatWorkspace(workspaces, directoryId, activeTask?.id)
  const childTasks = activeTask ? tasks.filter((task) => task.parentTaskId === activeTask.id) : []
  const workspaceTasks = tasks.filter((task) => !['COMPLETED', 'FAILED', 'CANCELLED'].includes(task.state))
  const readySources = sources.filter((source) => source.state === 'READY')
  const activeDirectorySources = readySources.filter((source) => source.id === activeDirectory?.sourceRepositoryId)
  const activePlan = plans.find(({ status }) => status === 'ACTIVE') ?? null
  const assignmentPlan = plans.find(({ id }) => id === assignmentPlanId) ?? null
  const assignmentStep = assignmentPlan?.steps.find(({ id }) => id === assignmentStepId) ?? null
  const assignmentAgent = availableAgents.find(({ id }) => id === assignmentAgentId) ?? null
  const reviewerAgent = availableAgents.find(({ id }) => id === reviewerAgentId) ?? null
  const executablePlan = plans.find(({ status }) => status === 'ACTIVE') ?? plans.find(({ status }) => status === 'APPROVED') ?? null
  const inspectedPlan = executablePlan ?? plans[0] ?? null
  const activePlanStep = inspectedPlan?.steps.find(({ state }) => !['COMPLETED', 'CANCELLED'].includes(state)) ?? inspectedPlan?.steps[0] ?? null
  const controlledExecution = executions.find(({ state }) => !terminalExecutionStates.has(state)) ?? executions[0] ?? null
  const executionAutoRefresh = executions.some(({ state }) => ['READY', 'RUNNING', 'PAUSING', 'CANCELLING'].includes(state))
  const handoffConversations = handoffTarget ? projectConversations.filter(({ conversationId: id }) => id !== handoffTarget.conversationId) : []
  const handoffAgents = handoffTarget ? availableAgents.filter(({ id }) => id !== handoffTarget.agentId) : []
  const workbenchWorkspace = workspaces.find((workspace) => workspace.id === codingJobs.find((job) => job.conversationId === conversationId && job.workspaceId)?.workspaceId) ?? chatWorkspace
  const workbenchSources = readySources.filter((source) => source.type !== 'LOCAL')
  const workbenchSourceId = resolveWorkbenchSourceId(selectedWorkbenchSourceId, activeDirectory?.sourceRepositoryId, workbenchWorkspace?.sourceRepositoryId, workbenchSources)
  const visibleWorkbenchWorkspace = workbenchWorkspace?.sourceRepositoryId === workbenchSourceId ? workbenchWorkspace : null
  useEffect(() => { setSelectedWorkbenchSourceId(null) }, [projectId, directoryId])
  const codingBusy = codingJobs.some((job) => job.conversationId === conversationId && ['PENDING','RUNNING','WAITING_APPROVAL'].includes(job.state))
  workspacesRef.current = workspaces
  executionControlRef.current = executionControl

  const refreshExecutions = useCallback(async () => {
    if (!activeProject || !inspectedPlan) {
      setExecutions([])
      setExecutionControl(null)
      return
    }
    const page = await listProjectPlanExecutions(request, activeProject.id, inspectedPlan.id)
    const rows = page.items.filter(({ taskPlanId }) => taskPlanId === inspectedPlan.id)
    setExecutions(rows)
    const current = rows.find(({ state }) => !terminalExecutionStates.has(state)) ?? rows[0] ?? null
    if (!current) { setExecutionControl(null); return }
    const cached = executionControlRef.current
    if (cached?.executionId === current.id && cached.revision === current.revision) return
    setExecutionControl(await getProjectPlanExecutionControl(
      request, activeProject.id, inspectedPlan.id, current.id))
  }, [activeProject, inspectedPlan, request])

  const loadIndex = useCallback(async () => {
    const generation = ++indexGeneration.current
    setLoading(true); setError(null)
    try {
      const [index, nextRoots] = await Promise.all([loadProjectIndex(request), listProjectRoots(request)])
      if (generation !== indexGeneration.current) return
      setRoots(nextRoots)
      setConversationPage(1);setConversationTotal(index.total)
      setProjects(index.projects); setAgents(index.agents); setConversations(index.conversations)
      agentPages.reset(index.agents.length)
      const target = requestedLink.current.conversationId
      if (target) {
        const linked = await loadLinkedConversation(request,target,'PROJECT',tx('linkedConversationTitle'),requestedLink.current)
        if (generation !== indexGeneration.current) return
        if (!index.projects.some(project=>project.id===linked.projectId && project.status==='ACTIVE')
          || !nextRoots.some(root=>root.id===linked.projectDirectoryId && root.projectId===linked.projectId && root.state==='ACTIVE'))
          throw new ApiError('Conversation is unavailable in this workspace',404,'CONVERSATION_LINK_UNAVAILABLE')
        linkedConversation.current = index.conversations.find(row=>row.conversationId===target) ?? {
          conversationId:linked.id,agentId:linked.agentId,userId:linked.userId!,projectId:linked.projectId!,
          projectDirectoryId:linked.projectDirectoryId!,activeTaskId:linked.activeTaskId??null,status:linked.status,
          title:linked.title,startedAt:linked.createdAt,lastMessageAt:linked.updatedAt,
          messages:[],messagePage:1,messageSize:0,messageTotal:0,
        }
        pendingRoot.current=linked.projectDirectoryId!;pendingConversation.current=target
        requestedLink.current.conversationId=null
        setConversations(includeLinkedConversation(index.conversations,linkedConversation.current,row=>row.conversationId))
        setProjectId(linked.projectId!);setLinkState('ready')
        return
      }
      const cached=linkedConversation.current
      if(cached && !index.conversations.some(row=>row.conversationId===cached.conversationId)){
        const latest=await loadLinkedConversation(request,cached.conversationId,'PROJECT',cached.title,
          {projectId:cached.projectId,directoryId:cached.projectDirectoryId})
        if(generation!==indexGeneration.current)return
        linkedConversation.current={...cached,title:latest.title,agentId:latest.agentId,activeTaskId:latest.activeTaskId??null,status:latest.status,lastMessageAt:latest.updatedAt}
      }
      setConversations(includeLinkedConversation(index.conversations,linkedConversation.current,row=>row.conversationId))
      if (streamOperation.isActive()) return
      setProjectId((current) => current && index.projects.some(({ id, status }) => id === current && status === 'ACTIVE') ? current : nextRoots[0]?.projectId ?? index.projects.find(project=>project.status==='ACTIVE')?.id ?? null)
    } catch (reason) {
      if(generation!==indexGeneration.current)return
      if(requestedLink.current.conversationId){setLinkState('error');setProjectId(null);setDirectoryId(null);setConversationId(null)}
      setError(reason instanceof Error ? reason.message : tx('projectLoadFailed'))
    }
    finally { if(generation===indexGeneration.current)setLoading(false) }
  }, [request, tx])
  useEffect(() => { void loadIndex() }, [loadIndex])
  useEffect(() => {
    let active=true
    listProjectRoots(request).then(value=>active&&setRoots(value)).catch(()=>undefined)
    return ()=>{active=false}
  }, [directories,request])

  useEffect(() => {
    let active = true
    loadGithubConnectionOptions(request).then((rows) => {
      if (!active) return
      setGithubConnections(rows)
      setGithubMcpConnectionId((current) => rows.some(({ id }) => id === current) ? current : rows[0]?.id || '')
    }).catch((reason) => active && setError(reason instanceof Error ? reason.message : tx('sourceLoadFailed')))
    return () => { active = false }
  }, [request, tx])

  useEffect(() => {
    if (linkState !== 'ready' || streamOperation.isActive()) return
    if (!projectId) { setTasks([]); setWorkspaces([]); setSources([]); setDirectories([]); setDirectoryId(null); return }
    let active = true
    setTasks([]); setWorkspaces([]); setSources([]); setDirectories([]); setHandoffs([]); setDirectoryId(null)
    loadProjectContext(request, projectId).then(({ tasks: nextTasks, workspaces: nextWorkspaces, sources: nextSources, directories: nextDirectories, handoffs: nextHandoffs }) => {
      if (!active) return
      setTasks(nextTasks); setWorkspaces(nextWorkspaces); setSources(nextSources); setDirectories(nextDirectories); setHandoffs(nextHandoffs)
      setDirectoryId((current) => {
        const desired=pendingRoot.current || current
        pendingRoot.current=null
        return desired && nextDirectories.some(({id,state}) => id===desired && state==='ACTIVE') ? desired
          : nextDirectories.find(({state}) => state==='ACTIVE')?.id ?? null
      })
    }).catch((reason) => active && setError(reason instanceof Error ? reason.message : tx('projectContextFailed')))
    return () => { active = false }
  }, [projectId, request, tx, linkState])

  useEffect(() => {
    if (linkState !== 'ready' || streamOperation.isActive()) return
    const available = conversations.filter((conversation) => conversation.projectId === projectId && conversation.projectDirectoryId === directoryId)
    const requested = available.find(row => row.conversationId === pendingConversation.current)
    if(requested) { pendingConversation.current = null; setConversationId(requested.conversationId); return }
    setConversationId((current) => current && available.some(({ conversationId: id }) => id === current) ? current : available[0]?.conversationId ?? null)
  }, [conversations, directoryId, projectId, linkState])

  useEffect(() => {
    if (!projectId || !directoryId) { setIntakes([]); return }
    let active = true
    listProjectIntakes(request, projectId, directoryId).then((page) => active && setIntakes(page.items)).catch((reason) => active && setError(reason instanceof Error ? reason.message : tx('projectIntakeLoadFailed')))
    return () => { active = false }
  }, [directoryId, projectId, request, tx])

  useEffect(() => {
    const reviewer = availableAgents.find((agent) => agent.id === reviewerAgentId && agent.id !== activeAgent?.id) ?? availableAgents.find((agent) => agent.id !== activeAgent?.id) ?? null
    setReviewerAgentId(reviewer?.id || '')
  }, [activeAgent?.id, agents, reviewerAgentId])

  useEffect(() => {
    let active = true
    refreshExecutions().catch((reason) => active && setError(reason instanceof Error ? reason.message : tx('projectExecutionLoadFailed')))
    return () => { active = false }
  }, [refreshExecutions, tx])

  useEffect(() => {
    if (!executionAutoRefresh) return
    let active = true
    let refreshing = false
    const timer = window.setInterval(() => {
      if (!active || refreshing || document.visibilityState === 'hidden') return
      refreshing = true
      refreshExecutions().catch(() => undefined).finally(() => { refreshing = false })
    }, 4000)
    return () => { active = false; window.clearInterval(timer) }
  }, [executionAutoRefresh, refreshExecutions])

  useEffect(() => {
    if (!activeProject || !inspectedPlan || !activePlanStep) { setCodingJobs([]); return }
    let active = true
    listProjectCodingJobs(request, activeProject.id, inspectedPlan.id, activePlanStep.id).then((page) => active && setCodingJobs(page.items)).catch((reason) => active && setError(reason instanceof Error ? reason.message : tx('projectExecutionLoadFailed')))
    return () => { active = false }
  }, [inspectedPlan, activePlanStep, activeProject, request, tx])

  const runControl=useChatRun(request,conversationId)
  const history=useMessageHistory(request,conversationId,sending,setMessages,reason=>setError(reason instanceof Error?reason.message:tx('messageLoadFailed')),runControl.historyKey)
  const stop=async()=>{try { await streamOperation.stop() } catch(reason) { setError(reason instanceof Error?reason.message:tx('streamFailed')) } finally {runControl.refresh()} }
  useEffect(()=>{if(runControl.status?.executionState==='COMPLETED'&&error===tx('streamDisconnectedRecovering'))setError(null)},[runControl.status?.executionState,error,tx])
  const loadOlderMessages=async()=>{
    const log=messageLogRef.current,height=log?.scrollHeight??0,top=log?.scrollTop??0
    if(await history.loadOlder())requestAnimationFrame(()=>{if(log)log.scrollTop=top+log.scrollHeight-height})
  }
  const loadMoreConversations=async()=>{
    if(loadingMoreConversations)return
    setLoadingMoreConversations(true)
    try{const page=await loadProjectIndex(request,conversationPage+1)
      setConversations(rows=>[...rows,...page.conversations.filter(item=>!rows.some(row=>row.conversationId===item.conversationId))]);setConversationPage(conversationPage+1);setConversationTotal(page.total)
    }catch(reason){setError(reason instanceof Error?reason.message:tx('projectLoadFailed'))}
    finally{setLoadingMoreConversations(false)}
  }
  useEffect(() => () => controller.current?.abort(), [])
  useEffect(() => {
    if (!activeProject || !inspectedPlan || !activePlanStep || (codingJobs.length > 0 && codingJobs.every((job) => ['COMPLETED','FAILED','HANDED_OFF'].includes(job.state)))) return
    return startSerialPolling(async signal => {
        const page = await listProjectCodingJobs(
          request, activeProject.id, inspectedPlan.id, activePlanStep.id, { signal })
        if (signal.aborted) return false
        setCodingJobs(page.items)
        const unresolvedWorkspace = page.items.some((job) => {
          if (!job.workspaceId) return false
          const workspace = workspacesRef.current.find(({ id }) => id === job.workspaceId)
          return !workspace || !['READY', 'FAILED', 'ARCHIVED', 'CLEANED_UP'].includes(workspace.state)
        })
        if (unresolvedWorkspace) {
          const nextWorkspaces = await listProjectWorkspaces(request, activeProject.id, { signal })
          if (!signal.aborted) setWorkspaces(nextWorkspaces)
        }
        return !(page.items.length > 0 && page.items.every(job => ['COMPLETED','FAILED','HANDED_OFF'].includes(job.state)))
    }, 4000)
  }, [activeProject?.id, inspectedPlan?.id, activePlanStep?.id, request])
  useEffect(() => resizeComposer(composerRef.current), [draft])
  useEffect(() => {
    if (!activeProject || !activeTask) { setPlans([]); return }
    let active = true
    listTaskPlans(request, activeProject.id, activeTask.id)
      .then((rows) => active && setPlans(rows))
      .catch((reason) => active && setError(reason instanceof Error ? reason.message : tx('projectContextFailed')))
    return () => { active = false }
  }, [activeProject, activeTask, request, tx])

  useEffect(() => {
    setCreatingPlan(false)
    setPlanDraftSteps([])
  }, [activeTask?.id])

  useEffect(() => {
    if (!activeProject || !assignmentPlan || !assignmentStep) {
      setStepAssignment(null)
      return
    }
    let active = true
    setStepAssignment(null)
    getProjectPlanStepAssignment(request, activeProject.id, assignmentPlan.id, assignmentStep.id)
      .then((assignment) => {
        if (!active) return
        setStepAssignment(assignment)
        setAssignmentSource(assignment.source === 'PLAN_DEFAULT' ? 'default' : 'override')
        setAssignmentAgentId(assignment.agentId)
        setAssignmentReviewerId(assignment.reviewerAgentId)
      })
      .catch((reason) => {
        if (!active || (reason instanceof ApiError && reason.status === 404)) return
        setError(reason instanceof Error ? reason.message : tx('assignmentLoadFailed'))
      })
    return () => { active = false }
  }, [activeProject, assignmentPlan, assignmentStep, request, tx])

  const updateDraft = (event: ChangeEvent<HTMLTextAreaElement>) => {
    setDraft(event.currentTarget.value)
    resizeComposer(event.currentTarget)
  }

  const handleComposerKeyDown = (event: KeyboardEvent<HTMLTextAreaElement>) => {
    if (!shouldSubmitComposer(event)) return
    event.preventDefault()
    event.currentTarget.form?.requestSubmit()
  }

  const submitProjectUpdate = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault(); if (!activeDirectory) return; const data = new FormData(event.currentTarget); setLoading(true); setError(null)
    try {
      const updated = await renameProjectRoot(request, activeDirectory.id, String(data.get('name') ?? '').trim())
      setRoots((rows) => rows.map((root) => root.id === updated.id ? updated : root)); setDirectories((rows)=>rows.map(root=>root.id===updated.id?updated:root)); setProjectAction(null)
    } catch (reason) { setError(reason instanceof Error ? reason.message : tx('projectUpdateFailed')) }
    finally { setLoading(false) }
  }

  const deleteActiveConversation = async () => {
    if (!conversationDeleteTarget || loading) return; setLoading(true); setError(null)
    try {
      await deleteConversation(request, conversationDeleteTarget.conversationId)
      if(linkedConversation.current?.conversationId===conversationDeleteTarget.conversationId)linkedConversation.current=null
      setConversations(rows=>rows.filter(row=>row.conversationId!==conversationDeleteTarget.conversationId))
      if(conversationId===conversationDeleteTarget.conversationId){setConversationId(null);setMessages([])}
      setConversationDeleteTarget(null)
    } catch (reason) { setError(reason instanceof Error ? reason.message : tx('conversationDeleteFailed')) }
    finally { setLoading(false) }
  }

  const newConversation = async (targetDirectory = activeDirectory, forSend = false) => {
    if (streamOperation.isActive() && !forSend) return null
    if (!projectId || !targetDirectory || !availableAgents.length) { setError(tx(!projectId ? 'projectRequired' : !targetDirectory ? 'projectDirectoryRequired' : 'agentRequired')); return null }
    try {
      if (targetDirectory.storageState && targetDirectory.storageState !== 'READY') await prepareProjectRoot(request,targetDirectory.id)
      const title = tx('newProjectConversation')
      const created = await createProjectConversation(request, { projectId: targetDirectory.projectId, projectDirectoryId: targetDirectory.id, agentId: availableAgents[0].id, activeTaskId: null, name: title })
      const conversation: ProjectConversation = { ...created, title }
      pendingRoot.current=targetDirectory.id; setProjectId(targetDirectory.projectId); setDirectoryId(targetDirectory.id); setConversations((rows) => [conversation, ...rows]); setConversationId(created.conversationId); setMessages([]); return conversation
    } catch (reason) { setError(reason instanceof Error ? reason.message : tx('conversationCreateFailed')); return null }
  }

  const submitRoot = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault(); if (loading) return
    const data=new FormData(event.currentTarget); setLoading(true); setError(null)
    try {
      const name=String(data.get('name')||'').trim()
      const githubInput={name,connectionId:String(data.get('connectionId')||''),githubUrl:String(data.get('githubUrl')||'').trim()}
      const fingerprint=JSON.stringify(githubInput)
      if(rootStartRequest.current?.fingerprint!==fingerprint)rootStartRequest.current={fingerprint,key:crypto.randomUUID()}
      const root=data.get('rootKind')==='GITHUB'
        ?await createGithubProjectRoot(request,githubInput,rootStartRequest.current.key)
        :await createProjectRoot(request,{projectId:projectId || null,sourceRepositoryId:String(data.get('sourceRepositoryId') || '') || null,name})
      pendingRoot.current=root.id
      await loadIndex(); setProjectId(root.projectId); setDirectoryId(root.id)
      const context=await loadProjectContext(request,root.projectId)
      setDirectories(context.directories); setSources(context.sources); setCreatingProject(false); setCreatingDirectory(false)
      rootStartRequest.current=null
    } catch(reason){setError(reason instanceof Error?reason.message:tx('projectDirectoryCreateFailed'))}
    finally{setLoading(false)}
  }

  const removeRoot = async () => {
    if(!rootDeleteTarget || loading)return
    setLoading(true);setError(null)
    try{
      const removed=await deleteProjectRoot(request,rootDeleteTarget.id)
      if(linkedConversation.current?.projectDirectoryId===removed.id)linkedConversation.current=null
      setRoots((items)=>items.filter(item=>item.id!==removed.id));setDirectories((items)=>items.filter(item=>item.id!==removed.id))
      if(directoryId===removed.id){setDirectoryId(null);setConversationId(null);setMessages([])}
      setRootDeleteTarget(null)
    }catch(reason){setError(reason instanceof Error?reason.message:tx('projectDirectoryArchiveFailed'))}
    finally{setLoading(false)}
  }

  const prepareRepository = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    if (!activeProject || !activeAgent || preparingRepository || sending) return
    const selectedProjectId = activeProject.id
    const sourceId = String(new FormData(event.currentTarget).get('sourceId') || '')
    const baseRef = String(new FormData(event.currentTarget).get('baseRef') || '').trim()
    setPreparingRepository(true); setError(null); setRepositoryAuthRequired(false)
    try {
      const prepared = await prepareRepositoryChat(request, { projectId: selectedProjectId,
        sourceId, agentId: activeAgent.id, conversationId: activeConversation?.conversationId ?? null, baseRef: baseRef || undefined }, tx)
      const context = await loadProjectContext(request, selectedProjectId)
      setDirectories(context.directories); setWorkspaces(context.workspaces); setTasks(context.tasks)
      setSources(context.sources); setDirectoryId(prepared.directory.id)
      setConversations((rows) => [{ ...prepared.conversation, title: rows.find((row) => row.conversationId === prepared.conversation.conversationId)?.title || tx('newProjectConversation') },
        ...rows.filter((row) => row.conversationId !== prepared.conversation.conversationId)])
      setConversationId(prepared.conversation.conversationId); setRepositorySetup(false)
    } catch (reason) {
      setRepositoryAuthRequired(isGithubReauthorizationRequired(reason))
      setError(reason instanceof Error ? reason.message : tx('workspaceProvisionFailed'))
      // Preserve completed public API mutations and rediscover them instead of rolling them back locally.
      const [context, index] = await Promise.all([loadProjectContext(request, selectedProjectId), loadProjectIndex(request)])
      setDirectories(context.directories); setWorkspaces(context.workspaces); setTasks(context.tasks)
      setSources(context.sources); setConversations(index.conversations)
    } finally { setPreparingRepository(false) }
  }

  const changeWorkbenchBranch = async (baseRef: string, sourceId: string) => {
    if (!activeProject || !activeAgent || sending || preparingRepository || codingBusy
      || !workbenchSources.some((source) => source.id === sourceId)) return
    setPreparingRepository(true); setError(null)
    codingStartRequest.current=null
    try {
      const prepared = await prepareRepositoryChat(request, { projectId: activeProject.id,
        sourceId, agentId: activeAgent.id,
        conversationId: activeConversation?.conversationId ?? null, baseRef }, tx)
      const context = await loadProjectContext(request, activeProject.id)
      setWorkspaces(context.workspaces); setTasks(context.tasks); setDirectories(context.directories)
      setConversations((rows) => [{ ...prepared.conversation,
        title: rows.find((row) => row.conversationId === prepared.conversation.conversationId)?.title || tx('newProjectConversation') },
        ...rows.filter((row) => row.conversationId !== prepared.conversation.conversationId)])
      setDirectoryId(prepared.directory.id); setConversationId(prepared.conversation.conversationId)
    } catch (reason) { setError(reason instanceof Error ? reason.message : tx('workspaceProvisionFailed')) }
    finally { setPreparingRepository(false) }
  }

  const decideCodingApproval = async (job: ProjectCodingJob, decision: 'APPROVED' | 'REJECTED') => {
    if (!job.pendingApprovalId || loading || !activeProject) return
    setLoading(true); setError(null)
    try {
      await decideProjectApproval(request, job.pendingApprovalId, decision, 'Decision from coding conversation')
      const page = await listProjectCodingJobs(request, activeProject.id, job.taskPlanId, job.planStepId)
      setCodingJobs(page.items)
    } catch (reason) { setError(reason instanceof Error ? reason.message : tx('projectExecutionLoadFailed')) }
    finally { setLoading(false) }
  }

  const confirmCodingConversation = async () => {
    if (!codingGoal || !activeProject || !activeDirectory?.sourceRepositoryId || !activeAgent || !activeConversation || launchingCode) return
    setLaunchingCode(true); setError(null)
    try {
      const input={ projectId: activeProject.id, directoryId: activeDirectory.id,
        sourceId: activeDirectory.sourceRepositoryId, conversationId: activeConversation.conversationId,
        agentId: activeAgent.id, reviewerAgentId, baseRef: workbenchWorkspace?.baseRef || 'main', goal: codingGoal }
      const {baseRef:_baseRef,...intent}=input
      const fingerprint=JSON.stringify(intent)
      if(codingStartRequest.current?.fingerprint!==fingerprint)codingStartRequest.current={fingerprint,key:crypto.randomUUID(),input}
      const result = await launchCodingConversation(request,{...codingStartRequest.current.input,idempotencyKey:codingStartRequest.current.key},tx)
      const context = await loadProjectContext(request, activeProject.id)
      setTasks(context.tasks); setPlans([result.plan]); setExecutions([result.execution])
      setConversations((rows) => rows.map((row) => row.conversationId===activeConversation.conversationId ? {...row,activeTaskId:result.root.id} : row))
      setDraft(''); setCodingGoal(null)
      codingStartRequest.current=null
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : tx('projectExecutionLoadFailed'))
      try{const [index,context]=await Promise.all([loadProjectIndex(request),loadProjectContext(request,activeProject.id)]);setConversations(index.conversations);setTasks(context.tasks);setWorkspaces(context.workspaces)}catch{/* Preserve error and retry key. */}
    }
    finally { setLaunchingCode(false) }
  }

  const send = async (event: FormEvent) => {
    event.preventDefault(); const content = draft.trim(); if (!content || sending || settingsBusy || linkState !== 'ready' || preparingRepository || launchingCode || runControl.active || !runControl.ready) return
    if (codingBusy) { setError(tx('codingAlreadyRunning')); return }
    if (needsRepositoryPreparation(activeDirectory, readySources.length > 0, chatWorkspace)) {
      setRepositorySetup(true); setError(tx('repositoryPrepareRequired')); return
    }
    if (composerMode === 'code' && activeDirectory?.sourceRepositoryId) {
      if (codingBusy) { setError(tx('codingAlreadyRunning')); return }
      setCodingGoal(content); return
    }
    const operation = streamOperation.begin()
    if (!operation) return
    setSending(true)
    let conversation = activeConversation; if (!conversation) conversation = await newConversation(activeDirectory,true)
    if (!conversation || !activeAgent) {streamOperation.finish(operation);setSending(false);return}
    operation.conversationId = conversation.conversationId
    const assistantId = `assistant-${Date.now()}`
    setMessages((rows) => [...rows, { id: `user-${Date.now()}`, role: 'USER', content, createdAt: now() }, { id: assistantId, role: 'ASSISTANT', content: '', createdAt: now(), streaming: true }])
    setDraft(''); setSending(true); setError(null); setRuntimeEvents([]); setRuntimeEventCount(0); setDone(null); const abortController = operation.controller; controller.current = abortController
    const deltas = createStreamingMessageBatcher(setMessages, assistantId)
    try {
      await streamMessage(openStream, { conversationId: conversation.conversationId, agentId: activeAgent.id, message: content, modelId: activeAgent.modelId, workspaceId: chatWorkspace?.id }, {
        onReasoningDelta: deltas.reasoning,
        onDelta: deltas.content,
        onRuntimeEvent: (runtimeEvent) => {streamOperation.onEvent(operation,runtimeEvent);runControl.onEvent(runtimeEvent);setRuntimeEventCount((count) => count + 1);setRuntimeEvents((rows) => appendBoundedRuntimeEvent(rows, runtimeEvent))}, onDone: (value) => { deltas.flush(); setDone(value); setMessages((rows) => rows.map((message) => message.id === assistantId && message.reasoningStartedAt ? { ...message, reasoningOpen: false, reasoningElapsedMs: Date.now() - message.reasoningStartedAt } : message)) }, onSuspended: () => runControl.refresh(),
      }, abortController.signal)
      deltas.flush()
      setMessages((rows) => rows.map((message) => message.id === assistantId ? { ...message, streaming: false } : message))
    } catch (reason) { deltas.flush(); setError(reason instanceof DOMException && reason.name === 'AbortError' || reason instanceof ApiError && reason.code === 'CHAT_RUN_CANCELLED' ? null : reason instanceof ApiError && reason.code === 'CHAT_STREAM_INCOMPLETE' ? tx('streamDisconnectedRecovering') : reason instanceof Error ? reason.message : tx('streamFailed')); setMessages((rows) => rows.map((message) => message.id === assistantId ? { ...message, streaming: false, reasoningOpen: false, reasoningElapsedMs: message.reasoningStartedAt ? Date.now() - message.reasoningStartedAt : message.reasoningElapsedMs } : message)) }
    finally { deltas.cancel(); controller.current = null; streamOperation.finish(operation); setSending(false); runControl.refresh() }
  }

  const applyConversationSettings = (update: ConversationSettingsUpdate) => {
    setConversations(rows => rows.map(conversation => conversation.conversationId === update.id
      ? { ...conversation, ...(update.title === undefined ? {} : {title:update.title}),
          ...(update.agentId === undefined ? {} : {agentId:update.agentId}), lastMessageAt:update.updatedAt }
      : conversation))
  }

  const selectActiveTask = async (taskId: string | null) => {
    if (!activeConversation || loading) return
    setLoading(true); setError(null)
    try {
      const updated = await setProjectConversationActiveTask(
        request, activeConversation.conversationId, taskId)
      setConversations((rows) => rows.map((conversation) =>
        conversation.conversationId === activeConversation.conversationId
          ? { ...conversation, activeTaskId: updated.activeTaskId, lastMessageAt: updated.lastMessageAt }
          : conversation))
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : tx('projectContextFailed'))
    } finally { setLoading(false) }
  }

  const submitTask = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    if (!activeProject || loading) return
    const form = event.currentTarget
    const data = new FormData(form)
    const parentTaskId = String(data.get('parentTaskId') ?? '') || null
    setLoading(true); setError(null)
    try {
      const created = await createProjectTask(request, activeProject.id, {
        parentTaskId,
        title: String(data.get('title') ?? '').trim(),
        goal: String(data.get('goal') ?? '').trim(),
        description: String(data.get('description') ?? '').trim() || null,
      })
      setTasks((rows) => [created, ...rows])
      setCreatingTask(false)
      form.reset()
      if (activeConversation && !activeConversation.activeTaskId && !parentTaskId) {
        const updated = await setProjectConversationActiveTask(
          request, activeConversation.conversationId, created.id)
        setConversations((rows) => rows.map((conversation) =>
          conversation.conversationId === activeConversation.conversationId
            ? { ...conversation, activeTaskId: updated.activeTaskId, lastMessageAt: updated.lastMessageAt }
            : conversation))
      }
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : tx('projectContextFailed'))
    } finally { setLoading(false) }
  }

  const transitionTask = async (transition: TaskTransition) => {
    if (!activeProject || !activeTask || loading) return
    setLoading(true); setError(null)
    try {
      const updated = await transitionProjectTask(
        request, activeProject.id, activeTask.id, transition)
      setTasks((rows) => rows.map((task) => task.id === updated.id ? updated : task))
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : tx('projectContextFailed'))
    } finally { setLoading(false) }
  }

  const submitTaskUpdate = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    if (!activeProject || !taskEditTarget || loading) return
    const data = new FormData(event.currentTarget)
    setLoading(true); setError(null)
    try {
      const updated = await updateProjectTask(request, activeProject.id, taskEditTarget.id, {
        title: String(data.get('title') ?? '').trim(),
        goal: String(data.get('goal') ?? '').trim(),
        description: String(data.get('description') ?? '').trim() || null,
        constraints: lineItems(data.get('constraints')),
        acceptanceCriteria: lineItems(data.get('acceptanceCriteria')),
      })
      setTasks((rows) => rows.map((task) => task.id === updated.id ? updated : task))
      setTaskEditTarget(null)
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : tx('taskUpdateFailed'))
    } finally { setLoading(false) }
  }

  const togglePlanDraft = () => {
    if (creatingPlan) {
      setCreatingPlan(false)
      setPlanDraftSteps([])
      return
    }
    setCreatingPlan(true)
    setPlanDraftSteps([newPlanDraftRow('step-1', childTasks[0]?.id, activeAgent?.id)])
  }

  const updatePlanDraft = (clientId: string, update: Partial<PlanDraftRow>) => {
    setPlanDraftSteps((rows) => rows.map((row) => row.clientId === clientId ? { ...row, ...update } : row))
  }

  const renamePlanDraftStep = (clientId: string, stepKey: string) => {
    setPlanDraftSteps((rows) => {
      const previous = rows.find((row) => row.clientId === clientId)?.stepKey
      return rows.map((row) => row.clientId === clientId
        ? { ...row, stepKey }
        : { ...row, dependsOnStepKeys: row.dependsOnStepKeys.map((key) => key === previous ? stepKey : key) })
    })
  }

  const addPlanDraftStep = () => {
    setPlanDraftSteps((rows) => {
      if (rows.length >= Math.min(100, childTasks.length)) return rows
      const usedTasks = new Set(rows.map(({ childTaskId }) => childTaskId))
      const childTaskId = childTasks.find(({ id }) => !usedTasks.has(id))?.id || ''
      return [...rows, newPlanDraftRow(nextPlanStepKey(rows), childTaskId, activeAgent?.id)]
    })
  }

  const removePlanDraftStep = (clientId: string) => {
    setPlanDraftSteps((rows) => {
      if (rows.length === 1) return rows
      const removedKey = rows.find((row) => row.clientId === clientId)?.stepKey
      return rows.filter((row) => row.clientId !== clientId).map((row) => ({
        ...row,
        dependsOnStepKeys: row.dependsOnStepKeys.filter((key) => key !== removedKey),
      }))
    })
  }

  const togglePlanDependency = (clientId: string, dependencyKey: string, checked: boolean) => {
    setPlanDraftSteps((rows) => rows.map((row) => row.clientId === clientId ? {
      ...row,
      dependsOnStepKeys: checked
        ? [...new Set([...row.dependsOnStepKeys, dependencyKey])]
        : row.dependsOnStepKeys.filter((key) => key !== dependencyKey),
    } : row))
  }

  const submitPlan = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    if (!activeProject || !activeTask || loading) return
    const steps = planDraftSteps.map(({ clientId: _clientId, acceptanceCriteriaText, ...row }) => ({
      ...row,
      stepKey: row.stepKey.trim(),
      dependsOnStepKeys: [...new Set(row.dependsOnStepKeys.map((value) => value.trim()).filter(Boolean))],
      requiredCapability: row.requiredCapability?.trim() || null,
      preferredAgentId: row.preferredAgentId || null,
      expectedOutput: row.expectedOutput.trim(),
      acceptanceCriteria: acceptanceCriteriaText.split(/\r?\n/).map((value) => value.trim()).filter(Boolean),
    }))
    const stepKeys = new Set(steps.map(({ stepKey }) => stepKey))
    const childIds = new Set(steps.map(({ childTaskId }) => childTaskId))
    if (!steps.length || stepKeys.size !== steps.length || childIds.size !== steps.length
        || steps.some(({ stepKey, childTaskId, expectedOutput }) => !stepKey || !childTaskId || !expectedOutput)) {
      setError(tx('planDraftInvalid'))
      return
    }
    setLoading(true); setError(null)
    try {
      const created = await createTaskPlan(request, activeProject.id, activeTask.id, { steps })
      setPlans((rows) => [created, ...rows])
      setCreatingPlan(false)
      setPlanDraftSteps([])
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : tx('projectContextFailed'))
    } finally { setLoading(false) }
  }

  const transitionPlan = async (plan: TaskPlan, action: TaskPlanAction) => {
    if (!activeProject || !activeTask || loading) return
    setLoading(true); setError(null)
    try {
      const updated = await transitionTaskPlan(
        request, activeProject.id, activeTask.id, plan.id, action)
      setPlans((rows) => rows.map((item) => item.id === updated.id ? updated : item))
      if (action === 'activate' || action === 'complete' || action === 'cancel') {
        const context = await loadProjectContext(request, activeProject.id)
        setTasks(context.tasks); setWorkspaces(context.workspaces); setSources(context.sources)
      }
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : tx('projectContextFailed'))
    } finally { setLoading(false) }
  }

  const openAssignment = (plan: TaskPlan) => {
    const step = plan.steps[0] ?? null
    const primary = availableAgents.find(({ id }) => id === step?.preferredAgentId)
      ?? activeAgent
      ?? availableAgents[0]
      ?? null
    const reviewer = availableAgents.find(({ id }) => id !== primary?.id) ?? null
    setAssignmentPlanId(plan.id)
    setAssignmentStepId(step?.id || '')
    setAssignmentSource('override')
    setStepAssignment(null)
    setAssignmentAgentId(primary?.id || '')
    setAssignmentReviewerId(reviewer?.id || '')
  }

  const submitStepAssignment = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    if (!activeProject || !assignmentPlan || !assignmentStep || !assignmentAgent
        || !(assignmentAgent.modelPoolId || assignmentAgent.modelProviderId && assignmentAgent.modelId) || !assignmentReviewerId || assignmentBusy) return
    setAssignmentBusy(true)
    setError(null)
    try {
      const assignment = await defineProjectPlanStepAssignment(
        request, activeProject.id, assignmentPlan.id, assignmentStep.id, assignmentSource,
        {
          agentId: assignmentAgentId,
          reviewerAgentId: assignmentReviewerId,
          modelPoolId: assignmentAgent.modelPoolId,
        },
      )
      setStepAssignment(assignment)
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : tx('assignmentSaveFailed'))
    } finally { setAssignmentBusy(false) }
  }

  const loadGithubRepositoryOptions = async () => {
    if (!githubMcpConnectionId || sourceBusy) return
    setSourceBusy(true); setError(null)
    try {
      const rows = await listGithubRepositories(request, githubMcpConnectionId)
      setGithubRepositories(rows.filter(({ archived }) => !archived))
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : tx('sourceLoadFailed'))
    } finally { setSourceBusy(false) }
  }

  const submitSource = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    if (!activeProject || !githubMcpConnectionId || sourceBusy) return
    const form = event.currentTarget
    const data = new FormData(form)
    const providerRepositoryId = String(data.get('providerRepositoryId') ?? '') || null
    const githubUrl = String(data.get('githubUrl') ?? '').trim() || null
    if ((!providerRepositoryId && !githubUrl) || (providerRepositoryId && githubUrl)) { setError(tx('sourceImportFailed')); return }
    setSourceBusy(true); setError(null)
    try {
      const source = await importGithubMcpSource(request, activeProject.id, {
        connectionId: githubMcpConnectionId,
        providerRepositoryId,
        githubUrl,
      }, crypto.randomUUID())
      setSources((rows) => [source, ...rows.filter(({ id }) => id !== source.id)])
      setCreatingSource(false); form.reset()
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : tx('sourceImportFailed'))
    } finally { setSourceBusy(false) }
  }

  const submitWorkspace = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    if (!activeProject || !activeDirectory || loading) return
    const form = event.currentTarget
    const data = new FormData(form)
    setLoading(true); setError(null)
    try {
      const workspace = await provisionProjectWorkspace(request, activeProject.id, {
        projectDirectoryId: activeDirectory.id,
        taskId: String(data.get('taskId') ?? ''),
        sourceRepositoryId: String(data.get('sourceRepositoryId') ?? ''),
        baseRef: String(data.get('baseRef') ?? '').trim() || null,
      })
      setWorkspaces((rows) => [workspace, ...rows.filter(({ id }) => id !== workspace.id)])
      setCreatingWorkspace(false); form.reset()
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : tx('workspaceProvisionFailed'))
    } finally { setLoading(false) }
  }

  const submitIntake = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault(); if (!activeProject || !activeDirectory || !activeConversation || !activeAgent || loading) return
    const form = event.currentTarget; const data = new FormData(form); setLoading(true); setError(null)
    try { const job = await enqueueProjectIntake(request, activeProject.id, activeDirectory.id, { conversationId: activeConversation.conversationId, agentId: activeAgent.id, goal: String(data.get('goal') || '').trim() }); setIntakes((rows) => [job, ...rows]); setCreatingIntake(false); form.reset() }
    catch (reason) { setError(reason instanceof Error ? reason.message : tx('projectIntakeCreateFailed')) }
    finally { setLoading(false) }
  }

  const reviewIntake = async (job: ProjectIntakeJob, decision: 'confirm' | 'reject', reason?: string) => {
    if (!activeProject || !activeDirectory || !job.proposalHash || loading) return
    if (decision === 'reject' && !reason) return
    setLoading(true); setError(null)
    try { const updated = await reviewProjectIntake(request, activeProject.id, activeDirectory.id, job.id, { proposalHash: job.proposalHash, decision, reason }); setIntakes((rows) => rows.map((item) => item.id === updated.id ? updated : item)); setIntakeRejectTarget(null); if (decision === 'confirm') { const context = await loadProjectContext(request, activeProject.id); setTasks(context.tasks); setWorkspaces(context.workspaces); setSources(context.sources) } }
    catch (reasonValue) { setError(reasonValue instanceof Error ? reasonValue.message : tx('projectIntakeReviewFailed')) }
    finally { setLoading(false) }
  }

  const captureRecovery = async (job: ProjectCodingJob) => {
    if (!activeProject || !job.codingRunId || loading) return
    setLoading(true); setError(null)
    try { setRecoveryPackage(await captureProjectRecovery(request, activeProject.id, job.codingRunId)) }
    catch (reason) { setError(reason instanceof Error ? reason.message : tx('projectRecoveryFailed')) }
    finally { setLoading(false) }
  }

  const openHandoff = (job: ProjectCodingJob) => {
    const targetConversation = projectConversations.find(({ conversationId: id }) => id !== job.conversationId) ?? null
    const targetAgent = availableAgents.find(({ id }) => id === targetConversation?.agentId && id !== job.agentId)
      ?? availableAgents.find(({ id }) => id !== job.agentId)
      ?? null
    setHandoffTarget(job)
    setHandoffConversationId(targetConversation?.conversationId || '')
    setHandoffTargetAgentId(targetAgent?.id || '')
  }

  const submitHandoff = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    if (!activeProject || !handoffTarget || !handoffConversationId || !handoffTargetAgentId || !handoffTarget.reviewerAgentId || loading) return
    setLoading(true); setError(null)
    try {
      const result = await createProjectRunHandoff(request, activeProject.id, handoffTarget, {
        targetConversationId: handoffConversationId,
        targetAgentId: handoffTargetAgentId,
        reviewerAgentId: handoffTarget.reviewerAgentId,
      }, crypto.randomUUID())
      setHandoffs((rows) => [result.handoff, ...rows.filter(({ id }) => id !== result.handoff.id)])
      setCodingJobs((rows) => [
        result.targetCodingJob,
        ...rows.filter(({ id }) => id !== result.targetCodingJob.id).map((job) =>
          job.id === handoffTarget.id ? { ...job, state: 'HANDED_OFF' as const } : job),
      ])
      setHandoffTarget(null)
      setRecoveryPackage(result.recoveryPackage)
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : tx('handoffCreateFailed'))
    } finally { setLoading(false) }
  }

  const applyExecutionAction = async (
    execution: ProjectPlanExecution,
    action: ProjectExecutionAction,
    reason?: string,
  ) => {
    if (!activeProject || executionBusyId) return
    const normalizedReason = reason?.trim()
    if (action !== 'resume' && !normalizedReason) return
    setExecutionBusyId(execution.id); setError(null)
    try {
      const updated = await controlProjectPlanExecution(
        request, activeProject.id, execution.taskPlanId, execution.id,
        action, execution.revision, normalizedReason,
      )
      setExecutions((rows) => rows.map((item) => item.id === updated.id ? updated : item))
      setExecutionControl(await getProjectPlanExecutionControl(
        request, activeProject.id, execution.taskPlanId, execution.id,
      ))
      setExecutionActionTarget(null)
    } catch (reasonValue) {
      setError(reasonValue instanceof Error ? reasonValue.message : tx('projectExecutionControlFailed'))
      await refreshExecutions().catch(() => undefined)
    } finally { setExecutionBusyId(null) }
  }

  const manuallyRefreshExecutions = async () => {
    if (executionBusyId) return
    setExecutionBusyId(controlledExecution?.id || 'refresh'); setError(null)
    try { await refreshExecutions() }
    catch (reason) { setError(reason instanceof Error ? reason.message : tx('projectExecutionLoadFailed')) }
    finally { setExecutionBusyId(null) }
  }

  const executePlan = async () => {
    const source = sources.find(({ id }) => id === activeDirectory?.sourceRepositoryId) ?? null
    if (!activeProject || !activeDirectory || !activeConversation || !activeTask || !executablePlan || !activeAgent || !reviewerAgent || !source || loading) return
    setLoading(true); setError(null)
    try {
      const execution = await executeProjectPlan(request, activeProject.id, activeTask.id, executablePlan.id, { projectDirectoryId: activeDirectory.id, conversationId: activeConversation.conversationId, sourceRepositoryId: source.id, agentId: activeAgent.id, reviewerAgentId: reviewerAgent.id, baseRef: source.defaultBranch || 'main' })
      setExecutions([execution])
      setExecutionControl(await getProjectPlanExecutionControl(request, activeProject.id, executablePlan.id, execution.id))
      setPlans(await listTaskPlans(request, activeProject.id, activeTask.id))
    } catch (reason) { setError(reason instanceof Error ? reason.message : tx('projectExecutionStartFailed')) }
    finally { setLoading(false) }
  }

  const archiveResource = async () => {
    if (!activeProject || !resourceTarget || loading) return
    setLoading(true); setError(null)
    try {
      if (resourceTarget.kind === 'source') {
        const source = await archiveProjectSource(request, activeProject.id, resourceTarget.id)
        setSources((rows) => rows.map((item) => item.id === source.id ? source : item))
      } else {
        const workspace = await archiveProjectWorkspace(request, activeProject.id, resourceTarget.id)
        setWorkspaces((rows) => rows.map((item) => item.id === workspace.id ? workspace : item))
      }
      setResourceTarget(null)
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : tx(resourceTarget.kind === 'source' ? 'sourceArchiveFailed' : 'workspaceArchiveFailed'))
    } finally { setLoading(false) }
  }

  const toggleReasoning = useCallback((messageId: string) => setMessages((rows) => rows.map((message) =>
    message.id === messageId ? { ...message, reasoningOpen: !message.reasoningOpen } : message)), [])

  return <section className={`project-workspace ${inspectorCollapsed ? 'inspector-collapsed' : ''} ${treeCollapsed ? 'tree-collapsed' : ''}`}>
    <WorkspaceNavigation ready={!loading}><ProjectNavigationTree collapsed={treeCollapsed} navigationLocked={sending}
      projects={projects}
      githubConnections={githubConnections}
      onRefreshConnections={()=>void loadGithubConnectionOptions(request).then(setGithubConnections).catch(reason=>setError(String(reason)))}
      conversations={conversations}
      directories={roots.map(root => ({...root,...directories.find(directory=>directory.id===root.id)}))}
      readySources={readySources.filter(source=>source.type!=='GENERIC')}
      projectId={projectId}
      directoryId={directoryId}
      conversationId={conversationId}
      loading={loading || sending || preparingRepository || launchingCode}
      hasMore={conversations.length<conversationTotal}
      loadingMore={loadingMoreConversations}
      onLoadMore={()=>void loadMoreConversations()}
      creatingProject={creatingProject}
      creatingDirectory={creatingDirectory}
      tx={tx}
      onToggleProjectCreate={() => setCreatingProject((value) => !value)}
      onCreateProject={submitRoot}
      onSelectProject={(id)=>{if(streamOperation.isActive())return;indexGeneration.current++;setLoading(false);requestedLink.current.conversationId=null;pendingConversation.current=null;if(linkedConversation.current?.projectId!==id)linkedConversation.current=null;setLinkState('ready');setError(null);setProjectId(id)}}
      onSelectDirectory={(id)=>{if(streamOperation.isActive())return;pendingRoot.current=id;setDirectoryId(id)}}
      onSelectConversation={(id)=>{if(streamOperation.isActive())return;pendingConversation.current=null;if(linkedConversation.current?.conversationId!==id)linkedConversation.current=null;setConversationId(id)}}
      onCreateConversation={(directory) => void newConversation(directory)}
      onDeleteConversation={(conversation) => setConversationDeleteTarget(conversation)}
      onArchiveDirectory={setRootDeleteTarget}
      onToggleDirectoryCreate={() => setCreatingDirectory((value) => !value)}
      onCreateDirectory={submitRoot}
    /></WorkspaceNavigation>
    <main className="project-conversation"><header><div className="project-head-left"><button type="button" className="project-panel-toggle project-navigation-toggle" onClick={onToggleNavigation} aria-expanded={!navigationCollapsed} aria-controls="application-navigation" title={tx(navigationCollapsed ? 'expandNavigation' : 'collapseNavigation')} aria-label={tx(navigationCollapsed ? 'expandNavigation' : 'collapseNavigation')}>{navigationCollapsed ? '⟫' : '⟪'}</button><button type="button" className="project-panel-toggle" onClick={toggleTree} aria-expanded={!treeCollapsed} aria-controls="project-navigation-tree" title={tx(treeCollapsed ? 'expandProjectTree' : 'collapseProjectTree')} aria-label={tx(treeCollapsed ? 'expandProjectTree' : 'collapseProjectTree')}>☷</button><div><span>{activeDirectory?.name || tx('projectNav')}</span><b>{activeConversation?.title || activeDirectory?.name || activeProject?.name || tx('projectNav')}</b></div></div><div className="project-header-actions"><small>{activeAgent?.name || tx('noAgent')}</small>{activeProject && <><button className="neutral" disabled={sending || preparingRepository} onClick={() => setRepositorySetup(true)}>{tx(chatWorkspace ? 'repositoryReadReady' : 'repositoryPrepare')}</button><button className="neutral" onClick={() => setProjectAction('edit')}>{tx('editRoot')}</button><button disabled={!activeDirectory} onClick={() => setRootDeleteTarget(activeDirectory)}>{tx('deleteRoot')}</button></>}<button type="button" className="project-panel-toggle" onClick={toggleInspector} aria-expanded={!inspectorCollapsed} aria-controls="project-run-inspector" title={tx(inspectorCollapsed ? 'expandInspector' : 'collapseInspector')} aria-label={tx(inspectorCollapsed ? 'expandInspector' : 'collapseInspector')}>{inspectorCollapsed ? '⟪' : '⟫'}</button></div></header>{error && <div className="project-error" role="alert">{error}</div>}<ChatRunPanel run={runControl} tx={tx}/><ConversationViewport className="project-message-log" logRef={messageLogRef} tx={tx}>{history.hasOlder&&<button type="button" className="history-load-more" disabled={history.loadingOlder||sending} onClick={()=>void loadOlderMessages()}>{tx('loadOlderMessages')}</button>}{activeTask?.currentTaskPlanId && <section className="coding-progress-card"><header><b>{tx('aiCodingMode')}</b><span>{codingJobs[0]?.state || controlledExecution?.state || activeTask.state}</span></header><p>{activeTask.goal}</p>{codingJobs[0]?.pendingToolName && <p>{codingJobs[0].pendingToolName}</p>}{codingJobs[0]?.safeErrorCode && <p role="alert">{codingJobs[0].safeErrorCode}</p>}{codingJobs[0]?.pendingApprovalId && <><button disabled={loading} onClick={() => void decideCodingApproval(codingJobs[0], 'APPROVED')}>{tx('approveAndResume')}</button><button disabled={loading} onClick={() => void decideCodingApproval(codingJobs[0], 'REJECTED')}>{tx('reject')}</button></>}{controlledExecution && executionActions(controlledExecution.state).map((action) => <button key={action} onClick={() => action === 'resume' ? void applyExecutionAction(controlledExecution, action) : setExecutionActionTarget({execution:controlledExecution,action})}>{tx(action === 'pause' ? 'pause' : action === 'cancel' ? 'cancel' : 'resume')}</button>)}</section>}{messages.map(message => <ConversationMessage key={message.id} message={message} mode="project" logRef={messageLogRef} tx={tx} toggleReasoning={toggleReasoning} />)}{!messages.length && <div className="project-empty"><b>{activeProject ? activeDirectory ? tx('startProjectConversation') : tx('projectDirectoryRequired') : tx('projectRequired')}</b></div>}</ConversationViewport><form className="project-composer" onSubmit={send}><div className="project-coding-options"><select aria-label={tx('aiCodingMode')} value={composerMode} onChange={(event) => setComposerMode(event.target.value as 'code' | 'ask')}><option value="code">{tx('aiCodingMode')}</option><option value="ask">{tx('askRepositoryMode')}</option></select><span>{repositoryBranchLabel(workbenchWorkspace?.baseRef)}</span></div><textarea aria-label={tx('projectComposer')} ref={composerRef} value={draft} onChange={updateDraft} onKeyDown={handleComposerKeyDown} rows={1} maxLength={4000} placeholder={tx('projectComposer')}/><div><div className="composer-route" aria-label={tx('activeContext')}><span>{activeDirectory?.name || '—'}</span><span>{activeAgent?.name || '—'}</span></div><div className="composer-actions"><span>{draft.length} / 4000</span>{sending?<button type="button" disabled={!streamOperation.canStop || streamOperation.stopping} onClick={()=>void stop()}>{tx('stop')}</button>:<button className="send" type="submit" disabled={settingsBusy || !draft.trim() || !activeProject || !activeDirectory || !activeAgentUsable || runControl.active || !runControl.ready}>→</button>}</div></div></form></main>
    <aside className="project-inspector" id="project-run-inspector" inert={inspectorCollapsed}>
      <ConversationSettings key={conversationId || 'empty'} request={request}
        conversation={activeConversation ? {id:activeConversation.conversationId,title:activeConversation.title,agentId:activeConversation.agentId,status:activeConversation.status} : null}
        agents={agents} rootName={activeDirectory?.name} locked={sending || preparingRepository || launchingCode || codingBusy || runControl.active || !runControl.ready || loading}
        tx={tx} onUpdated={applyConversationSettings} onBusyChange={setSettingsBusy} onDelete={() => setConversationDeleteTarget(activeConversation)} onClose={toggleInspector}
        hasMoreAgents={agentPages.hasMore} agentsLoading={agentPages.loading} loadMoreAgents={()=>void agentPages.loadMore()}/>
      {activeProject && <RepositoryWorkbench request={request} projectId={activeProject.id} sourceId={workbenchSourceId} sources={workbenchSources} onSource={setSelectedWorkbenchSourceId} workspace={visibleWorkbenchWorkspace} busy={sending || preparingRepository || codingBusy} refreshKey={codingJobs[0]?.revision ?? done} onBranch={(branch, sourceId) => void changeWorkbenchBranch(branch, sourceId)} onPrepare={() => setRepositorySetup(true)} tx={tx} />}
      <details className="project-advanced-controls"><summary>{tx('advancedProjectControls')}</summary>
      <header><b>{tx('projectInspector')}</b><span>{sending ? 'LIVE' : settingsBusy ? 'SYNC' : 'READY'}</span></header>
      <section className="project-task-control"><div className="project-inspector-heading"><label>{tx('tasks')}</label><button type="button" disabled={!activeProject} onClick={() => setCreatingTask((value) => !value)}>＋</button></div>{creatingTask && <form className="project-task-form" onSubmit={submitTask}><select name="parentTaskId" defaultValue=""><option value="">{tx('rootTask')}</option>{tasks.filter((task) => !['COMPLETED', 'FAILED', 'CANCELLED'].includes(task.state)).map((task) => <option key={task.id} value={task.id}>{tx('parentTask')}: {task.title}</option>)}</select><input name="title" placeholder={tx('taskTitle')} required/><input name="goal" placeholder={tx('taskGoal')} required/><input name="description" placeholder={tx('description')}/><button disabled={loading}>{tx('createTask')}</button></form>}<div className="project-task-list">{tasks.slice(0, 8).map((task) => <button type="button" key={task.id} className={task.id === activeConversation?.activeTaskId ? 'active' : ''} disabled={!activeConversation || loading} onClick={() => void selectActiveTask(task.id)}><span>{task.parentTaskId ? '↳ ' : ''}{task.title}</span><b>{task.state}</b></button>)}{!tasks.length && <small>{tx('noTasks')}</small>}</div>{activeTask && <div className="task-action-bar">{!terminalTaskStates.has(activeTask.state) && <button type="button" disabled={loading} onClick={() => setTaskEditTarget(activeTask)}>{tx('edit')}</button>}{taskActions(activeTask.state).map((action) => <button type="button" key={action} disabled={loading} onClick={() => void transitionTask(action)}>{tx(taskActionLabels[action])}</button>)}</div>}{activeConversation?.activeTaskId && <button className="clear-task" type="button" disabled={loading} onClick={() => void selectActiveTask(null)}>{tx('clearTask')}</button>}</section>
      <section className="project-plan-control">
        <div className="project-inspector-heading"><label>{tx('taskPlans')}</label><button type="button" disabled={!activeTask || !childTasks.length} onClick={togglePlanDraft}>{creatingPlan ? '×' : '＋'}</button></div>
        {creatingPlan && activeTask && <form className="project-plan-form multi-step-plan" onSubmit={submitPlan}>
          {planDraftSteps.map((row, index) => <section className="plan-step-draft" key={row.clientId}>
            <header><b>STEP {String(index + 1).padStart(2, '0')}</b><button type="button" disabled={planDraftSteps.length === 1} onClick={() => removePlanDraftStep(row.clientId)} aria-label={tx('removePlanStep')}>×</button></header>
            <label><span>{tx('stepKey')}</span><input value={row.stepKey} onChange={(event) => renamePlanDraftStep(row.clientId, event.target.value)} maxLength={64} required/></label>
            <label><span>{tx('childTask')}</span><select value={row.childTaskId} onChange={(event) => updatePlanDraft(row.clientId, { childTaskId: event.target.value })} required><option value="">{tx('childTask')}</option>{childTasks.map((task) => <option key={task.id} value={task.id} disabled={planDraftSteps.some((other) => other.clientId !== row.clientId && other.childTaskId === task.id)}>{task.title}</option>)}</select></label>
            <label><span>{tx('expectedOutput')}</span><textarea value={row.expectedOutput} onChange={(event) => updatePlanDraft(row.clientId, { expectedOutput: event.target.value })} maxLength={8000} required/></label>
            <label><span>{tx('requiredCapability')}</span><input value={row.requiredCapability || ''} onChange={(event) => updatePlanDraft(row.clientId, { requiredCapability: event.target.value || null })} maxLength={120}/></label>
            <label><span>{tx('primaryAgent')}</span><select value={row.preferredAgentId || ''} onChange={(event) => updatePlanDraft(row.clientId, { preferredAgentId: event.target.value || null })}><option value="">{tx('noAgent')}</option>{availableAgents.map((agent) => <option key={agent.id} value={agent.id}>{agent.name}</option>)}</select></label>
            <fieldset className="plan-dependencies"><legend>{tx('dependencies')}</legend>{planDraftSteps.filter((other) => other.clientId !== row.clientId && other.stepKey.trim()).map((other) => <label key={other.clientId}><input type="checkbox" checked={row.dependsOnStepKeys.includes(other.stepKey)} onChange={(event) => togglePlanDependency(row.clientId, other.stepKey, event.target.checked)}/><span>{other.stepKey}</span></label>)}{planDraftSteps.length === 1 && <small>{tx('noDependencies')}</small>}</fieldset>
            <label><span>{tx('taskAcceptanceCriteria')}</span><textarea value={row.acceptanceCriteriaText} onChange={(event) => updatePlanDraft(row.clientId, { acceptanceCriteriaText: event.target.value })} maxLength={4000}/></label>
            <label className="plan-approval"><input type="checkbox" checked={row.approvalRequired} onChange={(event) => updatePlanDraft(row.clientId, { approvalRequired: event.target.checked })}/><span>{tx('approvalRequired')}</span></label>
          </section>)}
          <button className="secondary" type="button" disabled={planDraftSteps.length >= Math.min(100, childTasks.length)} onClick={addPlanDraftStep}>＋ {tx('addPlanStep')}</button>
          <button disabled={loading || !planDraftSteps.length}>{loading ? tx('processing') : tx('newPlan')}</button>
        </form>}
        {activeTask && !childTasks.length && <small>{tx('planNeedsChildTask')}</small>}
        <div className="project-plan-list">{plans.map((plan) => <article key={plan.id}><header><b>V{plan.versionNumber}</b><span>{plan.status}</span></header><small>{plan.steps.length} STEP</small>{plan.steps.length > 0 && <details className="plan-step-summary"><summary>{tx('viewPlanSteps')}</summary>{plan.steps.map((step) => <p key={step.id}><b>{step.stepKey}</b><span>{step.dependencyStepIds.length ? `${tx('dependsOn')}: ${step.dependencyStepIds.map((id) => plan.steps.find((candidate) => candidate.id === id)?.stepKey || id.slice(0, 8)).join(', ')}` : tx('noDependencies')}</span></p>)}</details>}<div>{plan.steps.length > 0 && <button type="button" disabled={loading} onClick={() => openAssignment(plan)}>{tx('configureAssignment')}</button>}{planActions(plan.status).map((action) => <button key={action} type="button" disabled={loading} onClick={() => void transitionPlan(plan, action)}>{tx(planActionLabels[action])}</button>)}</div></article>)}{activeTask && !plans.length && childTasks.length > 0 && <small>{tx('noTaskPlans')}</small>}</div>
        {assignmentPlan && <form className="project-assignment-form" onSubmit={submitStepAssignment}>
          <header><b>{tx('stepAssignment')}</b><button type="button" aria-label={tx('close')} onClick={() => setAssignmentPlanId('')}>×</button></header>
          <label><span>STEP</span><select value={assignmentStepId} onChange={(event) => setAssignmentStepId(event.target.value)} required>{assignmentPlan.steps.map((step) => <option key={step.id} value={step.id}>{step.sequence}. {step.expectedOutput}</option>)}</select></label>
          <label><span>{tx('assignmentSource')}</span><select value={assignmentSource} onChange={(event) => setAssignmentSource(event.target.value as 'default' | 'override')}><option value="default">{tx('planDefault')}</option><option value="override">{tx('stepOverride')}</option></select></label>
          <label><span>{tx('primaryAgent')}</span><select value={assignmentAgentId} onChange={(event) => setAssignmentAgentId(event.target.value)} required><option value="">{tx('noAgent')}</option>{availableAgents.map((agent) => <option key={agent.id} value={agent.id}>{agent.name}</option>)}</select></label>
          <label><span>{tx('reviewerAgent')}</span><select value={assignmentReviewerId} onChange={(event) => setAssignmentReviewerId(event.target.value)} required><option value="">{tx('reviewerAgent')}</option>{availableAgents.filter(({ id }) => id !== assignmentAgentId).map((agent) => <option key={agent.id} value={agent.id}>{agent.name}</option>)}</select></label>
          <div className="assignment-pool"><span>{tx('assignmentModelPool')}</span><b>{assignmentAgent?.modelPoolId || assignmentAgent?.modelId || '—'}</b></div>
          {stepAssignment ? <div className="assignment-evidence"><span>{tx('serverDerivedEvidence')}</span><b>{stepAssignment.source} · REV {stepAssignment.revision}</b><small>{stepAssignment.capabilityHash.slice(0, 12)} · {stepAssignment.configurationHash.slice(0, 12)}</small></div> : <small>{tx('noStepAssignment')}</small>}
          <button disabled={assignmentBusy || !(assignmentAgent?.modelPoolId || assignmentAgent?.modelProviderId && assignmentAgent?.modelId) || !assignmentReviewerId || assignmentReviewerId === assignmentAgentId}>{assignmentBusy ? tx('processing') : tx('saveAssignment')}</button>
        </form>}
      </section>
      <section className="project-intake-control"><div className="project-inspector-heading"><label>{tx('projectIntake')}</label><button type="button" disabled={!activeDirectory || !activeConversation || !activeAgent} onClick={() => setCreatingIntake((value) => !value)}>＋</button></div>{creatingIntake && <form className="project-intake-form" onSubmit={submitIntake}><textarea name="goal" placeholder={tx('projectIntakeGoal')} required maxLength={8000}/><small>{activeAgent ? `${activeAgent.name} · ${tx('currentConfiguration')}` : tx('noAgent')}</small><button disabled={loading || !activeAgent}>{tx('startProjectIntake')}</button></form>}<div className="project-intake-list">{intakes.map((job) => <article key={job.id}><header><b>{job.state}</b><span>REV {job.revision}</span></header><p>{job.goal}</p>{job.proposal && <div className="intake-proposal"><b>{job.proposal.rootTask.title}</b><small>{job.proposal.childTasks.length} TASKS · {job.proposal.steps.length} STEPS</small></div>}{job.safeErrorCode && <small className="project-bad">{job.safeErrorCode}</small>}{job.state === 'PROPOSED' && job.proposalHash && <div><button className="danger" disabled={loading} onClick={() => setIntakeRejectTarget(job)}>{tx('rejectProposal')}</button><button disabled={loading} onClick={() => void reviewIntake(job, 'confirm')}>{tx('confirmProposal')}</button></div>}</article>)}{!intakes.length && <small>{tx('noProjectIntakes')}</small>}</div></section>
      <section className="project-execution-control">
        <div className="project-inspector-heading"><label>{tx('projectExecution')}</label><button type="button" disabled={!executablePlan || Boolean(executionBusyId)} onClick={() => void manuallyRefreshExecutions()} aria-label={tx('refreshExecution')}>↻</button></div>
        {executablePlan && <div className="project-execution-launch"><select value={reviewerAgentId} onChange={(event) => setReviewerAgentId(event.target.value)} aria-label={tx('reviewerAgent')} disabled={executions.length > 0}><option value="">{tx('reviewerAgent')}</option>{availableAgents.filter((agent) => agent.id !== activeAgent?.id).map((agent) => <option key={agent.id} value={agent.id}>{agent.name}</option>)}</select><small>{activeAgent ? `${activeAgent.name} · ${tx('currentConfiguration')}` : tx('noAgent')} → {reviewerAgent ? `${reviewerAgent.name} · ${tx('currentConfiguration')}` : tx('reviewerAgentRequired')}</small><button disabled={loading || Boolean(executionBusyId) || executions.length > 0 || !activeConversation || !activeDirectory || !activeAgent || !reviewerAgent} onClick={() => void executePlan()}>{loading ? tx('processing') : tx('executePlan')}</button></div>}
        <div className="project-execution-list">{executions.map((execution) => <article key={execution.id}><header><b>{execution.state}</b><span>REV {execution.revision}</span></header><small>{execution.activeJobs.length} ACTIVE JOB · {execution.safeErrorCode || execution.baseRef}</small>{executionControl?.executionId === execution.id && <small>{tx('desiredState')}: {executionControl.desiredState} · {executionControl.controlReasonPresent ? tx('controlReasonRecorded') : tx('noControlReason')}</small>}{execution.activeJobs.map((job) => <div key={job.id}><span>{job.planStepId.slice(0, 8)}</span><b>{job.state}</b></div>)}{controlledExecution?.id === execution.id && <div className="project-execution-actions">{executionActions(execution.state).map((action) => <button type="button" key={action} disabled={Boolean(executionBusyId)} className={action === 'cancel' ? 'danger' : ''} onClick={() => action === 'resume' ? void applyExecutionAction(execution, action) : setExecutionActionTarget({ execution, action })}>{tx(action === 'pause' ? 'pauseExecution' : action === 'resume' ? 'resumeExecution' : 'cancelExecution')}</button>)}</div>}</article>)}</div>
        <div className="project-intake-list">{codingJobs.map((job) => <article key={job.id}><header><b>{job.state}</b><span>ITER {job.iteration} · REVIEW {job.reviewRound}</span></header><p>{job.pendingToolName || job.safeErrorCode || `${job.baseRef} · ${job.id.slice(0, 8)}`}</p>{job.pendingApprovalId && <small>{tx('approval')}: {job.pendingApprovalId}</small>}{job.codingRunId && <div>{['WAITING_APPROVAL', 'BLOCKED'].includes(job.state) && job.workspaceId && !handoffs.some(({ sourceCodingJobId }) => sourceCodingJobId === job.id) && <button disabled={loading || !projectConversations.some(({ conversationId: id }) => id !== job.conversationId) || !availableAgents.some(({ id }) => id !== job.agentId)} onClick={() => openHandoff(job)}>{tx('createHandoff')}</button>}<button disabled={loading} onClick={() => void captureRecovery(job)}>{tx('captureRecovery')}</button></div>}</article>)}{!codingJobs.length && !executions.length && <small>{tx('noProjectExecutions')}</small>}</div>
        {handoffs.filter((handoff) => handoff.projectDirectoryId === directoryId).slice(0, 5).map((handoff) => <div className="project-handoff" key={handoff.id}><span>HANDOFF</span><b>{handoff.state}</b><small>{handoff.sourceCodingJobId.slice(0, 8)} → {handoff.targetCodingJobId.slice(0, 8)}</small></div>)}
      </section>
      <section className="project-source-control">
        <div className="project-inspector-heading"><label>{tx('sourceRepositories')}</label><button type="button" disabled={!activeProject || !githubConnections.length} onClick={() => setCreatingSource((value) => !value)}>＋</button></div>
        {!githubConnections.length && <small>{tx('noGithubConnection')}</small>}
        {creatingSource && githubConnections.length > 0 && <form className="project-source-form" onSubmit={submitSource}>
          <select aria-label={tx('githubMcpConnection')} value={githubMcpConnectionId} onChange={(event) => { setGithubMcpConnectionId(event.target.value); setGithubRepositories([]) }} required>{githubConnections.map((connection) => <option key={connection.id} value={connection.id}>{connection.name}{connection.accountName ? ` · ${connection.accountName}` : ''}</option>)}</select>
          <button type="button" disabled={sourceBusy} onClick={() => void loadGithubRepositoryOptions()}>{sourceBusy ? tx('processing') : tx('loadRepositories')}</button>
          <select key={githubMcpConnectionId} name="providerRepositoryId" aria-label={tx('repository')} defaultValue=""><option value="">{tx('repository')}</option>{githubRepositories.map((repository) => <option key={repository.providerRepositoryId} value={repository.providerRepositoryId}>{repository.owner}/{repository.name}{repository.privateRepository ? ' · PRIVATE' : ''}</option>)}</select>
          <input name="githubUrl" type="url" placeholder={tx('publicGithubUrl')} />
          <button disabled={sourceBusy}>{sourceBusy ? tx('processing') : tx('importSource')}</button>
        </form>}
        <div className="project-resource-list">{sources.slice(0, 6).map((source) => <article key={source.id}><div><b>{source.displayName}</b><small>{source.visibility} · {source.state}</small></div>{source.state !== 'ARCHIVED' && <button className="danger" type="button" onClick={() => setResourceTarget({ kind: 'source', id: source.id, name: source.displayName })}>{tx('archive')}</button>}</article>)}{!sources.length && <small>{tx('noSourceRepositories')}</small>}</div>
      </section>
      <section className="project-workspace-control">
        <div className="project-inspector-heading"><label>{tx('workspaces')}</label><button type="button" disabled={!activeProject || !activeDirectory || !workspaceTasks.length || !activeDirectorySources.length} onClick={() => setCreatingWorkspace((value) => !value)}>＋</button></div>
        {creatingWorkspace && <form className="project-workspace-form" onSubmit={submitWorkspace}>
          <select name="taskId" aria-label={tx('workspaceTask')} defaultValue={activeTask?.id || ''} required><option value="">{tx('workspaceTask')}</option>{workspaceTasks.map((task) => <option key={task.id} value={task.id}>{task.title}</option>)}</select>
          <select name="sourceRepositoryId" aria-label={tx('sourceRepositories')} required><option value="">{tx('sourceRepositories')}</option>{activeDirectorySources.map((source) => <option key={source.id} value={source.id}>{source.displayName}</option>)}</select>
          <input name="baseRef" placeholder={tx('baseReference')} />
          <button disabled={loading}>{loading ? tx('processing') : tx('provisionWorkspace')}</button>
        </form>}
        <div className="project-resource-list">{workspaces.filter((workspace) => workspace.projectDirectoryId === directoryId).slice(0, 6).map((workspace) => <article key={workspace.id}><div><b>{repositoryBranchLabel(workspace.baseRef)}</b><small>{workspace.mode} · {workspace.state}</small></div>{!['ARCHIVED', 'CLEANED_UP'].includes(workspace.state) && <button className="danger" type="button" onClick={() => setResourceTarget({ kind: 'workspace', id: workspace.id, name: repositoryBranchLabel(workspace.baseRef) })}>{tx('archive')}</button>}</article>)}{!workspaces.some((workspace) => workspace.projectDirectoryId === directoryId) && <small>{tx('noWorkspaces')}</small>}</div>
      </section>
      <section><label>{tx('currentRun')}</label><dl><div><dt>EVENTS</dt><dd>{done?.eventCount ?? runtimeEventCount}</dd></div><div><dt>TOKENS</dt><dd>{done?.totalTokenCount ?? '—'}</dd></div></dl></section>
      </details>
    </aside>
    {rootDeleteTarget&&<div className="project-modal" role="dialog" aria-modal="true"><section className="project-dialog"><h2>{tx('deleteRoot')}</h2><p>{rootDeleteTarget.name}</p><p>{tx('deleteRootRetentionHint')}</p><div><button disabled={loading} onClick={()=>setRootDeleteTarget(null)}>{tx('cancel')}</button><button className="danger" disabled={loading} onClick={()=>void removeRoot()}>{tx('confirmRemove')}</button></div></section></div>}
    {codingGoal && <div className="project-modal" role="dialog" aria-modal="true"><section className="project-dialog"><h2>{tx('codingLaunchTitle')}</h2><p>{codingGoal}</p><p>{tx('codingLaunchHint')}</p><label><span>{tx('reviewerAgent')}</span><select value={reviewerAgentId} onChange={(event) => setReviewerAgentId(event.target.value)}><option value="">{tx('distinctReviewerRequired')}</option>{availableAgents.filter((agent) => agent.id !== activeAgent?.id).map((agent) => <option key={agent.id} value={agent.id}>{agent.name}</option>)}</select></label>{error && <p role="alert">{error}</p>}<div><button disabled={launchingCode} onClick={() => setCodingGoal(null)}>{tx('cancel')}</button><button disabled={launchingCode || !reviewerAgentId} onClick={() => void confirmCodingConversation()}>{tx(launchingCode ? 'processing' : 'confirmCodingLaunch')}</button></div></section></div>}
    {repositorySetup && activeProject && <div className="project-modal" role="dialog" aria-modal="true" aria-labelledby="repository-chat-title"><form className="project-dialog" onSubmit={prepareRepository}><h2 id="repository-chat-title">{tx('repositoryPrepare')}</h2><p>{tx('repositoryPrepareHint')}</p><label><span>{tx('repositoryChat')}</span><select name="sourceId" defaultValue={workbenchSourceId || activeDirectory?.sourceRepositoryId || readySources[0]?.id || ''} required disabled={preparingRepository}><option value="">{tx('repositorySourceRequired')}</option>{readySources.filter((source) => source.type !== 'LOCAL').map((source) => <option value={source.id} key={source.id}>{source.displayName}</option>)}</select></label><label><span>{tx('codeBranch')}</span><input name="baseRef" placeholder={readySources.find((source) => source.id === activeDirectory?.sourceRepositoryId)?.defaultBranch || 'main'} maxLength={255} disabled={preparingRepository}/></label>{error && <p role="alert">{error}</p>}{repositoryAuthRequired && <div className="repository-auth-recovery"><p>{tx('githubReauthorizeHint')}</p><a href="/app/mcp" target="_blank" rel="noopener noreferrer">{tx('openMcpReauthorization')}</a></div>}<div><button type="button" disabled={preparingRepository} onClick={() => setRepositorySetup(false)}>{tx('cancel')}</button><button className="primary" disabled={preparingRepository || !readySources.length}>{tx(preparingRepository ? 'processing' : 'repositoryPrepare')}</button></div></form></div>}
    {projectAction === 'edit' && activeDirectory && <div className="project-modal" role="dialog" aria-modal="true"><form className="project-dialog" onSubmit={submitProjectUpdate}><span>PROJECT / EDIT</span><h2>{tx('edit')}</h2><label><span>{tx('rootName')}</span><input name="name" defaultValue={activeDirectory.name} required /></label><div><button type="button" onClick={() => setProjectAction(null)}>{tx('cancel')}</button><button className="primary" disabled={loading}>{loading ? tx('saving') : tx('saveChanges')}</button></div></form></div>}
    {executionActionTarget && <div className="project-modal" role="dialog" aria-modal="true"><form className="project-dialog" onSubmit={(event) => { event.preventDefault(); const data = new FormData(event.currentTarget); void applyExecutionAction(executionActionTarget.execution, executionActionTarget.action, String(data.get('reason') || '')) }}><span>EXECUTION / {executionActionTarget.execution.id.slice(0, 8)} / REV {executionActionTarget.execution.revision}</span><h2>{tx(executionActionTarget.action === 'pause' ? 'pauseExecutionTitle' : 'cancelExecutionTitle')}</h2><p>{tx(executionActionTarget.action === 'pause' ? 'pauseExecutionHint' : 'cancelExecutionHint')}</p><label><span>{tx('executionControlReason')}</span><textarea name="reason" required maxLength={240}/></label><div><button type="button" onClick={() => setExecutionActionTarget(null)}>{tx('cancel')}</button><button className={executionActionTarget.action === 'cancel' ? 'danger' : 'primary'} disabled={Boolean(executionBusyId)}>{executionBusyId ? tx('processing') : tx('confirm')}</button></div></form></div>}
    {taskEditTarget && activeProject && <div className="project-modal" role="dialog" aria-modal="true"><form className="project-dialog task-dialog" onSubmit={submitTaskUpdate}><span>TASK / {taskEditTarget.id.slice(0, 8)}</span><h2>{tx('editTask')}</h2><label><span>{tx('taskTitle')}</span><input name="title" defaultValue={taskEditTarget.title} required maxLength={200}/></label><label><span>{tx('taskGoal')}</span><textarea name="goal" defaultValue={taskEditTarget.goal} required/></label><label><span>{tx('description')}</span><textarea name="description" defaultValue={taskEditTarget.description || ''}/></label><label><span>{tx('taskConstraints')}</span><textarea name="constraints" defaultValue={taskEditTarget.constraints.join('\n')}/></label><label><span>{tx('taskAcceptanceCriteria')}</span><textarea name="acceptanceCriteria" defaultValue={taskEditTarget.acceptanceCriteria.join('\n')}/></label><div><button type="button" onClick={() => setTaskEditTarget(null)}>{tx('cancel')}</button><button className="primary" disabled={loading}>{loading ? tx('saving') : tx('updateTask')}</button></div></form></div>}
    {handoffTarget && <div className="project-modal" role="dialog" aria-modal="true"><form className="project-dialog handoff-dialog" onSubmit={submitHandoff}><span>HANDOFF / {handoffTarget.id.slice(0, 8)}</span><h2>{tx('handoffTitle')}</h2><p>{tx('handoffEligibility')}</p><label><span>{tx('targetConversation')}</span><select value={handoffConversationId} onChange={(event) => { const nextId = event.target.value; setHandoffConversationId(nextId); const nextAgentId = projectConversations.find(({ conversationId: id }) => id === nextId)?.agentId; if (nextAgentId && nextAgentId !== handoffTarget.agentId) setHandoffTargetAgentId(nextAgentId) }} required><option value="">{tx('targetConversation')}</option>{handoffConversations.map((conversation) => <option key={conversation.conversationId} value={conversation.conversationId}>{conversation.title}</option>)}</select></label><label><span>{tx('targetAgent')}</span><select value={handoffTargetAgentId} onChange={(event) => setHandoffTargetAgentId(event.target.value)} required><option value="">{tx('targetAgent')}</option>{handoffAgents.map((agent) => <option key={agent.id} value={agent.id}>{agent.name}</option>)}</select></label><label><span>{tx('reviewerAgent')}</span><input value={agents.find(({ id }) => id === handoffTarget.reviewerAgentId)?.name || handoffTarget.reviewerAgentId || '—'} readOnly/></label><div><button type="button" onClick={() => setHandoffTarget(null)}>{tx('cancel')}</button><button className="primary" disabled={loading || !handoffConversationId || !handoffTargetAgentId || !handoffTarget.reviewerAgentId}>{loading ? tx('processing') : tx('createHandoff')}</button></div></form></div>}
    {conversationDeleteTarget && <div className="project-modal" role="dialog" aria-modal="true"><div className="project-dialog"><span>{tx('systemConfirmation')}</span><h2>{tx('deleteConversation')}</h2><p>{conversationDeleteTarget.title}</p><div><button disabled={loading} onClick={() => setConversationDeleteTarget(null)}>{tx('cancel')}</button><button className="danger" disabled={loading} onClick={() => void deleteActiveConversation()}>{loading ? tx('processing') : tx('confirmRemove')}</button></div></div></div>}
    {resourceTarget && <div className="project-modal" role="dialog" aria-modal="true"><div className="project-dialog"><span>{tx('systemConfirmation')}</span><h2>{tx(resourceTarget.kind === 'source' ? 'archiveSource' : 'archiveWorkspace')}</h2><p>{resourceTarget.name}</p><div><button onClick={() => setResourceTarget(null)}>{tx('cancel')}</button><button className="danger" disabled={loading} onClick={() => void archiveResource()}>{loading ? tx('processing') : tx('confirmArchive')}</button></div></div></div>}
    {intakeRejectTarget && <div className="project-modal" role="dialog" aria-modal="true"><form className="project-dialog" onSubmit={(event) => { event.preventDefault(); const data = new FormData(event.currentTarget); void reviewIntake(intakeRejectTarget, 'reject', String(data.get('reason') || '').trim()) }}><span>{tx('systemConfirmation')}</span><h2>{tx('rejectProposal')}</h2><label><span>{tx('projectIntakeRejectReason')}</span><textarea name="reason" required maxLength={500}/></label><div><button type="button" onClick={() => setIntakeRejectTarget(null)}>{tx('cancel')}</button><button className="danger" disabled={loading}>{loading ? tx('processing') : tx('confirm')}</button></div></form></div>}
    {recoveryPackage && <div className="project-modal" role="dialog" aria-modal="true"><div className="project-dialog recovery-dialog"><span>RECOVERY / {recoveryPackage.snapshotId.slice(0, 8)}</span><h2>{tx('recoveryPackage')}</h2><div className="recovery-grid"><div><span>{tx('activeProjectDirectory')}</span><b>{recoveryPackage.context.project.directoryName}</b></div><div><span>RUN</span><b>{recoveryPackage.context.runtime.state}</b></div><div><span>{tx('tasks')}</span><b>{recoveryPackage.context.task.title}</b></div><div><span>{tx('nextAction')}</span><b>{recoveryPackage.context.nextAction || '—'}</b></div></div>{recoveryPackage.context.blockers.length > 0 && <section><b>{tx('blockers')}</b>{recoveryPackage.context.blockers.map((blocker) => <p key={blocker}>{blocker}</p>)}</section>}<div><button onClick={() => setRecoveryPackage(null)}>{tx('close')}</button></div></div></div>}
  </section>
}
