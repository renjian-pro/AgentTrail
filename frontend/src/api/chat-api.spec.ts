import { afterEach, describe, expect, it, vi } from 'vitest'
import { chatApi, decodeSseFrame, decodeStreamEvent, streamApproval, streamChat } from './chat-api'

describe('SSE event decoder', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('decodes an EventEnvelope frame by unwrapping its JSON-encoded payload', () => {
    expect(decodeStreamEvent('{"type":"ModelDelta","payload":"{\\"content\\":\\"你好\\"}"}'))
      .toEqual({ type: 'ModelDelta', content: '你好' })
  })

  it('carries every field back from a multi-field payload, e.g. RunCompleted', () => {
    expect(decodeStreamEvent('{"type":"RunCompleted","payload":"{\\"conversationId\\":\\"c1\\",\\"turnId\\":2}"}'))
      .toEqual({ type: 'RunCompleted', conversationId: 'c1', turnId: 2 })
  })

  it('decodes the sanitized pending tools carried by a Paused event', () => {
    expect(decodeStreamEvent('{"type":"Paused","payload":"{\\"conversationId\\":\\"c1\\",\\"reason\\":\\"HITL_APPROVAL\\",\\"pendingTools\\":[{\\"toolCallId\\":\\"t1\\",\\"toolName\\":\\"chargeCard\\",\\"arguments\\":\\"{\\\\\\"amount\\\\\\":100}\\",\\"riskLevel\\":\\"HIGH_RISK\\"}]}"}'))
      .toEqual({
        type: 'Paused',
        conversationId: 'c1',
        reason: 'HITL_APPROVAL',
        pendingTools: [{ toolCallId: 't1', toolName: 'chargeCard', arguments: '{"amount":100}', riskLevel: 'HIGH_RISK' }]
      })
  })

  it('ignores envelopes whose type is not part of the known SSE contract', () => {
    expect(decodeStreamEvent('{"type":"SomeInternalEvent","payload":"{}"}')).toBeNull()
  })

  it('reads the envelope from the data: line of an SSE frame', () => {
    expect(decodeSseFrame('event: ModelDelta\ndata: {"type":"ModelDelta","payload":"{\\"content\\":\\"流式正文\\"}"}'))
      .toEqual({ type: 'ModelDelta', content: '流式正文' })
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
    streamController.enqueue(new TextEncoder().encode(
      'event: ModelDelta\ndata: {"type":"ModelDelta","payload":"{\\"content\\":\\"首段\\"}"}\n\n'))

    expect(await firstEvent).toEqual({ done: false, value: { type: 'ModelDelta', content: '首段' } })
    streamController.close()
  })

  it('uses the shared SSE transport for approval decisions', async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response(
      'event: RunCompleted\ndata: {"type":"RunCompleted","payload":"{\\"conversationId\\":\\"c1\\",\\"turnId\\":3}"}\n\n',
      { status: 200, headers: { 'Content-Type': 'text/event-stream' } }
    ))
    vi.stubGlobal('fetch', fetchMock)

    const events = await Array.fromAsync(streamApproval('c1', { approved: false, rejectionReason: '金额异常' }))

    expect(events).toEqual([{ type: 'RunCompleted', conversationId: 'c1', turnId: 3 }])
    expect(fetchMock).toHaveBeenCalledWith('/agent/v1/chat/c1/approve', expect.objectContaining({
      method: 'POST',
      body: JSON.stringify({ approved: false, rejectionReason: '金额异常' })
    }))
  })

  it('loads the current pending approval for refresh recovery', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify({
      conversationId: 'c1', reason: 'HITL_APPROVAL', pausedAtMillis: 7,
      pendingTools: [{ toolCallId: 't1', toolName: 'chargeCard', arguments: '{}', riskLevel: 'HIGH_RISK' }]
    }), { status: 200, headers: { 'Content-Type': 'application/json' } })))

    await expect(chatApi.getPendingApproval('c1')).resolves.toEqual(expect.objectContaining({
      conversationId: 'c1', pausedAtMillis: 7
    }))
  })
})
