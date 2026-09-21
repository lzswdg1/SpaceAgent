export const canAuthorizeOrganizationDeletion = (status:string) => status === 'ACTIVE' || status === 'DELETING'
