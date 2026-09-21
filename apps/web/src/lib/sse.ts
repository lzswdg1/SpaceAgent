export type SseEvent = {
  event: string
  data: unknown
}

const decodeEvent = (frame: string): SseEvent | null => {
  let event = 'message'
  const data: string[] = []
  for (const line of frame.split(/\r?\n/)) {
    if (line.startsWith('event:')) event = line.slice(6).trim()
    if (line.startsWith('data:')) data.push(line.slice(5).trimStart())
  }
  if (!data.length) return null
  const raw = data.join('\n')
  try { return { event, data: JSON.parse(raw) } } catch { return { event, data: raw } }
}

export async function consumeSse(
  response: Response,
  onEvent: (event: SseEvent) => void,
  signal?: AbortSignal,
): Promise<void> {
  if (!response.body) throw new Error('Streaming response has no body')
  const reader = response.body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''
  try {
    while (true) {
      if (signal?.aborted) throw new DOMException('The operation was aborted', 'AbortError')
      const { value, done } = await reader.read()
      buffer = (buffer + decoder.decode(value, { stream: !done })).replace(/\r\n/g, '\n')
      if (buffer.length > 2_000_000) throw new Error('Streaming frame exceeds the supported size')
      let boundary = buffer.indexOf('\n\n')
      while (boundary >= 0) {
        const event = decodeEvent(buffer.slice(0, boundary))
        buffer = buffer.slice(boundary + 2)
        if (event) onEvent(event)
        boundary = buffer.indexOf('\n\n')
      }
      if (done) break
    }
    const event = decodeEvent(buffer)
    if (event) onEvent(event)
  } finally {
    reader.releaseLock()
  }
}
