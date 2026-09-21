export type MemoryScope = 'USER' | 'PROJECT' | 'TASK'
export type MemoryKind = 'FACT' | 'PREFERENCE' | 'CONSTRAINT' | 'DECISION' | 'ARCHITECTURE' | 'PROCEDURE' | 'FAILURE' | 'EXPERIENCE'

export type MemoryScopeRef = { scope: MemoryScope; scopeId: string }

export type MemoryCandidate = {
  id: string
  scope: MemoryScopeRef
  kind: MemoryKind
  sourceId: string
  sourceType: string
  content: string
  confidence: number
  dedupeKey: string
  state: 'PENDING' | 'ACCEPTED' | 'REJECTED' | 'CONSOLIDATED' | 'EXPIRED'
  createdAt: string
}

export type ScopedMemory = {
  id: string
  scope: MemoryScopeRef
  kind: MemoryKind
  key: string
  value: string
  createdAt: string
  updatedAt: string
}

export type MemoryConsolidation = {
  taskId: string
  accepted: number
  rejected: number
  consolidatedTaskMemories: number
  promotedToProject: number
  promotedToUser: number
  consolidatedMemories: ScopedMemory[]
}
