import { afterEach, describe, expect, it, vi } from 'vitest'
import { decodeSseFrame, decodeStreamEvent, streamChat } from './chat-api'

describe('SSE event decoder', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('decodes the Java sealed-event JSON shape', () => {
    expect(decodeStreamEvent('{"Text":{"content":"你好"}}')).toEqual({ type: 'Text', content: '你好' })
  })

  it('keeps explicit type events compatible with future event serializers', () => {
    expect(decodeStreamEvent('{"type":"Complete","conversationId":"c1","turnId":2}'))
      .toEqual({ type: 'Complete', conversationId: 'c1', turnId: 2 })
  })

  it('uses the SSE event name when the JSON payload has no type field', () => {
    expect(decodeSseFrame('event: Text\ndata: {"content":"流式正文"}'))
      .toEqual({ type: 'Text', content: '流式正文' })
  })

  it('yields a complete SSE frame before the HTTP stream closes', async () => {
    let streamController!: ReadableStreamDefaultController<Uint8Array>
    const body = new ReadableStream<Uint8Array>({
      start(controller) { streamController = controller }
    })
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(body, {
      status: 200,
      headers: { 'Content-Type': 'text/event-stream' }
    })))

    const events = streamChat({ message: '你好', webSearchEnabled: false })
    const firstEvent = events.next()
    streamController.enqueue(new TextEncoder().encode('event: Text\ndata: {"content":"首段"}\n\n'))

    expect(await firstEvent).toEqual({ done: false, value: { type: 'Text', content: '首段' } })
    streamController.close()
  })
})
