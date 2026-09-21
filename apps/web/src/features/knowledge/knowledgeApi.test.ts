import { describe, expect, it, vi } from 'vitest'
import type { AuthenticatedRequest } from '../overview/types'
import { controlKnowledgeUrlJob, createKnowledgeReference, createKnowledgeUrlJob, getKnowledgeUrlJob, listKnowledgeChunks, listKnowledgeUrlJobs, processKnowledgeDocument, retrieveKnowledge } from './knowledgeApi'

describe('knowledge API', () => {
  it('uses owner-scoped public document and retrieval routes', async () => {
    const request = vi.fn(async () => ({})) as unknown as AuthenticatedRequest
    await createKnowledgeReference(request, { name: 'rules.md', contentType: 'text/markdown', storageReference: 'browser-file:rules.md' })
    await processKnowledgeDocument(request, 'doc/1', 'verified content')
    await listKnowledgeChunks(request, 'doc/1')
    await retrieveKnowledge(request, { documentIds: ['doc/1'], query: 'release', topK: 4 })
    expect(request).toHaveBeenNthCalledWith(1, '/api/v1/knowledge/documents/upload-reference', expect.objectContaining({ method: 'POST' }))
    expect(request).toHaveBeenNthCalledWith(2, '/api/v1/knowledge/documents/doc%2F1/process', expect.objectContaining({ method: 'POST' }))
    expect(request).toHaveBeenNthCalledWith(3, '/api/v1/knowledge/documents/doc%2F1/chunks')
    expect(request).toHaveBeenNthCalledWith(4, '/api/v1/knowledge/retrieve', expect.objectContaining({ method: 'POST' }))
  })

  it('maps bounded URL ingestion and revision-fenced lifecycle routes', async () => {
    const request = vi.fn(async () => ({})) as unknown as AuthenticatedRequest
    const refreshPolicy = {
      mode: 'PERIODIC' as const,
      intervalSeconds: 900,
      useConditionalRequests: true,
      maximumRedirects: 3,
      maximumBytes: 2_000_000,
    }
    await createKnowledgeUrlJob(request, {
      knowledgeDocumentId: 'document/1',
      url: 'https://docs.example.com/guide',
      refreshPolicy,
      idempotencyKey: 'url-job-request-1',
    })
    await listKnowledgeUrlJobs(request, 'document/1', 1, 20)
    await getKnowledgeUrlJob(request, 'job/1')
    await controlKnowledgeUrlJob(request, 'job/1', 'pause', 3)
    expect(request).toHaveBeenNthCalledWith(1, '/api/v1/knowledge/url-jobs', {
      method: 'POST',
      body: JSON.stringify({ knowledgeDocumentId: 'document/1', url: 'https://docs.example.com/guide', refreshPolicy, idempotencyKey: 'url-job-request-1' }),
    })
    expect(request).toHaveBeenNthCalledWith(2, '/api/v1/knowledge/documents/document%2F1/url-jobs?page=1&pageSize=20')
    expect(request).toHaveBeenNthCalledWith(3, '/api/v1/knowledge/url-jobs/job%2F1')
    expect(request).toHaveBeenNthCalledWith(4, '/api/v1/knowledge/url-jobs/job%2F1/pause', {
      method: 'POST', body: JSON.stringify({ expectedRevision: 3 }),
    })
  })
})
