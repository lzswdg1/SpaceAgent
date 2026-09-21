export type ApiEnvelope<T> = {
  success: boolean
  data: T
  message: string
  code?: string
}

export class ApiError extends Error {
  readonly status: number
  readonly code?: string

  constructor(message: string, status: number, code?: string) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.code = code
  }
}

export type Fetcher = (input: RequestInfo | URL, init?: RequestInit) => Promise<Response>

const parseBody = async (response: Response): Promise<unknown> => {
  const contentType = response.headers.get('content-type') ?? ''
  if (!contentType.includes('application/json')) return null
  return response.json()
}

export async function apiRequest<T>(
  fetcher: Fetcher,
  path: string,
  init: RequestInit = {},
  accessToken?: string,
): Promise<T> {
  let response: Response
  try {
    const headers = new Headers(init.headers)
    headers.set('Accept', 'application/json')
    if (init.body !== undefined && !headers.has('Content-Type')) headers.set('Content-Type', 'application/json')
    if (accessToken) headers.set('Authorization', `Bearer ${accessToken}`)
    response = await fetcher(path, { ...init, headers })
  } catch (error) {
    throw new ApiError(error instanceof Error ? error.message : 'Network request failed', 0, 'NETWORK_ERROR')
  }

  const body = await parseBody(response) as Partial<ApiEnvelope<T>> | null
  if (!response.ok || body?.success === false) {
    const fallback = response.status === 401 ? 'Authentication is required' : `Request failed (${response.status})`
    throw new ApiError(body?.message || fallback, response.status, body?.code)
  }
  if (!body || body.success !== true) throw new ApiError('Invalid API response', response.status, 'INVALID_RESPONSE')
  return body.data as T
}
