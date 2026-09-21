import { describe, expect, it } from 'vitest'
import { consumeSse, type SseEvent } from './sse'

describe('consumeSse', () => {
  it('normalizes CRLF even when each byte arrives in a different chunk',async()=>{
    const bytes=new TextEncoder().encode('event: delta\r\ndata: {"content":"text"}\r\n\r\nevent: done\r\ndata: {}\r\n\r\n')
    const events:SseEvent[]=[]
    const body=new ReadableStream<Uint8Array>({start(controller){for(const byte of bytes)controller.enqueue(new Uint8Array([byte]));controller.close()}})
    await consumeSse(new Response(body),event=>events.push(event))
    expect(events.map(event=>event.event)).toEqual(['delta','done'])
  })
  it('parses named JSON events across arbitrary response chunks', async () => {
    const encoder = new TextEncoder()
    const stream = new ReadableStream<Uint8Array>({
      start(controller) {
        controller.enqueue(encoder.encode('event: delta\ndata: {"content":"hel'))
        controller.enqueue(encoder.encode('lo"}\n\nevent: done\ndata: {"totalTokenCount":7}\n\n'))
        controller.close()
      },
    })
    const events: SseEvent[] = []
    await consumeSse(new Response(stream), (event) => events.push(event))
    expect(events).toEqual([
      { event: 'delta', data: { content: 'hello' } },
      { event: 'done', data: { totalTokenCount: 7 } },
    ])
  })
})
