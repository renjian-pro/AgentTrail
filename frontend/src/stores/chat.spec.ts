import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { useChatStore } from './chat'
import { chatApi } from '../api/chat-api'

vi.mock('../api/chat-api', () => ({ chatApi: { history: vi.fn(), sessions: vi.fn() } }))

describe('chat store', () => {
  beforeEach(() => {
    localStorage.clear()
    setActivePinia(createPinia())
  })

  it('starts a clean conversation without losing the known session list', () => {
    const store = useChatStore()
    store.acceptConversation('saved-conversation', '上一轮问题')
    store.messages.push({ kind: 'chat', role: 'user', content: '上一轮问题' })
    store.todos.push({ content: '旧任务', status: 'COMPLETED' })

    store.startNewConversation()

    expect(store.conversationId).toBeUndefined()
    expect(store.messages).toEqual([])
    expect(store.todos).toEqual([])
    expect(store.sessions).toEqual([{ id: 'saved-conversation', title: '上一轮问题' }])
  })

  it('hydrates the sidebar from the paginated server-side conversation list', async () => {
    vi.mocked(chatApi.sessions).mockResolvedValue({
      page: 0,
      size: 20,
      hasMore: false,
      sessions: [{ conversationId: 'persisted', title: '从服务端恢复的标题', lastActiveAtMillis: 1 }]
    })
    const store = useChatStore()

    await store.hydrateSessions()

    expect(store.sessions).toEqual([{ id: 'persisted', title: '从服务端恢复的标题' }])
  })

  it('appends the next server-side page instead of replacing existing history', async () => {
    vi.mocked(chatApi.sessions)
      .mockResolvedValueOnce({ page: 0, size: 1, hasMore: true, sessions: [{ conversationId: 'first', title: '第一条', lastActiveAtMillis: 2 }] })
      .mockResolvedValueOnce({ page: 1, size: 1, hasMore: false, sessions: [{ conversationId: 'second', title: '第二条', lastActiveAtMillis: 1 }] })
    const store = useChatStore()

    await store.hydrateSessions()
    await store.loadMoreSessions()

    expect(store.sessions).toEqual([{ id: 'first', title: '第一条' }, { id: 'second', title: '第二条' }])
    expect(store.sessionsHasMore).toBe(false)
  })

  it('rebuilds research and PPT capability cards from the shared conversation history', async () => {
    vi.mocked(chatApi.history).mockResolvedValue({
      conversationId: 'capabilities',
      page: 0,
      size: 20,
      hasMore: false,
      turns: [
        {
          id: 1,
          question: '研究 Java 就业趋势',
          answer: '研究结论',
          think: null,
          timeline: JSON.stringify([{ type: 'StageOutput', stage: 'research', data: {
            payload: { needsClarification: false, clarifyingQuestion: null, researchTopic: 'Java 就业趋势', taskResults: [], report: '研究结论' },
            error: null
          } }]),
          createdAtMillis: 1
        },
        {
          id: 2,
          question: '生成季度汇报',
          answer: 'PPT 任务状态：SUCCESS',
          think: null,
          timeline: JSON.stringify([{ type: 'StageOutput', stage: 'ppt', data: {
            payload: { taskId: 9, status: 'SUCCESS', errorMsg: null, outputPath: '/ppt/9' },
            error: null
          } }]),
          createdAtMillis: 2
        }
      ]
    })
    const store = useChatStore()

    await store.openSession('capabilities')

    expect(store.messages).toEqual([
      {
        kind: 'research',
        question: '研究 Java 就业趋势',
        result: { needsClarification: false, clarifyingQuestion: null, researchTopic: 'Java 就业趋势', taskResults: [], report: '研究结论' },
        error: undefined
      },
      {
        kind: 'ppt',
        prompt: '生成季度汇报',
        task: { taskId: 9, status: 'SUCCESS', errorMsg: null, outputPath: '/ppt/9' },
        error: undefined
      }
    ])
  })
})
