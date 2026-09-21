import type { AuthenticatedRequest } from '../overview/types'

export type SkillVersion = { id: string; skillId: string; versionNumber: number; status: string;
  instructions: string; requiredToolIds: string[]; configHash: string }
export type Skill = { id: string; name: string; description: string | null; ownerUserId: string;
  lifecycle: string; currentVersionId: string | null; versions: SkillVersion[] }
export type SkillInput = { name: string; description: string; instructions: string; requiredToolIds: string[] }
export const listSkills = (request: AuthenticatedRequest) => request<Skill[]>('/api/v1/skills')
export const getSkill = (request: AuthenticatedRequest, id: string) => request<Skill>(`/api/v1/skills/${encodeURIComponent(id)}`)
export const createSkill = (request: AuthenticatedRequest, input: SkillInput) => request<Skill>('/api/v1/skills', { method: 'POST', body: JSON.stringify(input) })
export const createSkillContent = (request: AuthenticatedRequest, id: string, input: SkillInput) =>
  request<SkillVersion>(`/api/v1/skills/${encodeURIComponent(id)}/versions`, { method: 'POST', body: JSON.stringify({ instructions: input.instructions, requiredToolIds: input.requiredToolIds }) })
export const publishSkill = (request: AuthenticatedRequest, id: string, versionId: string) =>
  request<Skill>(`/api/v1/skills/${encodeURIComponent(id)}/versions/${encodeURIComponent(versionId)}/publish`, { method: 'POST' })
export const currentSkillVersion = (skill: Skill) => skill.versions.find(v => v.id === skill.currentVersionId && v.status === 'PUBLISHED')
export const replaceSkillBinding = (selected: string[], skill: Skill, versionId: string) =>
  [...selected.filter(id => !skill.versions.some(v => v.id === id)), versionId]
export const validSkillText = (value: string) => value.trim().length > 0 && new TextEncoder().encode(value.trim()).length <= 131072
