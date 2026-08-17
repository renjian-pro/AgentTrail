import type { StreamEvent } from '../types/stream-event'
import { jsonInit, request } from './http'
import { clearToken, readToken } from './auth-token'

/**
 * `fileIds`：这一轮要附带的文件（issue #110）。**上传和"这一轮附了什么"是两件事**——
 * 上传只是把文件存下来，真正的绑定发生在按下发送、把 id 显式带上来的这一刻。
 * 后端此前是"扫一遍这个会话里所有还没归属轮次的文件"，于是在输入框里删掉一个 chip
 * 只是视觉效果，它下一轮照样会进模型的上下文。
 */
export type ChatRequest = {
  message: string
  conversationId?: string
  modelId?: string
  webSearchEnabled: boolean
  mode?: string
  fileIds?: number[]
}
export type HistoryTurn = { id: number; question: string; answer: string; think: string | null; timeline: string | null; createdAtMillis: number }
export type HistoryPage = { conversationId: string; page: number; size: number; hasMore: boolean; turns: HistoryTurn[] }
export type ConversationSummary = { conversationId: string; title: string; lastActiveAtMillis: number }
export type ConversationPage = { page: number; size: number; hasMore: boolean; sessions: ConversationSummary[] }
const eventTypes = new Set<StreamEvent['type']>(['RunStarted', 'ModelDelta', 'ThinkingDelta', 'ToolStarted', 'ToolCompleted', 'Paused', 'RunFailed', 'RunCompleted'])

/**
 * 后端每帧 data 是完整的 EventEnvelope（eventId/runId/conversationId/type/payload/...），
 * 具体事件字段（content/toolName/...）另外 JSON 编码在 envelope.payload 里，需要二次解析。
 */
export function decodeStreamEvent(raw: string): StreamEvent | null {
  if (!raw.trim()) return null
  const envelope = JSON.parse(raw) as { type?: string; payload?: string }
  const type = envelope.type
  if (!type || !eventTypes.has(type as StreamEvent['type'])) return null
  const fields = envelope.payload ? JSON.parse(envelope.payload) : {}
  return { ...(fields as object), type } as StreamEvent
}

export function decodeSseFrame(frame: string): StreamEvent | null {
  const lines = frame.split(/\r?\n/)
  const data = lines.filter(line => line.startsWith('data:')).map(line => line.slice(5).trim()).join('')
  return decodeStreamEvent(data)
}

export async function* streamChat(body: ChatRequest, signal?: AbortSignal): AsyncGenerator<StreamEvent> {
  const headers = new Headers({ Accept: 'text/event-stream', 'Content-Type': 'application/json' })
  const token = readToken()
  if (token) headers.set('Authorization', `Bearer ${token}`)
  const response = await fetch('/agent/v1/chat', { ...jsonInit(body), signal, headers })
  if (response.status === 401) {
    clearToken()
    if (typeof window !== 'undefined' && window.location.pathname !== '/login') {
      try { window.location.href = '/login' } catch { /* jsdom and embedded hosts may reject navigation */ }
    }
  }
  if (!response.ok || !response.body) throw new Error(await response.text() || '对话连接失败')
  const reader = response.body.getReader(); const decoder = new TextDecoder(); let buffer = ''
  while (true) {
    const part = await reader.read(); if (part.done) break
    buffer += decoder.decode(part.value, { stream: true })
    const frames = buffer.split(/\r?\n\r?\n/); buffer = frames.pop() ?? ''
    for (const frame of frames) {
      const event = decodeSseFrame(frame); if (event) yield event
    }
  }
}

export const chatApi = {
  stop: (conversationId: string) => request<{ stopped: boolean }>('/agent/v1/chat/stop?conversationId=' + encodeURIComponent(conversationId), { method: 'POST' }),
  history: (conversationId: string, page = 0) => request<HistoryPage>(`/agent/v1/conversations/${encodeURIComponent(conversationId)}/history?page=${page}`),
  sessions: (page = 0) => request<ConversationPage>(`/agent/v1/conversations?page=${page}`)
}
