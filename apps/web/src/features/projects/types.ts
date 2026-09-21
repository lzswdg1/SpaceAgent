import type { Conversation } from '../chat/types'

export type Project = {
  id: string
  tenantId: string
  ownerId: string
  name: string
  description: string | null
  status: string
  createdAt: string
  updatedAt: string
}

export type Task = {
  id: string
  projectId: string
  parentTaskId: string | null
  title: string
  goal: string
  description: string | null
  constraints: string[]
  acceptanceCriteria: string[]
  currentTaskPlanId: string | null
  state: string
  createdAt: string
  updatedAt: string
}

export type Workspace = {
  id: string
  projectId: string
  projectDirectoryId: string
  taskId: string
  sourceRepositoryId: string
  bridgeId: string | null
  isolationKey: string
  mode: string
  worktreeKey: string
  baseRef: string
  branchName?: string | null
  worktreeRef: string | null
  headCommit: string | null
  writable: boolean
  state: string
  failureReason: string | null
  revision: number
  createdAt: string
  updatedAt: string
}

export type SourceRepository = {
  id: string
  projectId: string
  mcpConnectionId: string | null
  mcpInvocationId: string | null
  workspaceBridgeId: string | null
  providerRepositoryId: string | null
  displayName: string
  remoteUrl: string | null
  localRootHandle: string | null
  defaultBranch: string | null
  type: 'LOCAL' | 'GIT' | 'GITHUB' | 'GITLAB' | 'GENERIC'
  state: 'PROVISIONING' | 'READY' | 'DIRTY' | 'FAILED' | 'ARCHIVED'
  visibility: 'PUBLIC' | 'PRIVATE' | 'INTERNAL' | 'LOCAL'
  createdBy: string
  createdAt: string
  updatedAt: string
}

export type GithubMcpRepository = {
  providerRepositoryId: string
  owner: string
  name: string
  htmlUrl: string
  cloneUrl: string
  defaultBranch: string
  privateRepository: boolean
  archived: boolean
}

export type GithubConnectionOption = {
  id: string
  name: string
  accountName: string | null
}

export type ProjectConversation = Conversation & { title: string }

export type ProjectDirectory = {
  rootKind?: 'EMPTY' | 'REPOSITORY'
  storageState?: string
  storageRef?: string | null
  id: string
  tenantId: string
  projectId: string
  sourceRepositoryId: string | null
  name: string
  relativePath: string
  defaultDirectory: boolean
  state: 'ACTIVE' | 'ARCHIVED'
  createdAt: string
  updatedAt: string
}

export type ProjectPlanStepAssignment = {
  id: string
  taskPlanId: string
  planStepId: string
  revision: number
  source: 'PLAN_DEFAULT' | 'STEP_OVERRIDE' | 'HANDOFF'
  agentId: string
  primaryConfigurationHash: string
  reviewerAgentId: string
  reviewerConfigurationHash: string
  modelPoolId: string | null
  capabilityHash: string
  configurationHash: string
  assignmentHash: string
  assignedAt: string
}

export type ProjectIntakeJob = {
  id: string
  projectId: string
  projectDirectoryId: string
  sourceRepositoryId: string
  conversationId: string
  agentId: string
  goal: string
  state: 'PENDING' | 'RUNNING' | 'PROPOSED' | 'CONFIRMED' | 'REJECTED' | 'FAILED' | 'BLOCKED'
  attempt: number
  proposalHash: string | null
  proposal: null | {
    blueprint: { goal: string; modules: string[]; risks: string[]; acceptanceCriteria: string[] }
    rootTask: { title: string; goal: string; description: string }
    childTasks: Array<{ key: string; title: string; goal: string }>
    steps: Array<{ stepKey: string; childTaskKey: string; requiredCapability: string | null; expectedOutput: string; approvalRequired: boolean }>
  }
  agentRunId: string | null
  blueprintId: string | null
  rootTaskId: string | null
  taskPlanId: string | null
  safeErrorCode: string | null
  reviewReason: string | null
  revision: number
  createdAt: string
  updatedAt: string
}

export type ProjectCodingJob = {
  id: string
  projectId: string
  projectDirectoryId: string
  conversationId: string
  sourceRepositoryId: string
  rootTaskId: string
  taskId: string
  taskPlanId: string
  planStepId: string
  agentId: string
  primaryConfigurationHash: string
  reviewerConfigurationHash: string
  reviewerAgentId: string | null
  baseRef: string
  state: 'PENDING' | 'RUNNING' | 'WAITING_APPROVAL' | 'HANDED_OFF' | 'COMPLETED' | 'FAILED' | 'BLOCKED'
  workspaceId: string | null
  codingRunId: string | null
  reviewerRunId: string | null
  iteration: number
  reviewRound: number
  pendingToolName: string | null
  pendingApprovalId: string | null
  safeErrorCode: string | null
  revision: number
  createdAt: string
  updatedAt: string
}

export type ProjectPlanExecution = {
  id: string
  projectId: string
  projectDirectoryId: string
  conversationId: string
  sourceRepositoryId: string
  rootTaskId: string
  taskPlanId: string
  agentId: string
  primaryConfigurationHash: string
  reviewerAgentId: string
  baseRef: string
  state: 'READY' | 'RUNNING' | 'PAUSING' | 'PAUSED' | 'CANCELLING' | 'CANCELLED' | 'COMPLETED' | 'FAILED' | 'BLOCKED'
  safeErrorCode: string | null
  attempt: number
  revision: number
  createdAt: string
  startedAt: string | null
  updatedAt: string
  completedAt: string | null
  activeJobs: Array<{ id: string; planStepId: string; state: ProjectCodingJob['state']; workspaceId: string | null; codingRunId: string | null; reviewerRunId: string | null; revision: number; updatedAt: string }>
}

export type ProjectPlanExecutionControl = {
  executionId: string
  state: ProjectPlanExecution['state']
  desiredState: 'RUNNING' | 'PAUSED' | 'CANCELLED'
  safeErrorCode: string | null
  controlReasonPresent: boolean
  revision: number
  activeJobCount: number
  updatedAt: string
  completedAt: string | null
}

export type ProjectRunHandoff = {
  id: string
  projectId: string
  projectDirectoryId: string
  sourceCodingJobId: string
  sourceAgentRunId: string
  targetConversationId: string
  targetAgentId: string
  reviewerAgentId: string
  targetCodingJobId: string
  state: 'PENDING' | 'ACTIVE' | 'READY_TO_FINALIZE' | 'FINALIZING' | 'COMPLETED' | 'BLOCKED'
  memoryKey: string | null
  safeErrorCode: string | null
  revision: number
  createdAt: string
  updatedAt: string
}

export type ProjectRunHandoffResult = {
  handoff: ProjectRunHandoff
  targetCodingJob: ProjectCodingJob
  recoveryPackage: RecoveryPackage
}

export type RecoveryPackage = {
  snapshotId: string
  snapshotSha256: string
  capturedAt: string
  context: {
    project: { projectDirectoryId: string; directoryName: string; directoryRelativePath: string }
    runtime: { agentRunId: string; state: string; executionPhase: string; revision: number }
    task: { rootTitle: string; title: string; planStepState: string; expectedOutput: string }
    nextAction: string
    blockers: string[]
  }
}

export type GovernanceApproval = {
  id: string
  actionType: string
  resourceType: string
  resourceId: string
  summary: string
  state: string
  expiresAt: string
  revision: number
}

export type PlanStep = {
  id: string
  stepKey: string
  sequence: number
  childTaskId: string
  dependencyStepIds: string[]
  requiredCapability: string | null
  preferredAgentId: string | null
  expectedOutput: string
  acceptanceCriteria: string[]
  approvalRequired: boolean
  state: string
  createdAt: string
  updatedAt: string
}

export type TaskPlan = {
  id: string
  projectId: string
  rootTaskId: string
  versionNumber: number
  status: 'DRAFT' | 'PROPOSED' | 'APPROVED' | 'ACTIVE' | 'COMPLETED' | 'CANCELLED'
  generatedByAgentId: string | null
  generatedByRunConfigurationSnapshotId: string | null
  createdBy: string
  approvedBy: string | null
  approvedAt: string | null
  steps: PlanStep[]
  createdAt: string
  updatedAt: string
}

export type TaskPlanDraftStep = {
  stepKey: string
  childTaskId: string
  dependsOnStepKeys: string[]
  requiredCapability: string | null
  preferredAgentId: string | null
  expectedOutput: string
  acceptanceCriteria: string[]
  approvalRequired: boolean
}
