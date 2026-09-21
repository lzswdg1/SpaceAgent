/** Defense against stale server responses and previously selected internal execution refs. */
export function visibleRepositoryBranch(value: string | null | undefined): value is string {
  if (!value?.trim()) return false
  const branch = value.trim().replace(/^refs\/heads\//, '').replace(/^(refs\/remotes\/)?origin\//, '')
  return branch !== 'spaceagent' && !branch.startsWith('spaceagent/')
    && branch !== 'spaceagent-intake' && !branch.startsWith('spaceagent-intake/')
}

export const repositoryBranchOptions = (branches: string[], current?: string | null) =>
  [...new Set([...(current ? [current] : []), ...branches].filter(visibleRepositoryBranch))]

export const repositoryBranchLabel = (branch?: string | null) => visibleRepositoryBranch(branch) ? branch : '—'
