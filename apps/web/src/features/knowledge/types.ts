export type KnowledgeDocument = {
  id: string
  ownerId: string
  name: string
  contentType: string
  storageLocation: string
  status: string
  errorReason: string | null
  createdAt: string
  updatedAt: string
}

export type KnowledgeChunk = {
  id: string
  documentId: string
  sequence: number
  content: string
  embeddingReference: string | null
  contentHash: string | null
  embeddingModel: string | null
  embeddingDimensions: number
  createdAt: string
}

export type KnowledgeProcessing = {
  document: KnowledgeDocument
  chunks: KnowledgeChunk[]
}

export type KnowledgeUrlRefreshPolicy = {
  mode: 'MANUAL' | 'PERIODIC'
  intervalSeconds: number
  useConditionalRequests: boolean
  maximumRedirects: number
  maximumBytes: number
}

export type KnowledgeUrlJob = {
  id: string
  knowledgeDocumentId: string
  normalizedUrl: string
  origin: string
  refreshPolicy: KnowledgeUrlRefreshPolicy
  state: 'DRAFT' | 'ACTIVE' | 'PAUSED' | 'ARCHIVED'
  revision: number
  nextRefreshAt: string | null
  createdAt: string
  updatedAt: string
  archivedAt: string | null
}

export type KnowledgeUrlJobPage = {
  items: KnowledgeUrlJob[]
  page: number
  pageSize: number
  total: number
}

export type RetrievalMatch = {
  documentId: string
  chunkId: string
  sequence: number
  content: string
  score: number
  embeddingModel: string
}

export type RetrievalResult = {
  query: string
  matches: RetrievalMatch[]
}
