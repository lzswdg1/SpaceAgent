import { describe, expect, it, vi } from 'vitest'
import { archiveProject, archiveProjectDirectory, archiveProjectSource, archiveProjectWorkspace, captureProjectRecovery, controlProjectPlanExecution, createProjectConversation, createProjectDirectory, createProjectRunHandoff, createProjectTask, createTaskPlan, decideProjectApproval, defineProjectPlanStepAssignment, enqueueProjectIntake, executeProjectPlan, getProjectPlanExecutionControl, getProjectPlanStepAssignment, importGithubMcpSource, listGithubRepositories, listProjectCodingJobs, listProjectIntakes, listProjectPlanExecutions, listProjectWorkspaces, listTaskPlans, loadGithubConnectionOptions, loadProjectContext, provisionProjectWorkspace, reviewProjectIntake, setProjectConversationActiveTask, transitionProjectTask, transitionTaskPlan, updateProject, updateProjectTask } from './projectApi'
import type { AuthenticatedRequest } from '../overview/types'
import { loadProjectIndex } from './projectApi'
import { loadMessagePage,listConversations } from '../chat/chatApi'

describe('loadProjectContext', () => {
  it('uses scoped conversation summaries without per-conversation detail requests and supports subsequent pages',async()=>{
    const request=vi.fn(async(path:string)=>{
      if(path==='/api/v1/projects'||path==='/api/v1/agents?summary=true&offset=0&limit=100')return []
      if(path.includes('/chat/conversations?'))return {items:[{id:'c',agentId:'a',userId:'u',projectId:'p',projectDirectoryId:'d',activeTaskId:'t',title:'Title',status:'ACTIVE',createdAt:'now',updatedAt:'now'}],page:2,size:100,total:101}
      if(path.includes('/messages/page'))return {items:[],hasMore:false,beforeSequence:null}
      throw Error(path)
    }) as unknown as AuthenticatedRequest
    const index=await loadProjectIndex(request,2)
    expect(index.total).toBe(101);expect(index.conversations[0]).toMatchObject({conversationId:'c',activeTaskId:'t'})
    expect(request).toHaveBeenCalledWith('/api/v1/chat/conversations?page=2&size=100&scope=PROJECT')
    expect(request).not.toHaveBeenCalledWith('/api/v1/chat/conversations/c')
    await loadMessagePage(request,'c/1',51)
    expect(request).toHaveBeenCalledWith('/api/v1/chat/conversations/c%2F1/messages/page?size=50&beforeSequence=51')
    await listConversations(request,2,'CHAT','old title')
    expect(request).toHaveBeenCalledWith('/api/v1/chat/conversations?page=2&size=100&scope=CHAT&query=old%20title')
  })
  it('loads tasks and workspaces from project-owned routes', async () => {
    const mock = vi.fn(async <T,>(path: string): Promise<T> => {
      if (path.endsWith('/tasks')) return [{ id: 'task-1' }] as T
      if (path.endsWith('/workspaces')) return [{ id: 'workspace-1' }] as T
      if (path.endsWith('/sources')) return [{ id: 'source-1' }] as T
      if (path.endsWith('/directories')) return [{ id: 'directory-1' }] as T
      if (path.includes('/run-handoffs')) return { items: [] } as T
      throw new Error(path)
    }) as AuthenticatedRequest & ReturnType<typeof vi.fn>
    const result = await loadProjectContext(mock, 'project 1')
    expect(result.tasks).toHaveLength(1)
    expect(result.sources).toHaveLength(1)
    expect(result.directories).toHaveLength(1)
    expect(mock).toHaveBeenCalledWith('/api/v1/projects/project%201/workspaces')
    await listProjectWorkspaces(mock, 'project/2')
    expect(mock).toHaveBeenCalledWith('/api/v1/projects/project%2F2/workspaces')
  })

  it('updates and archives through the canonical Project route', async () => {
    const request = vi.fn(async () => ({})) as unknown as AuthenticatedRequest
    await updateProject(request, 'project/1', 'Project', 'Description')
    await archiveProject(request, 'project/1')
    expect(request).toHaveBeenNthCalledWith(1, '/api/v1/projects/project%2F1', expect.objectContaining({ method: 'PATCH' }))
    expect(request).toHaveBeenNthCalledWith(2, '/api/v1/projects/project%2F1', { method: 'DELETE' })
  })

  it('creates Tasks and persists the Conversation active Task', async () => {
    const request = vi.fn(async () => ({})) as unknown as AuthenticatedRequest
    await createProjectTask(request, 'project/1', { title: 'Review', goal: 'Ship safely' })
    await setProjectConversationActiveTask(request, 'conversation/1', 'task/1')
    expect(request).toHaveBeenNthCalledWith(1, '/api/v1/projects/project%2F1/tasks', expect.objectContaining({ method: 'POST' }))
    expect(request).toHaveBeenNthCalledWith(2, '/api/v1/chat/conversations/conversation%2F1/active-task', {
      method: 'PUT', body: JSON.stringify({ taskId: 'task/1' }),
    })
  })

  it('updates Task intent through the canonical partial-update route', async () => {
    const request = vi.fn(async () => ({})) as unknown as AuthenticatedRequest
    const input = {
      title: 'Review runtime',
      goal: 'Ship safely',
      description: null,
      constraints: ['No remote push'],
      acceptanceCriteria: ['Focused tests pass'],
    }
    await updateProjectTask(request, 'project/1', 'task/1', input)
    expect(request).toHaveBeenCalledWith('/api/v1/projects/project%2F1/tasks/task%2F1', {
      method: 'PATCH', body: JSON.stringify(input),
    })
  })

  it('uses canonical Task lifecycle and TaskPlan routes', async () => {
    const request = vi.fn(async () => ({})) as unknown as AuthenticatedRequest
    await transitionProjectTask(request, 'project/1', 'task/1', 'ready')
    await listTaskPlans(request, 'project/1', 'task/1')
    await createTaskPlan(request, 'project/1', 'task/1', {
      steps: [
        { stepKey: 'inspect', childTaskId: 'child/1', dependsOnStepKeys: [], requiredCapability: 'review',
          preferredAgentId: 'agent/1', expectedOutput: 'Inspection', acceptanceCriteria: ['Evidence'], approvalRequired: false },
        { stepKey: 'implement', childTaskId: 'child/2', dependsOnStepKeys: ['inspect'], requiredCapability: null,
          preferredAgentId: null, expectedOutput: 'Reviewed change', acceptanceCriteria: [], approvalRequired: true },
      ],
    })
    await transitionTaskPlan(request, 'project/1', 'task/1', 'plan/1', 'propose')
    expect(request).toHaveBeenNthCalledWith(1, '/api/v1/projects/project%2F1/tasks/task%2F1/ready', { method: 'POST' })
    expect(request).toHaveBeenNthCalledWith(2, '/api/v1/projects/project%2F1/tasks/task%2F1/plans')
    expect(JSON.parse(String((request as unknown as ReturnType<typeof vi.fn>).mock.calls[2][1].body))).toMatchObject({
      generatedByAgentId: null,
      generatedByRunConfigurationSnapshotId: null,
      steps: [{ stepKey: 'inspect', dependsOnStepKeys: [] }, { stepKey: 'implement', dependsOnStepKeys: ['inspect'] }],
    })
    expect(request).toHaveBeenNthCalledWith(4, '/api/v1/projects/project%2F1/tasks/task%2F1/plans/plan%2F1/propose', { method: 'POST' })
  })

  it('uses the path-free GitHub MCP Source and Project Workspace routes', async () => {
    const request = vi.fn(async () => ({})) as unknown as AuthenticatedRequest
    await listGithubRepositories(request, 'connection/1')
    await importGithubMcpSource(request, 'project/1', {
      connectionId: 'connection/1', providerRepositoryId: 'repo/1', githubUrl: 'https://github.com/acme/repo',
    }, 'import-key-1')
    await provisionProjectWorkspace(request, 'project/1', {
      projectDirectoryId: 'directory/1', taskId: 'task/1', sourceRepositoryId: 'source/1', baseRef: 'main',
    })
    await archiveProjectWorkspace(request, 'project/1', 'workspace/1')
    await archiveProjectSource(request, 'project/1', 'source/1')
    expect(request).toHaveBeenNthCalledWith(1, '/api/v1/github-mcp/connections/connection%2F1/repositories')
    expect(request).toHaveBeenNthCalledWith(2, '/api/v1/projects/project%2F1/sources/github-mcp', expect.objectContaining({
      method: 'POST', headers: { 'Idempotency-Key': 'import-key-1' },
    }))
    expect(request).toHaveBeenNthCalledWith(3, '/api/v1/projects/project%2F1/workspaces', expect.objectContaining({ method: 'POST' }))
    expect(request).toHaveBeenNthCalledWith(4, '/api/v1/projects/project%2F1/workspaces/workspace%2F1', { method: 'DELETE' })
    expect(request).toHaveBeenNthCalledWith(5, '/api/v1/projects/project%2F1/sources/source%2F1', { method: 'DELETE' })
  })

  it('maps ProjectDirectory, directory Conversation and Intake routes', async () => {
    const request = vi.fn(async () => ({})) as unknown as AuthenticatedRequest
    await createProjectDirectory(request, 'project/1', { sourceRepositoryId: 'source/1', name: 'Runtime', relativePath: 'apps/runtime' })
    await archiveProjectDirectory(request, 'project/1', 'directory/1')
    await createProjectConversation(request, { projectId: 'project/1', projectDirectoryId: 'directory/1', agentId: 'agent/1', name: 'Review' })
    await listProjectIntakes(request, 'project/1', 'directory/1')
    await enqueueProjectIntake(request, 'project/1', 'directory/1', { conversationId: 'conversation/1', agentId: 'agent/1', goal: 'Map runtime' })
    await reviewProjectIntake(request, 'project/1', 'directory/1', 'job/1', { proposalHash: 'sha256:' + 'a'.repeat(64), decision: 'confirm' })

    expect(request).toHaveBeenNthCalledWith(1, '/api/v1/projects/project%2F1/directories', expect.objectContaining({ method: 'POST' }))
    expect(request).toHaveBeenNthCalledWith(2, '/api/v1/projects/project%2F1/directories/directory%2F1', { method: 'DELETE' })
    expect(request).toHaveBeenNthCalledWith(4, '/api/v1/projects/project%2F1/directories/directory%2F1/intakes?page=1&pageSize=20')
    expect(request).toHaveBeenNthCalledWith(6, '/api/v1/projects/project%2F1/directories/directory%2F1/intakes/job%2F1/confirm', expect.objectContaining({ method: 'POST' }))
  })

  it('maps autonomous execution evidence, approval and recovery routes', async () => {
    const request = vi.fn(async () => ({})) as unknown as AuthenticatedRequest
    await listProjectCodingJobs(request, 'project/1', 'plan/1', 'step/1')
    await decideProjectApproval(request, 'approval/1', 'APPROVED', 'reviewed')
    await captureProjectRecovery(request, 'project/1', 'run/1')
    expect(request).toHaveBeenNthCalledWith(1, '/api/v1/projects/project%2F1/task-plans/plan%2F1/steps/step%2F1/coding-jobs?page=1&pageSize=20')
    expect(request).toHaveBeenNthCalledWith(2, '/api/v1/governance/approvals/approval%2F1/decision', expect.objectContaining({ method: 'POST' }))
    expect(request).toHaveBeenNthCalledWith(3, '/api/v1/projects/project%2F1/runs/run%2F1/recovery-packages', expect.objectContaining({ method: 'POST' }))
  })

  it('maps reviewed whole-plan execution and durable execution reads', async () => {
    const request = vi.fn(async () => ({})) as unknown as AuthenticatedRequest
    await executeProjectPlan(request, 'project/1', 'root/1', 'plan/1', { projectDirectoryId: 'directory/1', conversationId: 'conversation/1', sourceRepositoryId: 'source/1', agentId: 'agent/1', reviewerAgentId: 'agent/2', baseRef: 'main' })
    await listProjectPlanExecutions(request, 'project/1', 'plan/1')
    expect(request).toHaveBeenNthCalledWith(1, '/api/v1/projects/project%2F1/task-plans/plan%2F1/executions', expect.objectContaining({
      method: 'POST', headers: { 'Idempotency-Key': expect.any(String) },
      body: expect.stringContaining('"rootTaskId":"root/1"'),
    }))
    expect(request).toHaveBeenNthCalledWith(2, '/api/v1/projects/project%2F1/task-plans/plan%2F1/executions?page=1&pageSize=20')
  })

  it('maps revision-fenced execution control and safe control projection routes', async () => {
    const request = vi.fn(async () => ({})) as unknown as AuthenticatedRequest
    await getProjectPlanExecutionControl(request, 'project/1', 'plan/1', 'execution/1')
    await controlProjectPlanExecution(request, 'project/1', 'plan/1', 'execution/1', 'pause', 2, 'Review requested')
    await controlProjectPlanExecution(request, 'project/1', 'plan/1', 'execution/1', 'resume', 3)
    await controlProjectPlanExecution(request, 'project/1', 'plan/1', 'execution/1', 'cancel', 4, 'Stop requested')
    const base = '/api/v1/projects/project%2F1/task-plans/plan%2F1/executions/execution%2F1'
    expect(request).toHaveBeenNthCalledWith(1, `${base}/control`)
    expect(request).toHaveBeenNthCalledWith(2, `${base}/pause`, { method: 'POST', body: JSON.stringify({ expectedRevision: 2, reason: 'Review requested' }) })
    expect(request).toHaveBeenNthCalledWith(3, `${base}/resume`, { method: 'POST', body: JSON.stringify({ expectedRevision: 3 }) })
    expect(request).toHaveBeenNthCalledWith(4, `${base}/cancel`, { method: 'POST', body: JSON.stringify({ expectedRevision: 4, reason: 'Stop requested' }) })
  })

  it('reads and defines server-derived PlanStep assignment evidence', async () => {
    const request = vi.fn(async () => ({})) as unknown as AuthenticatedRequest
    const input = { agentId: 'agent/1', reviewerAgentId: 'agent/2', modelPoolId: 'pool/1' }
    await getProjectPlanStepAssignment(request, 'project/1', 'plan/1', 'step/1')
    await defineProjectPlanStepAssignment(request, 'project/1', 'plan/1', 'step/1', 'override', input)
    const base = '/api/v1/projects/project%2F1/task-plans/plan%2F1/steps/step%2F1/assignment'
    expect(request).toHaveBeenNthCalledWith(1, base)
    expect(request).toHaveBeenNthCalledWith(2, `${base}/override`, {
      method: 'POST', body: JSON.stringify(input),
    })
    expect(JSON.parse(String((request as unknown as ReturnType<typeof vi.fn>).mock.calls[1][1].body)))
      .not.toHaveProperty('capabilityHash')
  })

  it('creates a directory-scoped cross-Agent handoff with an idempotency key', async () => {
    const request = vi.fn(async () => ({})) as unknown as AuthenticatedRequest
    const input = {
      targetConversationId: 'conversation/2',
      targetAgentId: 'agent/2',
      reviewerAgentId: 'agent/1',
    }
    await createProjectRunHandoff(request, 'project/1', {
      taskPlanId: 'plan/1', planStepId: 'step/1', id: 'job/1',
    }, input, 'handoff-key-1')
    expect(request).toHaveBeenCalledWith(
      '/api/v1/projects/project%2F1/task-plans/plan%2F1/steps/step%2F1/coding-jobs/job%2F1/handoffs',
      { method: 'POST', headers: { 'Idempotency-Key': 'handoff-key-1' }, body: JSON.stringify(input) },
    )
  })

  it('offers only active GitHub MCP connections for source import', async () => {
    const request = vi.fn(async <T,>(path: string): Promise<T> => {
      if (path.endsWith('/catalog')) return [{ id: 'entry-github', slug: 'github' }, { id: 'entry-other', slug: 'other' }] as T
      if (path.endsWith('/installations')) return [
        { id: 'install-github', entryId: 'entry-github', displayName: 'GitHub official', state: 'INSTALLED' },
        { id: 'install-disabled', entryId: 'entry-github', displayName: 'Disabled', state: 'DISABLED' },
      ] as T
      if (path.endsWith('/connections')) return [
        { id: 'connection-active', installationId: 'install-github', state: 'ACTIVE', externalAccountName: 'octo' },
        { id: 'connection-pending', installationId: 'install-github', state: 'PENDING_AUTH', externalAccountName: null },
        { id: 'connection-disabled', installationId: 'install-disabled', state: 'ACTIVE', externalAccountName: null },
      ] as T
      throw new Error(path)
    }) as AuthenticatedRequest & ReturnType<typeof vi.fn>
    await expect(loadGithubConnectionOptions(request)).resolves.toEqual([
      { id: 'connection-active', name: 'GitHub official', accountName: 'octo' },
    ])
  })
})
