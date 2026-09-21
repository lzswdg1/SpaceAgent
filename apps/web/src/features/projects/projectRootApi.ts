import type { AuthenticatedRequest } from '../chat/types'
export const createGithubProjectRoot=(request:AuthenticatedRequest,input:{name:string;connectionId:string;githubUrl:string},idempotencyKey:string)=>
  request<ProjectDirectory>('/api/v1/project-roots/github',{method:'POST',headers:{'Idempotency-Key':idempotencyKey},body:JSON.stringify(input)})
import type { ProjectDirectory } from './types'
export const listProjectRoots = (request: AuthenticatedRequest) => request<ProjectDirectory[]>('/api/v1/project-roots')
export const createProjectRoot = (request: AuthenticatedRequest, input: { projectId: string | null; sourceRepositoryId: string | null; name: string }) => request<ProjectDirectory>('/api/v1/project-roots', {method:'POST',body:JSON.stringify(input)})
export const prepareProjectRoot = (request: AuthenticatedRequest, rootId: string) => request<ProjectDirectory>(`/api/v1/project-roots/${encodeURIComponent(rootId)}/prepare`,{method:'POST'})
export const deleteProjectRoot = (request: AuthenticatedRequest, rootId: string) => request<ProjectDirectory>(`/api/v1/project-roots/${encodeURIComponent(rootId)}`,{method:'DELETE'})
export const renameProjectRoot = (request: AuthenticatedRequest, rootId: string, name: string) => request<ProjectDirectory>(`/api/v1/project-roots/${encodeURIComponent(rootId)}`,{method:'PATCH',body:JSON.stringify({name})})
