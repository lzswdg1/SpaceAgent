import {describe,it,expect} from 'vitest'
import {canAuthorizeOrganizationDeletion} from './organizationActions'
describe('organization cleanup authorization',()=>{
  it('allows the administrator to authorize retained storage after ordinary leave, but not after deletion',()=>{
    expect(canAuthorizeOrganizationDeletion('DELETING')).toBe(true)
    expect(canAuthorizeOrganizationDeletion('ACTIVE')).toBe(true)
    expect(canAuthorizeOrganizationDeletion('DELETED')).toBe(false)
  })
})
