import { ref } from 'vue'
import { defineStore } from 'pinia'
import { chatApi, type ConversationSummary, type HistoryTurn } from '../api/chat-api'
import type { PptTask } from '../api/ppt-api'
import type { DeepResearchReport } from '../api/research-api'
import type { StreamEvent, TodoItem } from '../types/stream-event'

/** 三种能力共用同一条时间线，靠 kind 区分渲染方式——不再是三个独立页面。 */
export type ChatTurn = {
  kind: 'chat'
  role: 'user' | 'assistant'
  content: string
  think?: string
  tools?: { name: string; toolCallId: string; detail: string }[]
}
export type PptEntry = { kind: 'ppt'; prompt: string; task?: PptTask; error?: string }
export type ResearchEntry = { kind: 'research'; question: string; result?: DeepResearchReport; error?: string }
export type ChatMessage = ChatTurn | PptEntry | ResearchEntry

export type ChatSession = { id: string; title: string }

const STORAGE_KEY = 'agenttrail.chat-sessions'
const TITLE_MAX_LENGTH = 60

/** 侧栏标题永远单行截断显示，但防线不能只放在 CSS 里——曾经真实发生过内部编排子提示词
 *（几千到上万字）被当成"问题"落库当标题，撑爆整个侧栏布局，这里从数据源头也截一刀。 */
function truncateTitle(title: string): string {
  const trimmed = title.trim()
  return trimmed.length > TITLE_MAX_LENGTH ? trimmed.slice(0, TITLE_MAX_LENGTH) + '…' : trimmed
}

function toChatSession(session: ConversationSummary): ChatSession {
  return { id: session.conversationId, title: truncateTitle(session.title) }
}

/** 对话状态由侧栏和 ChatView 共同消费，不能留在任一组件的本地 ref 中。 */
export const useChatStore = defineStore('chat', () => {
  const conversationId = ref<string>()
  const messages = ref<ChatMessage[]>([])
  const todos = ref<TodoItem[]>([])
  const sessions = ref<ChatSession[]>([])
  const sessionPage = ref(0)
  const sessionsHasMore = ref(false)

  function persistSessions() {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(sessions.value))
  }

  function acceptConversation(id: string, title: string) {
    conversationId.value = id
    const safeTitle = truncateTitle(title)
    const existing = sessions.value.find(session => session.id === id)
    if (existing) {
      existing.title = safeTitle || existing.title
    } else {
      sessions.value.unshift({ id, title: safeTitle || '新对话' })
    }
    persistSessions()
  }

  function startNewConversation() {
    conversationId.value = undefined
    messages.value = []
    todos.value = []
  }

  /** Research/PPT/文件问答没有 AgentStart 事件分配会话号，需要在首次调用时自己兜底。 */
  function ensureConversation(seedTitle: string) {
    if (!conversationId.value) acceptConversation(crypto.randomUUID(), seedTitle)
  }

  /** 把聊天 SSE 的语义收敛在 store 里，ChatView 只负责驱动流式迭代和忙碌/错误态 UI。 */
  function applyStreamEvent(event: StreamEvent, assistant: ChatTurn, seedTitle: string): string | undefined {
    switch (event.type) {
      case 'AgentStart':
        acceptConversation(event.conversationId, seedTitle)
        return undefined
      case 'Text':
        assistant.content += event.content
        return undefined
      case 'Thinking':
        assistant.think = (assistant.think ?? '') + event.content
        return undefined
      case 'ToolStart':
        assistant.tools?.push({ name: event.toolName, toolCallId: event.toolCallId, detail: event.arguments })
        return undefined
      case 'ToolEnd': {
        const tool = assistant.tools?.find(item => item.toolCallId === event.toolCallId)
        if (tool) tool.detail += `\n\n结果：${event.result}`
        return undefined
      }
      case 'TodoProgress':
        todos.value = event.items
        return undefined
      case 'Error':
        return event.message
      default:
        return undefined
    }
  }

  function turnToMessages(turns: HistoryTurn[]): ChatMessage[] {
    return turns.flatMap<ChatMessage>(turn => {
      if (turn.timeline) {
        try {
          const timeline = JSON.parse(turn.timeline) as Array<{
            type?: string
            stage?: string
            data?: { payload?: DeepResearchReport | PptTask; error?: string | null }
          }>
          const capability = Array.isArray(timeline)
            ? timeline.find(event => event.type === 'StageOutput' && (event.stage === 'research' || event.stage === 'ppt'))
            : undefined
          if (capability?.stage === 'research') {
            return [{
              kind: 'research' as const,
              question: turn.question,
              result: capability.data?.payload as DeepResearchReport | undefined,
              error: capability.data?.error ?? undefined
            }]
          }
          if (capability?.stage === 'ppt') {
            return [{
              kind: 'ppt' as const,
              prompt: turn.question,
              task: capability.data?.payload as PptTask | undefined,
              error: capability.data?.error ?? undefined
            }]
          }
        } catch {
          // 老版本或普通对话的 timeline 不是能力快照，继续按标准问答重建。
        }
      }
      return [
        { kind: 'chat' as const, role: 'user' as const, content: turn.question },
        {
          kind: 'chat' as const,
          role: 'assistant' as const,
          content: turn.answer ?? '',
          think: turn.think ?? undefined
        }
      ]
    })
  }

  async function openSession(id: string) {
    const page = await chatApi.history(id)
    conversationId.value = id
    messages.value = turnToMessages(page.turns)
    todos.value = []
    if (!sessions.value.some(session => session.id === id)) {
      acceptConversation(id, page.turns.at(0)?.question ?? '新对话')
    }
  }

  async function hydrateSessions() {
    try {
      const page = await chatApi.sessions()
      sessions.value = page.sessions.map(toChatSession)
      sessionPage.value = page.page
      sessionsHasMore.value = page.hasMore
      persistSessions()
    } catch {
      // 服务暂不可用时仍保留上次已知的会话入口；恢复后下一次刷新会重新以服务端为准。
      const raw = localStorage.getItem(STORAGE_KEY)
      if (!raw) return
      try {
        const parsed = JSON.parse(raw) as ChatSession[]
        sessions.value = parsed.filter(item => item.id && item.title)
      } catch {
        localStorage.removeItem(STORAGE_KEY)
        sessions.value = []
      }
    }
  }

  async function loadMoreSessions() {
    if (!sessionsHasMore.value) return
    const page = await chatApi.sessions(sessionPage.value + 1)
    const knownIds = new Set(sessions.value.map(session => session.id))
    sessions.value.push(...page.sessions
      .filter(session => !knownIds.has(session.conversationId))
      .map(toChatSession))
    sessionPage.value = page.page
    sessionsHasMore.value = page.hasMore
    persistSessions()
  }

  return {
    conversationId, messages, todos, sessions, sessionsHasMore,
    acceptConversation, ensureConversation, applyStreamEvent,
    startNewConversation, openSession, hydrateSessions, loadMoreSessions
  }
})
