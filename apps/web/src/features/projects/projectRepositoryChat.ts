import { getConversation } from '../chat/chatApi'
import type { AuthenticatedRequest, Conversation, ConversationSummary, PageResponse } from '../chat/types'
import type { AgentDefinition, AgentSaveResult, RuntimeCapabilityCatalog } from '../agents/types'
import { createProjectConversation, createProjectDirectory, createProjectTask, loadProjectContext,
  provisionProjectWorkspace, setProjectConversationActiveTask } from './projectApi'
import type { ProjectDirectory, Workspace } from './types'

export function resolveWorkbenchSourceId(
  selectedId: string | null,
  directorySourceId: string | null | undefined,
  workspaceSourceId: string | null | undefined,
  sources: Array<{ id: string }>,
): string | null {
  for (const id of [selectedId, directorySourceId, workspaceSourceId]) {
    if (id && sources.some((source) => source.id === id)) return id
  }
  return sources.length === 1 ? sources[0].id : null
}

export const needsRepositoryPreparation = (
  directory: Pick<ProjectDirectory, 'sourceRepositoryId'> | null,
  hasReadySources: boolean,
  workspace: Workspace | null,
) => !workspace && (Boolean(directory?.sourceRepositoryId) || hasReadySources)

export const repositoryChatWorkspace = (workspaces: Workspace[], directoryId: string | null,
  taskId: string | null | undefined): Workspace | null => {
  const matches = workspaces.filter((workspace) => workspace.projectDirectoryId === directoryId
    && workspace.taskId === taskId && workspace.state === 'READY' && workspace.mode === 'MANAGED_GIT')
  return matches.length === 1 ? matches[0] : null
}

/** Called only after the preparation form explicitly authorizes enabling repository read tools. */
export async function prepareRepositoryChat(request: AuthenticatedRequest,
  input: { projectId: string; sourceId: string; agentId: string; conversationId: string | null; baseRef?: string },
  tx: (key: string) => string) {
  const [context, capabilities, agent] = await Promise.all([
    loadProjectContext(request, input.projectId),
    request<RuntimeCapabilityCatalog>('/api/v1/tooling/capabilities'),
    request<AgentDefinition>(`/api/v1/agents/${encodeURIComponent(input.agentId)}`),
  ])
  const tools = ['file_list', 'file_read']
  if (tools.some((id) => !capabilities.tools.some((tool) => tool.id === id && tool.available))) {
    throw new Error(tx('repositorySandboxUnavailable'))
  }
  const source = context.sources.find((item) => item.id === input.sourceId && item.state === 'READY')
  if (!source || source.type === 'LOCAL') throw new Error(tx('repositorySourceRequired'))
  const baseRef = input.baseRef?.trim() || source.defaultBranch || 'main'
  if (tools.some((id) => !agent.enabledToolIds.includes(id))) {
    const enabledToolIds = [...new Set([...agent.enabledToolIds.filter((id) => id !== 'knowledge_search'
      || (agent.ragEnabled && agent.knowledgeBaseIds.length > 0)), ...tools])]
    const result = await request<AgentDefinition | AgentSaveResult>(`/api/v1/agents/${encodeURIComponent(agent.id)}`, {
      method: 'PATCH', body: JSON.stringify({ enabledToolIds }),
    })
    if ('outcome' in result && result.outcome === 'PENDING_APPROVAL') throw new Error(tx('repositoryToolApprovalRequired'))
  }
  const directory: ProjectDirectory = context.directories.find((item) => item.sourceRepositoryId === source.id
    && item.relativePath === '.' && item.state === 'ACTIVE') ?? await createProjectDirectory(request, input.projectId, {
    sourceRepositoryId: source.id, name: source.displayName, relativePath: '.',
  })
  let conversation: Conversation | null = input.conversationId ? await getConversation(request, input.conversationId) : null
  if (!conversation || conversation.projectId !== input.projectId || conversation.projectDirectoryId !== directory.id
      || conversation.agentId !== input.agentId || conversation.status !== 'ACTIVE') {
    const page = await request<PageResponse<ConversationSummary>>(`/api/v1/projects/${encodeURIComponent(input.projectId)}/directories/${encodeURIComponent(directory.id)}/conversations?page=1&size=100`)
    const reusable = page.items.find((item) => item.agentId === input.agentId && item.status === 'ACTIVE')
    conversation = reusable ? await getConversation(request, reusable.id) : await createProjectConversation(request, { projectId: input.projectId,
      projectDirectoryId: directory.id, agentId: input.agentId, name: tx('newProjectConversation') })
  }
  let task = context.tasks.find((item) => item.id === conversation?.activeTaskId
    && !['COMPLETED', 'FAILED', 'CANCELLED'].includes(item.state)
    && (!input.baseRef || context.workspaces.some((workspace) => workspace.taskId === item.id && workspace.baseRef === baseRef)))
  if (!task) {
    task = context.tasks.find((item) => item.description === `Repository conversation ${conversation?.conversationId} branch ${baseRef}`
      && !['COMPLETED', 'FAILED', 'CANCELLED'].includes(item.state))
      ?? await createProjectTask(request, input.projectId, { title: `${tx('repositoryReadTask')}: ${source.displayName}`.slice(0, 200),
      goal: tx('repositoryReadTask'), description: `Repository conversation ${conversation.conversationId} branch ${baseRef}` })
    conversation = await setProjectConversationActiveTask(request, conversation.conversationId, task.id)
  }
  const existing = context.workspaces.filter((item) => item.projectDirectoryId === directory.id
    && item.taskId === task.id && !['ARCHIVED', 'CLEANED_UP', 'FAILED'].includes(item.state))
  let workspace = repositoryChatWorkspace(existing, directory.id, task.id)
  if (!workspace && existing.length) throw new Error(tx('repositoryWorkspacePending'))
  if (!workspace) workspace = await provisionProjectWorkspace(request, input.projectId, {
    projectDirectoryId: directory.id, sourceRepositoryId: source.id, taskId: task.id, baseRef,
  })
  if (workspace.state !== 'READY') throw new Error(tx('repositoryWorkspacePending'))
  return { directory, conversation, workspace, task }
}
