import type { AuthenticatedRequest } from '../chat/types'
import { repositoryBranchOptions } from './repositoryBranches'
export type RepositoryEnvironment = { workspaceId: string; sourceName: string; baseRef: string; branch: string; headCommit: string; state: string; status: string; patch: string; changedFiles: string[]; truncated: boolean }
export type RepositoryEntry = { path: string; directory: boolean; sizeBytes: number }
export type RepositoryFile = { path: string; content: string; sizeBytes: number; truncated: boolean }
const root = (projectId: string, workspaceId: string) => `/api/v1/projects/${encodeURIComponent(projectId)}/workspaces/${encodeURIComponent(workspaceId)}`
export const loadRepositoryEnvironment = (request: AuthenticatedRequest, projectId: string, workspaceId: string) => request<RepositoryEnvironment>(`${root(projectId, workspaceId)}/environment`)
export const loadRepositoryBranches = async (request: AuthenticatedRequest, projectId: string, sourceId: string) => repositoryBranchOptions(await request<string[]>(`/api/v1/projects/${encodeURIComponent(projectId)}/sources/${encodeURIComponent(sourceId)}/branches`))
export const loadRepositoryFiles = (request: AuthenticatedRequest, projectId: string, workspaceId: string, path: string) => request<{ root: string; entries: RepositoryEntry[]; truncated: boolean }>(`${root(projectId, workspaceId)}/files?path=${encodeURIComponent(path)}`)
export const loadRepositoryFile = (request: AuthenticatedRequest, projectId: string, workspaceId: string, path: string) => request<RepositoryFile>(`${root(projectId, workspaceId)}/file?path=${encodeURIComponent(path)}`)
