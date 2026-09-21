import type { AuthenticatedRequest } from '../overview/types'
import type { KnowledgeChunk, KnowledgeDocument, KnowledgeProcessing, KnowledgeUrlJob, KnowledgeUrlJobPage, KnowledgeUrlRefreshPolicy, RetrievalResult } from './types'

export const listKnowledgeDocuments = (request: AuthenticatedRequest) => request<KnowledgeDocument[]>('/api/v1/knowledge/documents')

export const listKnowledgeChunks = (request: AuthenticatedRequest, documentId: string) => request<KnowledgeChunk[]>(
  `/api/v1/knowledge/documents/${encodeURIComponent(documentId)}/chunks`,
)

export const createKnowledgeReference = (
  request: AuthenticatedRequest,
  input: { name: string; contentType: string; storageReference: string },
) => request<KnowledgeDocument>('/api/v1/knowledge/documents/upload-reference', {
  method: 'POST',
  body: JSON.stringify(input),
})

export const processKnowledgeDocument = (
  request: AuthenticatedRequest,
  documentId: string,
  content?: string,
) => request<KnowledgeProcessing>(`/api/v1/knowledge/documents/${encodeURIComponent(documentId)}/process`, {
  method: 'POST',
  body: JSON.stringify(content ? { content } : {}),
})

export const retrieveKnowledge = (
  request: AuthenticatedRequest,
  input: { documentIds: string[]; query: string; topK: number },
) => request<RetrievalResult>('/api/v1/knowledge/retrieve', {
  method: 'POST',
  body: JSON.stringify(input),
})

export const deleteKnowledgeDocument = (request: AuthenticatedRequest, documentId: string) => request<void>(
  `/api/v1/knowledge/documents/${encodeURIComponent(documentId)}`,
  { method: 'DELETE' },
)

export const createKnowledgeUrlJob = (
  request: AuthenticatedRequest,
  input: { knowledgeDocumentId: string; url: string; refreshPolicy: KnowledgeUrlRefreshPolicy; idempotencyKey: string },
) => request<KnowledgeUrlJob>('/api/v1/knowledge/url-jobs', {
  method: 'POST',
  body: JSON.stringify(input),
})

export const getKnowledgeUrlJob = (request: AuthenticatedRequest, jobId: string) => request<KnowledgeUrlJob>(
  `/api/v1/knowledge/url-jobs/${encodeURIComponent(jobId)}`,
)

export const listKnowledgeUrlJobs = (
  request: AuthenticatedRequest,
  documentId: string,
  page = 1,
  pageSize = 20,
) => request<KnowledgeUrlJobPage>(
  `/api/v1/knowledge/documents/${encodeURIComponent(documentId)}/url-jobs?page=${page}&pageSize=${pageSize}`,
)

export type KnowledgeUrlLifecycleAction = 'pause' | 'resume' | 'archive'

export const controlKnowledgeUrlJob = (
  request: AuthenticatedRequest,
  jobId: string,
  action: KnowledgeUrlLifecycleAction,
  expectedRevision: number,
) => request<KnowledgeUrlJob>(
  `/api/v1/knowledge/url-jobs/${encodeURIComponent(jobId)}/${action}`,
  { method: 'POST', body: JSON.stringify({ expectedRevision }) },
)
