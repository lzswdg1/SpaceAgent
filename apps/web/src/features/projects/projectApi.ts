import { getConversation, listAgents, listConversations } from '../chat/chatApi'
import type { AgentSummary, AuthenticatedRequest, Conversation, ConversationSummary } from '../chat/types'
import { loadMcpRegistry } from '../mcp/mcpApi'
import type { GithubConnectionOption, GithubMcpRepository, GovernanceApproval, Project, ProjectCodingJob, ProjectConversation, ProjectDirectory, ProjectIntakeJob, ProjectPlanExecution, ProjectPlanExecutionControl, ProjectPlanStepAssignment, ProjectRunHandoff, ProjectRunHandoffResult, RecoveryPackage, SourceRepository, Task, TaskPlan, TaskPlanDraftStep, Workspace } from './types'

export type ProjectIndex = {
  projects: Project[]
  agents: AgentSummary[]
  conversations: ProjectConversation[]
  total: number
}

export async function loadProjectIndex(request: AuthenticatedRequest,page=1): Promise<ProjectIndex> {
  const [projects, agents, conversationPage] = await Promise.all([
    request<Project[]>('/api/v1/projects'),
    listAgents(request),
    listConversations(request,page,'PROJECT'),
  ])
  const details = await Promise.all(conversationPage.items.map(async (summary: ConversationSummary) => summary.userId ? ({
    conversationId:summary.id,agentId:summary.agentId,userId:summary.userId,projectId:summary.projectId??null,
    projectDirectoryId:summary.projectDirectoryId??null,activeTaskId:summary.activeTaskId??null,status:summary.status,
    startedAt:summary.createdAt,lastMessageAt:summary.updatedAt,messages:[],messagePage:1,messageSize:0,messageTotal:0,title:summary.title,
  }) : ({...await getConversation(request,summary.id),title:summary.title})))
  return { projects, agents, conversations: details.filter(({ projectId }) => projectId !== null),total:conversationPage.total }
}

export const listProjectWorkspaces = (
  request: AuthenticatedRequest,
  projectId: string,
  init?: RequestInit,
) => init ? request<Workspace[]>(`/api/v1/projects/${encodeURIComponent(projectId)}/workspaces`, init)
  : request<Workspace[]>(`/api/v1/projects/${encodeURIComponent(projectId)}/workspaces`)

export const loadProjectContext = async (request: AuthenticatedRequest, projectId: string) => {
  const encoded = encodeURIComponent(projectId)
  const [tasks, workspaces, sources, directories, handoffs] = await Promise.all([
    request<Task[]>(`/api/v1/projects/${encoded}/tasks`),
    listProjectWorkspaces(request, projectId),
    request<SourceRepository[]>(`/api/v1/projects/${encoded}/sources`),
    request<ProjectDirectory[]>(`/api/v1/projects/${encoded}/directories`),
    request<{ items: ProjectRunHandoff[]; page: number; pageSize: number; total: number }>(`/api/v1/projects/${encoded}/run-handoffs?page=1&pageSize=20`),
  ])
  return { tasks, workspaces, sources, directories, handoffs: handoffs.items }
}

export const loadGithubConnectionOptions = async (
  request: AuthenticatedRequest,
): Promise<GithubConnectionOption[]> => {
  const { catalog, installations, connections } = await loadMcpRegistry(request)
  const githubEntryIds = new Set(catalog.filter(({ slug }) => slug === 'github').map(({ id }) => id))
  const githubInstallations = new Map(installations
    .filter(({ entryId, state }) => githubEntryIds.has(entryId) && state === 'INSTALLED')
    .map((installation) => [installation.id, installation]))
  return connections
    .filter(({ installationId, state }) => state === 'ACTIVE' && githubInstallations.has(installationId))
    .map((connection) => ({
      id: connection.id,
      name: githubInstallations.get(connection.installationId)?.displayName || 'GitHub',
      accountName: connection.externalAccountName,
    }))
}

export const listGithubRepositories = (
  request: AuthenticatedRequest,
  connectionId: string,
) => request<GithubMcpRepository[]>(
  `/api/v1/github-mcp/connections/${encodeURIComponent(connectionId)}/repositories`,
)

export const importGithubMcpSource = (
  request: AuthenticatedRequest,
  projectId: string,
  input: { connectionId: string; providerRepositoryId?: string | null; githubUrl?: string | null },
  idempotencyKey: string,
) => request<SourceRepository>(`/api/v1/projects/${encodeURIComponent(projectId)}/sources/github-mcp`, {
  method: 'POST',
  headers: { 'Idempotency-Key': idempotencyKey },
  body: JSON.stringify({
    connectionId: input.connectionId,
    providerRepositoryId: input.providerRepositoryId || null,
    githubUrl: input.githubUrl || null,
  }),
})

export const archiveProjectSource = (
  request: AuthenticatedRequest,
  projectId: string,
  sourceId: string,
) => request<SourceRepository>(
  `/api/v1/projects/${encodeURIComponent(projectId)}/sources/${encodeURIComponent(sourceId)}`,
  { method: 'DELETE' },
)

export const provisionProjectWorkspace = (
  request: AuthenticatedRequest,
  projectId: string,
  input: { projectDirectoryId: string; taskId: string; sourceRepositoryId: string; baseRef?: string | null },
) => request<Workspace>(`/api/v1/projects/${encodeURIComponent(projectId)}/workspaces`, {
  method: 'POST',
  body: JSON.stringify({
    projectDirectoryId: input.projectDirectoryId,
    taskId: input.taskId,
    sourceRepositoryId: input.sourceRepositoryId,
    baseRef: input.baseRef || null,
  }),
})

export const archiveProjectWorkspace = (
  request: AuthenticatedRequest,
  projectId: string,
  workspaceId: string,
) => request<Workspace>(
  `/api/v1/projects/${encodeURIComponent(projectId)}/workspaces/${encodeURIComponent(workspaceId)}`,
  { method: 'DELETE' },
)

export const createProject = (request: AuthenticatedRequest, name: string, description: string) => request<Project>('/api/v1/projects', {
  method: 'POST', body: JSON.stringify({ name, description: description || null }),
})

export const updateProject = (request: AuthenticatedRequest, projectId: string, name: string, description: string) => request<Project>(
  `/api/v1/projects/${encodeURIComponent(projectId)}`,
  { method: 'PATCH', body: JSON.stringify({ name, description: description || null }) },
)

export const archiveProject = (request: AuthenticatedRequest, projectId: string) => request<Project>(
  `/api/v1/projects/${encodeURIComponent(projectId)}`,
  { method: 'DELETE' },
)

export const createProjectTask = (
  request: AuthenticatedRequest,
  projectId: string,
  input: { parentTaskId?: string | null; title: string; goal: string; description?: string | null },
) => request<Task>(`/api/v1/projects/${encodeURIComponent(projectId)}/tasks`, {
  method: 'POST',
  body: JSON.stringify({
    parentTaskId: input.parentTaskId || null,
    title: input.title,
    goal: input.goal,
    description: input.description || null,
    constraints: [],
    acceptanceCriteria: [],
  }),
})

export const updateProjectTask = (
  request: AuthenticatedRequest,
  projectId: string,
  taskId: string,
  input: Pick<Task, 'title' | 'goal' | 'description' | 'constraints' | 'acceptanceCriteria'>,
) => request<Task>(
  `/api/v1/projects/${encodeURIComponent(projectId)}/tasks/${encodeURIComponent(taskId)}`,
  { method: 'PATCH', body: JSON.stringify(input) },
)

export const setProjectConversationActiveTask = (
  request: AuthenticatedRequest,
  conversationId: string,
  taskId: string | null,
) => request<Conversation>(
  `/api/v1/chat/conversations/${encodeURIComponent(conversationId)}/active-task`,
  { method: 'PUT', body: JSON.stringify({ taskId }) },
)

export type TaskTransition = 'ready' | 'start' | 'block' | 'complete' | 'fail' | 'cancel'

export const transitionProjectTask = (
  request: AuthenticatedRequest,
  projectId: string,
  taskId: string,
  transition: TaskTransition,
) => request<Task>(
  `/api/v1/projects/${encodeURIComponent(projectId)}/tasks/${encodeURIComponent(taskId)}/${transition}`,
  { method: 'POST' },
)

export const listTaskPlans = (
  request: AuthenticatedRequest,
  projectId: string,
  rootTaskId: string,
) => request<TaskPlan[]>(
  `/api/v1/projects/${encodeURIComponent(projectId)}/tasks/${encodeURIComponent(rootTaskId)}/plans`,
)

export const createTaskPlan = (
  request: AuthenticatedRequest,
  projectId: string,
  rootTaskId: string,
  input: { steps: TaskPlanDraftStep[] },
) => request<TaskPlan>(
  `/api/v1/projects/${encodeURIComponent(projectId)}/tasks/${encodeURIComponent(rootTaskId)}/plans`,
  {
    method: 'POST',
    body: JSON.stringify({
      generatedByAgentId: null,
      generatedByRunConfigurationSnapshotId: null,
      steps: input.steps,
    }),
  },
)

export type TaskPlanAction = 'propose' | 'approve' | 'activate' | 'complete' | 'cancel'

export const transitionTaskPlan = (
  request: AuthenticatedRequest,
  projectId: string,
  rootTaskId: string,
  planId: string,
  action: TaskPlanAction,
) => request<TaskPlan>(
  `/api/v1/projects/${encodeURIComponent(projectId)}/tasks/${encodeURIComponent(rootTaskId)}/plans/${encodeURIComponent(planId)}/${action}`,
  { method: 'POST' },
)

const stepAssignmentPath = (projectId: string, taskPlanId: string, planStepId: string) =>
  `/api/v1/projects/${encodeURIComponent(projectId)}/task-plans/${encodeURIComponent(taskPlanId)}/steps/${encodeURIComponent(planStepId)}/assignment`

export const getProjectPlanStepAssignment = (
  request: AuthenticatedRequest,
  projectId: string,
  taskPlanId: string,
  planStepId: string,
) => request<ProjectPlanStepAssignment>(stepAssignmentPath(projectId, taskPlanId, planStepId))

export const defineProjectPlanStepAssignment = (
  request: AuthenticatedRequest,
  projectId: string,
  taskPlanId: string,
  planStepId: string,
  source: 'default' | 'override',
  input: {
    agentId: string
    reviewerAgentId: string
    modelPoolId: string | null
  },
) => request<ProjectPlanStepAssignment>(`${stepAssignmentPath(projectId, taskPlanId, planStepId)}/${source}`, {
  method: 'POST', body: JSON.stringify(input),
})

export const createProjectConversation = (
  request: AuthenticatedRequest,
  input: { projectId: string; projectDirectoryId: string; agentId: string; activeTaskId?: string | null; name: string },
) => request<Conversation>('/api/v1/chat/conversations', {
  method: 'POST',
  body: JSON.stringify(input),
})

export const createProjectDirectory = (
  request: AuthenticatedRequest, projectId: string,
  input: { sourceRepositoryId: string; name: string; relativePath: string },
) => request<ProjectDirectory>(`/api/v1/projects/${encodeURIComponent(projectId)}/directories`, {
  method: 'POST', body: JSON.stringify(input),
})

export const archiveProjectDirectory = (request: AuthenticatedRequest, projectId: string, directoryId: string) => request<ProjectDirectory>(
  `/api/v1/projects/${encodeURIComponent(projectId)}/directories/${encodeURIComponent(directoryId)}`, { method: 'DELETE' },
)

export const listProjectIntakes = (request: AuthenticatedRequest, projectId: string, directoryId: string) => request<{ items: ProjectIntakeJob[]; page: number; pageSize: number; total: number }>(
  `/api/v1/projects/${encodeURIComponent(projectId)}/directories/${encodeURIComponent(directoryId)}/intakes?page=1&pageSize=20`,
)

export const enqueueProjectIntake = (
  request: AuthenticatedRequest, projectId: string, directoryId: string,
  input: { conversationId: string; agentId: string; goal: string },
) => request<ProjectIntakeJob>(`/api/v1/projects/${encodeURIComponent(projectId)}/directories/${encodeURIComponent(directoryId)}/intakes`, {
  method: 'POST', headers: { 'Idempotency-Key': crypto.randomUUID() }, body: JSON.stringify(input),
})

export const reviewProjectIntake = (
  request: AuthenticatedRequest, projectId: string, directoryId: string, jobId: string,
  input: { proposalHash: string; decision: 'confirm' | 'reject'; reason?: string },
) => request<ProjectIntakeJob>(`/api/v1/projects/${encodeURIComponent(projectId)}/directories/${encodeURIComponent(directoryId)}/intakes/${encodeURIComponent(jobId)}/${input.decision}`, {
  method: 'POST', body: JSON.stringify(input.decision === 'confirm' ? { proposalHash: input.proposalHash } : { proposalHash: input.proposalHash, reason: input.reason }),
})

export const listProjectCodingJobs = (
  request: AuthenticatedRequest, projectId: string, planId: string, stepId: string,
  init?: RequestInit,
) => init ? request<{ items: ProjectCodingJob[]; page: number; pageSize: number; total: number }>(
  `/api/v1/projects/${encodeURIComponent(projectId)}/task-plans/${encodeURIComponent(planId)}/steps/${encodeURIComponent(stepId)}/coding-jobs?page=1&pageSize=20`,
  init,
) : request<{ items: ProjectCodingJob[]; page: number; pageSize: number; total: number }>(
  `/api/v1/projects/${encodeURIComponent(projectId)}/task-plans/${encodeURIComponent(planId)}/steps/${encodeURIComponent(stepId)}/coding-jobs?page=1&pageSize=20`,
)

export const decideProjectApproval = (request: AuthenticatedRequest, approvalId: string, decision: 'APPROVED' | 'REJECTED', note: string) => request<GovernanceApproval>(
  `/api/v1/governance/approvals/${encodeURIComponent(approvalId)}/decision`, { method: 'POST', body: JSON.stringify({ decision, note }) },
)

export const captureProjectRecovery = (request: AuthenticatedRequest, projectId: string, agentRunId: string) => request<RecoveryPackage>(
  `/api/v1/projects/${encodeURIComponent(projectId)}/runs/${encodeURIComponent(agentRunId)}/recovery-packages`, { method: 'POST', headers: { 'Idempotency-Key': crypto.randomUUID() } },
)

export const executeProjectPlan = (
  request: AuthenticatedRequest, projectId: string, rootTaskId: string, planId: string,
  input: { projectDirectoryId: string; conversationId: string; sourceRepositoryId: string; agentId: string; reviewerAgentId: string; baseRef: string },
) => request<ProjectPlanExecution>(`/api/v1/projects/${encodeURIComponent(projectId)}/task-plans/${encodeURIComponent(planId)}/executions`, {
  method: 'POST',
  headers: { 'Idempotency-Key': crypto.randomUUID() },
  body: JSON.stringify({ ...input, rootTaskId }),
})

export const listProjectPlanExecutions = (request: AuthenticatedRequest, projectId: string, taskPlanId: string) => request<{ items: ProjectPlanExecution[]; page: number; pageSize: number; total: number }>(
  `/api/v1/projects/${encodeURIComponent(projectId)}/task-plans/${encodeURIComponent(taskPlanId)}/executions?page=1&pageSize=20`,
)

const projectExecutionPath = (projectId: string, taskPlanId: string, executionId: string) =>
  `/api/v1/projects/${encodeURIComponent(projectId)}/task-plans/${encodeURIComponent(taskPlanId)}/executions/${encodeURIComponent(executionId)}`

export const getProjectPlanExecutionControl = (
  request: AuthenticatedRequest, projectId: string, taskPlanId: string, executionId: string,
) => request<ProjectPlanExecutionControl>(`${projectExecutionPath(projectId, taskPlanId, executionId)}/control`)

export type ProjectExecutionAction = 'pause' | 'resume' | 'cancel'

export const controlProjectPlanExecution = (
  request: AuthenticatedRequest, projectId: string, taskPlanId: string,
  executionId: string, action: ProjectExecutionAction, expectedRevision: number, reason?: string,
) => request<ProjectPlanExecution>(`${projectExecutionPath(projectId, taskPlanId, executionId)}/${action}`, {
  method: 'POST',
  body: JSON.stringify(action === 'resume' ? { expectedRevision } : { expectedRevision, reason }),
})

export const createProjectRunHandoff = (
  request: AuthenticatedRequest,
  projectId: string,
  job: Pick<ProjectCodingJob, 'taskPlanId' | 'planStepId' | 'id'>,
  input: { targetConversationId: string; targetAgentId: string; reviewerAgentId: string },
  idempotencyKey: string,
) => request<ProjectRunHandoffResult>(
  `/api/v1/projects/${encodeURIComponent(projectId)}/task-plans/${encodeURIComponent(job.taskPlanId)}/steps/${encodeURIComponent(job.planStepId)}/coding-jobs/${encodeURIComponent(job.id)}/handoffs`,
  {
    method: 'POST',
    headers: { 'Idempotency-Key': idempotencyKey },
    body: JSON.stringify(input),
  },
)
