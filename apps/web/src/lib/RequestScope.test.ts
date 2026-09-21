import {describe,it,expect} from 'vitest'
import {RequestScope} from './RequestScope'
describe('response scope',()=>{
  it('rejects results after switching away and back or editing a newer draft',()=>{
    const scope=new RequestScope(),old=scope.capture()
    scope.advance();scope.advance()
    expect(old()).toBe(false)
    const current=scope.capture();expect(current()).toBe(true)
    scope.advance();expect(current()).toBe(false)
  })
})
