import type { StreamEvent } from '../types/stream-event'
import { jsonInit, request } from './http'
import { clearToken, readToken } from './auth-token'

export type ChatRequest = { message: string; conversationId?: string; modelId?: string; webSearchEnabled: boolean; mode?: string }
export type HistoryTurn = { id: number; question: string; answer: string; think: string | null; timeline: string | null; createdAtMillis: number }
export type HistoryPage = { conversationId: string; page: number; size: number; hasMore: boolean; turns: HistoryTurn[] }
export type ConversationSummary = { conversationId: string; title: string; lastActiveAtMillis: number }
export type ConversationPage = { page: number; size: number; hasMore: boolean; sessions: ConversationSummary[] }
const eventTypes = new Set<StreamEvent['type']>(['AgentStart', 'Thinking', 'Text', 'ToolStart', 'ToolEnd', 'TodoProgress', 'StageOutput', 'Error', 'Complete'])

export function decodeStreamEvent(raw: string): StreamEvent | null {
  if (!raw.trim()) return null
  const payload = JSON.parse(raw) as Record<string, unknown>
  const type = String(payload.type ?? payload['@type'] ?? '')
  if (eventTypes.has(type as StreamEvent['type'])) return { ...payload, type } as StreamEvent
  const keys = Object.keys(payload)
  return keys.length === 1 && eventTypes.has(keys[0] as StreamEvent['type'])
    ? { ...(payload[keys[0]] as object), type: keys[0] } as StreamEvent : null
}

/** Spring 用 SSE 的 event 字段携带 sealed-interface 的具体变体，JSON 保持纯载荷。 */
export function decodeSseFrame(frame: string): StreamEvent | null {
  const lines = frame.split(/\r?\n/)
  const eventName = lines.find(line => line.startsWith('event:'))?.slice(6).trim()
  const payload = lines.filter(line => line.startsWith('data:')).map(line => line.slice(5).trim()).join('')
  const decoded = decodeStreamEvent(payload)
  if (decoded) return decoded
  if (!eventName || !payload) return null
  return { ...(JSON.parse(payload) as object), type: eventName } as StreamEvent
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
