import { describe, expect, it, vi } from 'vitest'
import { createSkill, createSkillContent, currentSkillVersion, getSkill, listSkills, publishSkill, replaceSkillBinding, validSkillText, type Skill } from './skillApi'
import type { AuthenticatedRequest } from '../overview/types'

describe('tenant Skill registry', () => {
  it('uses real registry routes and exact draft publication identity', async () => {
    const request = vi.fn(async () => ({})) as unknown as AuthenticatedRequest
    const input = { name: 'Evidence', description: '', instructions: 'Cite sources', requiredToolIds: ['http_fetch'] }
    await listSkills(request); await createSkill(request, input); await getSkill(request, 's/1')
    await createSkillContent(request, 's/1', input); await publishSkill(request, 's/1', 'v/2')
    expect(request).toHaveBeenNthCalledWith(1, '/api/v1/skills')
    expect(request).toHaveBeenNthCalledWith(3, '/api/v1/skills/s%2F1')
    expect(request).toHaveBeenNthCalledWith(4, '/api/v1/skills/s%2F1/versions', { method: 'POST', body: JSON.stringify({instructions: input.instructions, requiredToolIds: input.requiredToolIds}) })
    expect(request).toHaveBeenNthCalledWith(5, '/api/v1/skills/s%2F1/versions/v%2F2/publish', {method:'POST'})
  })
  it('pins only current published content and upgrades one Skill without removing others', () => {
    const skill = { currentVersionId:'v2', versions:[{id:'v1',status:'DEPRECATED'},{id:'v2',status:'PUBLISHED'}] } as Skill
    expect(currentSkillVersion(skill)?.id).toBe('v2')
    expect(replaceSkillBinding(['other','v1'], skill, 'v2')).toEqual(['other','v2'])
    expect(currentSkillVersion({...skill,currentVersionId:'v1'})).toBeUndefined()
  })
  it('bounds UTF-8 bytes rather than JavaScript character count', () => {
    expect(validSkillText(' ')).toBe(false)
    expect(validSkillText('a'.repeat(131072))).toBe(true)
    expect(validSkillText('中'.repeat(50000))).toBe(false)
  })
})
