import { createPinia, setActivePinia } from 'pinia'
import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import ChatView from './ChatView.vue'
import { useChatStore } from '../stores/chat'
import { pptApi } from '../api/ppt-api'
import { researchApi } from '../api/research-api'
import { streamChat } from '../api/chat-api'

vi.mock('../api/chat-api', () => ({
  chatApi: { stop: vi.fn(), history: vi.fn() },
  streamChat: vi.fn()
}))
vi.mock('../api/ppt-api', () => ({ pptApi: { create: vi.fn(), resume: vi.fn(), status: vi.fn() } }))
vi.mock('../api/research-api', () => ({ researchApi: { run: vi.fn(), status: vi.fn() } }))

describe('ChatView', () => {
  beforeEach(() => {
    localStorage.clear()
    setActivePinia(createPinia())
    vi.clearAllMocks()
    vi.mocked(streamChat).mockImplementation(async function * () {
      yield { type: 'AgentStart', conversationId: 'streamed-conversation' }
      yield { type: 'Thinking', content: '先分析问题' }
      yield { type: 'Text', content: '这是回答' }
      yield { type: 'Complete', conversationId: 'streamed-conversation', turnId: 1 }
    })
  })

  it('renders a streamed answer and records the reusable conversation id', async () => {
    const pinia = createPinia()
    setActivePinia(pinia)
    const wrapper = mount(ChatView, { global: { plugins: [pinia] } })
    await wrapper.find('textarea').setValue('你好')
    await wrapper.find('form').trigger('submit')
    await flushPromises()
    const store = useChatStore()
    expect(wrapper.text()).toContain('这是回答')
    expect(store.conversationId).toBe('streamed-conversation')
    expect(store.sessions).toEqual([{ id: 'streamed-conversation', title: '你好' }])
  })

  it('renders each text event before the stream completes', async () => {
    let releaseRemainder!: () => void
    const remainder = new Promise<void>(resolve => { releaseRemainder = resolve })
    vi.mocked(streamChat).mockImplementation(async function * () {
      yield { type: 'AgentStart', conversationId: 'progressive-conversation' }
      yield { type: 'Text', content: '第一段已经到达' }
      await remainder
      yield { type: 'Text', content: '，第二段稍后到达' }
      yield { type: 'Complete', conversationId: 'progressive-conversation', turnId: 1 }
    })

    const wrapper = mount(ChatView, { global: { plugins: [createPinia()] } })
    await wrapper.find('textarea').setValue('测试逐段显示')
    await wrapper.find('form').trigger('submit')
    await flushPromises()

    expect(wrapper.text()).toContain('第一段已经到达')
    expect(wrapper.text()).not.toContain('第二段稍后到达')

    releaseRemainder()
    await flushPromises()
    expect(wrapper.text()).toContain('第一段已经到达，第二段稍后到达')
  })

  it('routes the next message to PPT generation instead of chat when that mode is selected, then reverts to chat', async () => {
    vi.mocked(pptApi.create).mockResolvedValue({ taskId: 12, status: 'RENDER', errorMsg: null, outputPath: null })
    // create() 现在只提交任务，本身没跑完（RENDER 不是终态）——runPpt 提交后会立即再轮询一次
    // pptApi.status()，让它原地停在同一个状态即可，这个用例不关心后续轮询本身。
    vi.mocked(pptApi.status).mockResolvedValue({ taskId: 12, status: 'RENDER', errorMsg: null, outputPath: null })
    const wrapper = mount(ChatView, { global: { plugins: [createPinia()] } })

    await wrapper.findAll('.mode-picker button')[1].trigger('click')
    await wrapper.find('textarea').setValue('生成战略汇报')
    await wrapper.find('form').trigger('submit')
    await flushPromises()

    expect(pptApi.create).toHaveBeenCalledWith(expect.any(String), '生成战略汇报')
    expect(wrapper.text()).toContain('正在渲染')
    expect(wrapper.find('.mode-hint').exists()).toBe(false)

    await wrapper.find('textarea').setValue('这条应该走普通对话')
    await wrapper.find('form').trigger('submit')
    await flushPromises()
    expect(pptApi.create).toHaveBeenCalledTimes(1)
  })

  it('renders the Deep Research clarification card inline instead of dumping raw JSON', async () => {
    // 需要澄清也是"跑完了"（研究服务调用本身没抛异常），status 是 SUCCESS 不是 FAILED——
    // run() 直接返回终态时 runResearch 不会再去轮询 status()。
    vi.mocked(researchApi.run).mockResolvedValue({
      taskId: 1,
      status: 'SUCCESS',
      report: {
        needsClarification: true,
        clarifyingQuestion: '你希望研究哪个行业？',
        researchTopic: null,
        taskResults: [],
        report: null
      },
      errorMsg: null
    })
    const wrapper = mount(ChatView, { global: { plugins: [createPinia()] } })

    await wrapper.findAll('.mode-picker button')[0].trigger('click')
    await wrapper.find('textarea').setValue('帮我研究 AI')
    await wrapper.find('form').trigger('submit')
    await flushPromises()

    expect(wrapper.text()).toContain('你希望研究哪个行业？')
    expect(wrapper.find('pre').exists()).toBe(false)
  })

  /**
   * 回归测试：曾经 busy 会一直锁到研究整个跑完（同步阻塞调用），切换会话时这个悬空的
   * busy 会卡住新会话的工具栏、任务本身也从界面消失。现在提交（拿到 taskId）就该解锁，
   * 报告本身靠轮询在后台推进，不占着输入框和工具栏。
   */
  it('unlocks capability controls right after research is submitted, while the report keeps polling in the background', async () => {
    vi.mocked(researchApi.run).mockResolvedValue({ taskId: 7, status: 'RUNNING', report: null, errorMsg: null })
    let resolveStatus!: (value: Awaited<ReturnType<typeof researchApi.status>>) => void
    vi.mocked(researchApi.status).mockReturnValue(new Promise(resolve => { resolveStatus = resolve }))
    const wrapper = mount(ChatView, { global: { plugins: [createPinia()] } })

    await wrapper.findAll('.mode-picker button')[0].trigger('click')
    await wrapper.find('textarea').setValue('研究 Java 工程师就业趋势')
    await wrapper.find('form').trigger('submit')
    await flushPromises()

    expect(wrapper.get('.research-question').text()).toContain('研究 Java 工程师就业趋势')
    expect(wrapper.get('.research-loading').text()).toContain('深度研究进行中')
    // 提交已经完成（拿到了 taskId），不该继续锁住工具栏——报告还在后台轮询，界面不该跟着卡死。
    expect(wrapper.findAll('.mode-picker button').every(button => button.attributes('disabled') === undefined)).toBe(true)
    expect(wrapper.find('.stop').exists()).toBe(false)
    expect(researchApi.run).toHaveBeenCalledWith(expect.any(String), '研究 Java 工程师就业趋势')
    expect(researchApi.status).toHaveBeenCalledWith(7)

    resolveStatus({
      taskId: 7,
      status: 'SUCCESS',
      report: {
        needsClarification: false,
        clarifyingQuestion: null,
        researchTopic: 'Java 工程师就业趋势',
        taskResults: [],
        report: '研究完成。'
      },
      errorMsg: null
    })
    await flushPromises()
    expect(wrapper.text()).toContain('研究完成')
  })
})
