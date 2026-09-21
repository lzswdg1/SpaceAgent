export type DeletionBlocker = {
  code: string
  resourceType: string
  count: number
}

export const deletionBlockers = (result: Record<string, unknown> | null | undefined): DeletionBlocker[] => {
  const value = result?.blockers
  if (!Array.isArray(value)) return []
  return value.flatMap((item) => {
    if (!item || typeof item !== 'object') return []
    const blocker = item as Record<string, unknown>
    return [{
      code: typeof blocker.code === 'string' ? blocker.code : 'BLOCKED',
      resourceType: typeof blocker.resourceType === 'string' ? blocker.resourceType : 'RESOURCE',
      count: typeof blocker.count === 'number' && Number.isFinite(blocker.count) ? blocker.count : 0,
    }]
  })
}

export const shortEvidenceId = (value: string | null | undefined, fallback = '—') =>
  value ? value.slice(0, 8) : fallback

export const displayCount = (value: number | null | undefined) =>
  typeof value === 'number' && Number.isFinite(value) && value >= 0 ? value.toLocaleString() : '—'

export const cleanupStepTone = (state: string) => {
  const normalized = state.toLowerCase()
  return normalized === 'completed' || normalized === 'succeeded' ? 'completed'
    : normalized === 'blocked' || normalized === 'failed' ? 'blocked' : 'pending'
}

export const isDeletionEligible = (command: {
  operation: string
  state: string
  result: Record<string, unknown>
} | null | undefined) => command?.operation === 'USER_DELETION_PREFLIGHT'
  && command.state === 'SUCCEEDED'
  && command.result.eligible === true
