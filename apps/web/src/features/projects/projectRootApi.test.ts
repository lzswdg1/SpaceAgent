import { describe,expect,it,vi } from 'vitest'
import type { AuthenticatedRequest } from '../chat/types'
import { createProjectRoot,createGithubProjectRoot,deleteProjectRoot,prepareProjectRoot,renameProjectRoot } from './projectRootApi'
describe('logical project roots',()=>{
  it('starts a first GitHub repository without requiring an existing Project ID',async()=>{
    const request=vi.fn(async()=>({})) as unknown as AuthenticatedRequest
    await createGithubProjectRoot(request,{name:'repo',connectionId:'connection',githubUrl:'https://github.com/owner/repo'},'root-intent')
    expect(request).toHaveBeenCalledWith('/api/v1/project-roots/github',{
      method:'POST',headers:{'Idempotency-Key':'root-intent'},body:JSON.stringify({name:'repo',connectionId:'connection',githubUrl:'https://github.com/owner/repo'}),
    })
  })
  it('creates empty roots without a repository or host path and deletes only through the logical endpoint',async()=>{
    const request=vi.fn(async()=>({})) as unknown as AuthenticatedRequest
    await createProjectRoot(request,{name:'My folder',projectId:null,sourceRepositoryId:null})
    await prepareProjectRoot(request,'root/1')
    await renameProjectRoot(request,'root/1','Renamed')
    await deleteProjectRoot(request,'root/1')
    expect(request).toHaveBeenCalledWith('/api/v1/project-roots',{method:'POST',body:JSON.stringify({name:'My folder',projectId:null,sourceRepositoryId:null})})
    expect(request).toHaveBeenLastCalledWith('/api/v1/project-roots/root%2F1',{method:'DELETE'})
  })
})
