import { createPinia, setActivePinia } from 'pinia'
import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import ChatView from './ChatView.vue'
import { useChatStore } from '../stores/chat'
import { pptApi } from '../api/ppt-api'
import { researchApi } from '../api/research-api'
import { chatApi, streamApproval, streamChat } from '../api/chat-api'
import { fileApi } from '../api/file-api'

vi.mock('../api/chat-api', () => ({
  chatApi: { stop: vi.fn(), history: vi.fn(), getPendingApproval: vi.fn() },
  streamChat: vi.fn(),
  streamApproval: vi.fn()
}))
vi.mock('../api/ppt-api', () => ({ pptApi: { create: vi.fn(), resume: vi.fn(), status: vi.fn() } }))
vi.mock('../api/research-api', () => ({ researchApi: { run: vi.fn(), status: vi.fn() } }))
vi.mock('../api/file-api', () => ({ fileApi: { upload: vi.fn(), remove: vi.fn() } }))

describe('ChatView', () => {
  beforeEach(() => {
    localStorage.clear()
    setActivePinia(createPinia())
    vi.clearAllMocks()
    vi.mocked(streamChat).mockImplementation(async function * () {
      yield { type: 'RunStarted', conversationId: 'streamed-conversation' }
      yield { type: 'ThinkingDelta', content: '先分析问题' }
      yield { type: 'ModelDelta', content: '这是回答' }
      yield { type: 'RunCompleted', conversationId: 'streamed-conversation', turnId: 1 }
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

  it('renders a paused tool approval inline and continues the same assistant after approval', async () => {
    vi.mocked(streamChat).mockImplementation(async function * () {
      yield { type: 'RunStarted', conversationId: 'approval-conversation' }
      yield {
        type: 'Paused', conversationId: 'approval-conversation', reason: 'HITL_APPROVAL',
        pendingTools: [{ toolCallId: 't1', toolName: 'chargeCard', arguments: '{"amount":100}', riskLevel: 'HIGH_RISK' }]
      }
    })
    vi.mocked(streamApproval).mockImplementation(async function * () {
      yield { type: 'ToolStarted', toolName: 'chargeCard', toolCallId: 't1', arguments: '{"amount":100}' }
      yield { type: 'ToolCompleted', toolName: 'chargeCard', toolCallId: 't1', result: 'charged' }
      yield { type: 'ModelDelta', content: '充值成功' }
      yield { type: 'RunCompleted', conversationId: 'approval-conversation', turnId: 2 }
    })
    const wrapper = mount(ChatView, { global: { plugins: [createPinia()] } })
    await wrapper.find('textarea').setValue('充值 100 元')
    await wrapper.find('form').trigger('submit')
    await flushPromises()

    expect(wrapper.get('.tool-approval-card').text()).toContain('chargeCard')
    expect(wrapper.find('.composer button').attributes('disabled')).toBeDefined()
    await wrapper.get('.approve-button').trigger('click')
    await flushPromises()

    expect(streamApproval).toHaveBeenCalledTimes(1)
    const assistant = useChatStore().messages.find(message => message.kind === 'chat' && message.role === 'assistant')
    expect(assistant?.kind === 'chat' ? assistant.tools?.[0]?.result : undefined).toBe('charged')
    expect(wrapper.text()).toContain('充值成功')
    expect(wrapper.get('.tool-approval-card').text()).toContain('已批准')
    expect(wrapper.find('.composer button').attributes('disabled')).toBeUndefined()
  })

  it('recovers an unresolved approval when an existing conversation is opened', async () => {
    vi.mocked(chatApi.history).mockResolvedValue({
      conversationId: 'paused-conversation', page: 0, size: 20, hasMore: false, turns: []
    })
    vi.mocked(chatApi.getPendingApproval).mockResolvedValue({
      conversationId: 'paused-conversation', reason: 'HITL_APPROVAL', pausedAtMillis: 7,
      pendingTools: [{ toolCallId: 't1', toolName: 'chargeCard', arguments: '{}', riskLevel: 'HIGH_RISK' }]
    })
    const pinia = createPinia()
    const wrapper = mount(ChatView, { global: { plugins: [pinia] } })

    await useChatStore().openSession('paused-conversation')
    await flushPromises()

    expect(wrapper.get('.tool-approval-card').text()).toContain('chargeCard')
    expect(useChatStore().hasPendingApproval).toBe(true)
  })

  it('sends the optional rejection reason and continues without executing the tool', async () => {
    vi.mocked(streamChat).mockImplementation(async function * () {
      yield { type: 'RunStarted', conversationId: 'rejection-conversation' }
      yield {
        type: 'Paused', conversationId: 'rejection-conversation', reason: 'HITL_APPROVAL',
        pendingTools: [{ toolCallId: 't1', toolName: 'chargeCard', arguments: '{}', riskLevel: 'HIGH_RISK' }]
      }
    })
    vi.mocked(streamApproval).mockImplementation(async function * (_conversationId, decision) {
      expect(decision).toEqual({ approved: false, rejectionReason: '金额异常' })
      yield { type: 'ModelDelta', content: '好的，已取消充值' }
      yield { type: 'RunCompleted', conversationId: 'rejection-conversation', turnId: 3 }
    })
    const wrapper = mount(ChatView, { global: { plugins: [createPinia()] } })
    await wrapper.find('.composer textarea').setValue('充值')
    await wrapper.find('.composer').trigger('submit')
    await flushPromises()

    await wrapper.get('.tool-approval-card textarea').setValue('金额异常')
    await wrapper.get('.reject-button').trigger('click')
    await flushPromises()

    const assistant = useChatStore().messages.find(message => message.kind === 'chat' && message.role === 'assistant')
    expect(assistant?.kind === 'chat' ? assistant.tools : undefined).toEqual([])
    expect(wrapper.text()).toContain('好的，已取消充值')
    expect(wrapper.get('.tool-approval-card').text()).toContain('已拒绝')
  })

  /**
   * 回归测试：切换会话时中断上一个会话遗留的 SSE 流，靠的是 watch(navigationSeq, ...)——
   * 踩过的坑是改成直接 watch(conversationId, ...)：RunStarted 事件会把 conversationId
   * 从 undefined 改写成服务端刚分配的真实 id，这也是 conversationId 的一次变化，但那是"这次
   * 请求认领了它自己的会话号"，不是"用户换了会话"。如果两者不分开，第一条消息发出去之后
   * conversationId 一变，watcher 就会把这次请求自己的 aborter.abort() 调用了——请求还没走完
   * 就被自己掐断，是真实复现过的行为，不是假设的边界情况。
   */
  it('does not abort its own SSE stream when RunStarted assigns the very first conversationId', async () => {
    let capturedSignal: AbortSignal | undefined
    vi.mocked(streamChat).mockImplementation(async function * (_body, signal) {
      capturedSignal = signal
      yield { type: 'RunStarted', conversationId: 'brand-new-conversation' }
      yield { type: 'ModelDelta', content: '你好呀' }
      yield { type: 'RunCompleted', conversationId: 'brand-new-conversation', turnId: 1 }
    })
    const wrapper = mount(ChatView, { global: { plugins: [createPinia()] } })

    await wrapper.find('textarea').setValue('第一条消息，之前没有 conversationId')
    await wrapper.find('form').trigger('submit')
    await flushPromises()

    expect(capturedSignal?.aborted).toBe(false)
    expect(wrapper.text()).toContain('你好呀')
  })

  it('renders each text event before the stream completes', async () => {
    let releaseRemainder!: () => void
    const remainder = new Promise<void>(resolve => { releaseRemainder = resolve })
    vi.mocked(streamChat).mockImplementation(async function * () {
      yield { type: 'RunStarted', conversationId: 'progressive-conversation' }
      yield { type: 'ModelDelta', content: '第一段已经到达' }
      await remainder
      yield { type: 'ModelDelta', content: '，第二段稍后到达' }
      yield { type: 'RunCompleted', conversationId: 'progressive-conversation', turnId: 1 }
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

  it('fires PPT generation from the composer once PPT mode is selected', async () => {
    vi.mocked(pptApi.create).mockResolvedValue({ taskId: 12, status: 'RENDER', errorMsg: null, outputPath: null })
    // create() 现在只提交任务，本身没跑完（RENDER 不是终态）——runPpt 提交后会立即再轮询一次
    // pptApi.status()，让它原地停在同一个状态即可，这个用例不关心后续轮询本身。
    vi.mocked(pptApi.status).mockResolvedValue({ taskId: 12, status: 'RENDER', errorMsg: null, outputPath: null })
    const wrapper = mount(ChatView, { global: { plugins: [createPinia()] } })

    await wrapper.findAll('.agent-option')[3].trigger('click')
    await wrapper.find('textarea').setValue('生成战略汇报')
    await wrapper.find('form').trigger('submit')
    await flushPromises()

    expect(pptApi.create).toHaveBeenCalledWith(expect.any(String), '生成战略汇报')
    expect(wrapper.text()).toContain('正在渲染')
    // 模式发完不复位（R13/R14）：PPT 仍然是选中态，用户可以接着改主题再发一版
    expect(wrapper.findAll('.agent-option')[3].classes()).toContain('active')

    // 这条断言的方向和改造前**正好相反**，是有意的：任务曾经是"动作"，点一次发起一次，
    // 下一条消息自动回到普通对话；现在它是模式，选中后一直保持，第二次发送仍然走 PPT。
    // 想发普通消息得先手动切回去——这正是 R13「不因发送而复位」要的行为。
    await wrapper.find('textarea').setValue('再来一版，换个角度')
    await wrapper.find('form').trigger('submit')
    await flushPromises()
    expect(pptApi.create).toHaveBeenCalledTimes(2)
    expect(streamChat).not.toHaveBeenCalled()
  })

  /**
   * 数据分析和 research/ppt 不一样，不脱离普通聊天的 SSE 流——它是 **Agent 层**，会话级绑定：
   * 选中之后整个会话的每一条消息都带 mode:'analytics'，让后端路由到分析执行器
   * （见 ChatToolScopeRuntimeAdapter）。
   *
   * <p>**这是 issue #92 的核心回归**。改之前是"模式只对下一条消息生效、发完立刻复位"，
   * 用户追问时会静默掉回普通对话，模型没有数据库工具就去编造演示数据画图。断言必须钉住
   * "同一会话连续两条消息带的 mode 一致"，否则哪天改回去了没人会发现。
   */
  it('keeps mode:analytics for every message in the session, not just the next one', async () => {
    const wrapper = mount(ChatView, { global: { plugins: [createPinia()] } })

    await wrapper.findAll('.agent-option')[1].trigger('click')
    await wrapper.find('textarea').setValue('上个月的订单量是多少')
    await wrapper.find('form').trigger('submit')
    await flushPromises()

    expect(streamChat).toHaveBeenCalledWith(
      expect.objectContaining({ message: '上个月的订单量是多少', mode: 'analytics', webSearchEnabled: false }),
      expect.anything())

    await wrapper.find('textarea').setValue('那上上个月呢')
    await wrapper.find('form').trigger('submit')
    await flushPromises()

    expect(streamChat).toHaveBeenLastCalledWith(
      expect.objectContaining({ message: '那上上个月呢', mode: 'analytics' }),
      expect.anything())
  })

  /**
   * 协议分派的核心断言（requirements.md §7.2）：四个模式共用一个发送入口，但 `research`/`ppt`
   * 不走 `/agent/v1/chat` 的 SSE 流，而是各自创建异步任务。选中 PPT 模式后按发送，走的必须是
   * `pptApi.create`，而且**模式保持不变**——发完不复位是 R13/R14 的核心。
   */
  it('routes send to the task API when a task mode is selected, and keeps the mode', async () => {
    vi.mocked(pptApi.create).mockResolvedValue({ taskId: 12, status: 'RENDER', errorMsg: null, outputPath: null })
    vi.mocked(pptApi.status).mockResolvedValue({ taskId: 12, status: 'SUCCESS', errorMsg: null, outputPath: 'a.pptx' })
    const wrapper = mount(ChatView, { global: { plugins: [createPinia()] } })

    await wrapper.findAll('.agent-option')[3].trigger('click')
    await wrapper.find('textarea').setValue('把刚才的结论做成 PPT')
    await wrapper.find('form').trigger('submit')
    await flushPromises()

    expect(pptApi.create).toHaveBeenCalled()
    expect(streamChat).not.toHaveBeenCalled()
    expect(useChatStore().agentKind).toBe('ppt')
  })

  /**
   * 联网搜索与模式不正交（R14a）：普通对话可开关、数据分析禁用并说明理由、
   * research/ppt 下**整颗按钮不渲染**——它们内部无条件做自己的资料检索，
   * 渲染成"已开启的开关"会暗示用户可以关掉，而关掉这两个模式压根跑不起来。
   */
  it('scopes the web-search toggle to the modes it actually applies to', async () => {
    const wrapper = mount(ChatView, { global: { plugins: [createPinia()] } })
    const toggle = () => wrapper.findAll('.capability-bar button').find(b => b.text().includes('联网搜索'))

    expect(toggle()).toBeTruthy()
    expect(toggle()!.attributes('disabled')).toBeUndefined()

    await wrapper.findAll('.agent-option')[1].trigger('click')
    expect(toggle()!.attributes('disabled')).toBeDefined()
    expect(wrapper.get('.bar-notes').text()).toContain('不挂载联网搜索')

    await wrapper.findAll('.agent-option')[2].trigger('click')
    expect(toggle()).toBeUndefined()
    expect(wrapper.get('.bar-notes').text()).toContain('自带资料检索')
  })

  /**
   * issue #94。会话级绑定之后，用户在普通对话里问数据问题仍然会拿到编造的数字——他不知道
   * 自己在哪个 Agent 下，也不知道该怎么切。卡片插在提问之后、回答之前，等模型答完再提示就晚了。
   */
  it('offers a switch to analytics when a data question lands in a plain chat', async () => {
    const wrapper = mount(ChatView, { global: { plugins: [createPinia()] } })

    await wrapper.find('textarea').setValue('上个月的订单量是多少')
    await wrapper.find('form').trigger('submit')
    await flushPromises()

    expect(wrapper.find('.switch-hint').exists()).toBe(true)

    // 就地切换，**不再新开会话**：模式改成轮次级之后，用户刚问的那个数据问题还在上面，
    // 换个会话反而把它丢了。此前这里是 startNewConversation('analytics')，那是会话级绑定
    // 时代的唯一出路（会话一旦锁定就换不了 Agent）。
    await wrapper.find('.switch-hint button').trigger('click')
    const store = useChatStore()
    expect(store.agentKind).toBe('analytics')
    expect(store.messages.length).toBeGreaterThan(0)
  })

  /** 数据分析会话里不该出现这张卡片——它已经在正确的 Agent 上了。 */
  it('stays quiet about switching when the session is already analytics', async () => {
    const wrapper = mount(ChatView, { global: { plugins: [createPinia()] } })

    await wrapper.findAll('.agent-option')[1].trigger('click')
    await wrapper.find('textarea').setValue('上个月的订单量是多少')
    await wrapper.find('form').trigger('submit')
    await flushPromises()

    expect(wrapper.find('.switch-hint').exists()).toBe(false)
  })

  /** 普通闲聊不该被打断。识别的主要风险是误报，不是漏报。 */
  it('leaves ordinary chat alone', async () => {
    const wrapper = mount(ChatView, { global: { plugins: [createPinia()] } })

    await wrapper.find('textarea').setValue('帮我写一段自我介绍')
    await wrapper.find('form').trigger('submit')
    await flushPromises()

    expect(wrapper.find('.switch-hint').exists()).toBe(false)
  })

  /**
   * 模式化之后"空输入"不再是特例：发送按钮本来就不接受空白输入（MessageInput.submit 自己拦），
   * 所以不需要再为任务按钮单独准备一句"先写下要研究的问题"。占位文案随模式变，
   * 用户不用猜这个模式下该写什么。
   */
  it('tells the user what to write by switching the composer placeholder per mode', async () => {
    const wrapper = mount(ChatView, { global: { plugins: [createPinia()] } })

    await wrapper.findAll('.agent-option')[2].trigger('click')
    expect(wrapper.find('textarea').attributes('placeholder')).toContain('研究')

    await wrapper.find('form').trigger('submit')
    await flushPromises()
    expect(researchApi.run).not.toHaveBeenCalled()

    // 写了内容再发，才真的发起任务
    vi.mocked(researchApi.run).mockResolvedValue({
      taskId: 7,
      status: 'SUCCESS',
      report: {
        needsClarification: false,
        clarifyingQuestion: null,
        researchTopic: '行业现状',
        taskResults: [],
        report: '结论'
      },
      errorMsg: null
    })
    await wrapper.find('textarea').setValue('查一下行业现状')
    await wrapper.find('form').trigger('submit')
    await flushPromises()

    expect(researchApi.run).toHaveBeenCalled()
  })

  /**
   * **本次修复的核心回归**（requirements.md R13/R14）：模式发完不复位、发完还能自由切、
   * 选择器始终在，四个模式常驻可见。
   *
   * <p>此前是"首条消息发出即锁定"——选择器消失、换成只读标识 + 一个「换一个」出口，
   * 想换模式必须新开会话。那套约束建立在"换执行器会让历史里的 tool_calls 指向当前不存在的
   * 工具"之上，而 `JdbcSessionStore.loadHistory` 只回放 question/answer，故障并不存在（§7.3）。
   *
   * <p>再往前还有一次更糟的：发完 `pendingMode = undefined` 静默复位，用户追问时掉回普通对话
   * 而毫不知情。两个方向都不能再走回去，所以正反都钉住。
   */
  it('keeps the selected mode after sending and still allows switching', async () => {
    const wrapper = mount(ChatView, { global: { plugins: [createPinia()] } })

    expect(wrapper.findAll('.agent-option')).toHaveLength(4)
    await wrapper.findAll('.agent-option')[1].trigger('click')

    await wrapper.find('textarea').setValue('上个月的订单量是多少')
    await wrapper.find('form').trigger('submit')
    await flushPromises()

    // 不复位：选择器还在，数据分析仍然选中
    expect(wrapper.findAll('.agent-option')).toHaveLength(4)
    expect(useChatStore().agentKind).toBe('analytics')
    expect(wrapper.findAll('.agent-option')[1].classes()).toContain('active')

    // 不锁定：会话已经有 id 了，照样能换
    await wrapper.findAll('.agent-option')[2].trigger('click')
    expect(useChatStore().agentKind).toBe('research')

    // 再点当前模式＝取消，落回普通对话
    await wrapper.findAll('.agent-option')[2].trigger('click')
    expect(useChatStore().agentKind).toBe('chat')
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

    await wrapper.findAll('.agent-option')[2].trigger('click')
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

    await wrapper.findAll('.agent-option')[2].trigger('click')
    await wrapper.find('textarea').setValue('研究 Java 工程师就业趋势')
    await wrapper.find('form').trigger('submit')
    await flushPromises()

    expect(wrapper.get('.research-question').text()).toContain('研究 Java 工程师就业趋势')
    expect(wrapper.get('.research-loading').text()).toContain('深度研究进行中')
    // 提交已经完成（拿到了 taskId），不该继续锁住输入框——报告还在后台轮询，界面不该跟着卡死。
    expect(wrapper.find('.composer button').attributes('disabled')).toBeUndefined()
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

  /**
   * 回归测试：拖放的接收范围曾经只有"添加文件"按钮那一小条（~160×30px），不是整个输入区——
   * 用户很自然地会往下面那个大输入框拖，那里毫无反应，体感上就是"拖放根本不能用"。
   * 现在整个 .chat-bottom（工具栏 + 输入框）都是拖放目标。
   */
  it('accepts a dropped file anywhere in the composer, not just on the small "添加文件" button', async () => {
    vi.mocked(fileApi.upload).mockResolvedValue(
      { fileId: 1, fileName: 'brief.pdf', kind: 'TEXT', sizeBytes: 8, parsedTextLength: 3, routedToRag: false })
    const wrapper = mount(ChatView, { global: { plugins: [createPinia()] } })
    const file = new File(['hello'], 'brief.pdf', { type: 'application/pdf' })
    const dataTransfer = { files: [file], items: [{ webkitGetAsEntry: () => ({ isDirectory: false }) }] }

    // 故意丢在 textarea 上，而不是 FileUploadWidget 的按钮/文字——这正是用户会去尝试、
    // 之前完全没反应的地方。
    await wrapper.find('textarea').trigger('drop', { dataTransfer })
    await flushPromises()

    expect(fileApi.upload).toHaveBeenCalledWith(expect.any(String), file)
    expect(wrapper.text()).toContain('brief.pdf')
  })

  /**
   * 回归测试：之前"×"按钮只是把文件从本地列表里过滤掉，从没调用过任何后端接口——服务端那份
   * 记录一直都在，会话继续提问时模型和 RAG 检索照样能看到"已删除"的文件。现在必须先请求后端
   * 真正删除，删除成功后才从列表里消失；后端失败时要保留在列表里并提示错误，不能让用户以为
   * 删掉了、其实还在。
   */
  it('calls the backend to actually delete a file before removing it from the list, and keeps it if that fails', async () => {
    vi.mocked(fileApi.upload).mockResolvedValue(
      { fileId: 7, fileName: 'brief.pdf', kind: 'TEXT', sizeBytes: 8, parsedTextLength: 3, routedToRag: false })
    const wrapper = mount(ChatView, { global: { plugins: [createPinia()] } })
    const file = new File(['hello'], 'brief.pdf', { type: 'application/pdf' })
    await wrapper.find('textarea').trigger('drop', {
      dataTransfer: { files: [file], items: [{ webkitGetAsEntry: () => ({ isDirectory: false }) }] }
    })
    await flushPromises()
    expect(wrapper.text()).toContain('brief.pdf')

    vi.mocked(fileApi.remove).mockRejectedValueOnce(new Error('删除失败'))
    await wrapper.find('.attachments button').trigger('click')
    await flushPromises()
    expect(fileApi.remove).toHaveBeenCalledWith(7)
    expect(wrapper.text()).toContain('brief.pdf')
    expect(wrapper.text()).toContain('删除失败')

    vi.mocked(fileApi.remove).mockResolvedValueOnce(undefined)
    await wrapper.find('.attachments button').trigger('click')
    await flushPromises()
    expect(wrapper.text()).not.toContain('brief.pdf')
  })
})
