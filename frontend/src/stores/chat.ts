import { computed, reactive, ref } from 'vue'
import { defineStore } from 'pinia'
import { chatApi, streamApproval, type ConversationSummary, type HistoryTurn, type PendingApprovalResponse } from '../api/chat-api'
import { toErrorMessage } from '../api/http'
import type { PptTask } from '../api/ppt-api'
import type { DeepResearchReport, ResearchStep } from '../api/research-api'
import type { StreamEvent, TodoItem } from '../types/stream-event'

/** 三种能力共用同一条时间线，靠 kind 区分渲染方式——不再是三个独立页面。 */
export type ChatTurn = {
  kind: 'chat'
  role: 'user' | 'assistant'
  content: string
  think?: string
  tools?: { name: string; toolCallId: string; detail: string; argumentsText: string; result?: string }[]
  approval?: ApprovalCardState
}
export type PptEntry = { kind: 'ppt'; prompt: string; task?: PptTask; error?: string }
export type ResearchEntry = {
  kind: 'research'
  question: string
  result?: DeepResearchReport
  currentStep?: ResearchStep | null
  error?: string
}
/**
 * 普通对话里问了数据问题时插进流里的引导卡片（issue #94）。没有任何负载——它只是一个位置标记，
 * 文案和动作都在 `SwitchAgentHint.vue` 里。不落库，刷新后不会重建，这是有意的：它是当下的引导，
 * 不是对话内容的一部分。
 */
export type SwitchHintEntry = { kind: 'switch-hint' }
export type ChatMessage = ChatTurn | PptEntry | ResearchEntry | SwitchHintEntry

export type ChatSession = { id: string; title: string }
export type ApprovalStatus = 'pending' | 'submitting' | 'approved' | 'rejected' | 'failed'
export type ApprovalCardState = PendingApprovalResponse & {
  status: ApprovalStatus
  decision?: 'approved' | 'rejected'
  error?: string
}
export type ApprovalState = ApprovalCardState & {
  assistant: ChatTurn
}

/**
 * 当前选中的能力模式。四者互斥，同一时刻只能选一个（`docs/requirements.md` §7.2）。
 *
 * <p><b>轮次级，不是会话级</b>——一个会话里可以自由切换，选中后一直保持，直到用户手动
 * 取消（切回 `chat`）或换成另一个。绝不因为发送了一条消息就复位：那条静默失败链路
 * （用户以为在数据分析、模型却在编数据）是 issue #92 堵掉的，不能倒回去。
 *
 * <p><b>为什么会话内可以自由切</b>：曾经的推导是"换模式＝换执行器 → 消息历史里的 tool_calls
 * 会指向当前不存在的工具 → 工具集必须会话内稳定"，据此做成了会话级绑定 + 首条消息后锁定。
 * 2026-08-17 核对实现后发现第二步不成立：`JdbcSessionStore.loadHistory` 只 SELECT
 * `question`/`answer` 两列，`timeline` 从不读回，跨轮历史里压根没有 tool_calls 结构。
 * 工具集的稳定作用域天然就是"一轮"，锁定是多余约束（§7.3）。
 *
 * <p><b>`research`/`ppt` 的执行协议不同</b>：它们不走 `/agent/v1/chat` 的 SSE 流，而是各自
 * 创建异步任务再轮询（见 ChatView 的 runResearch/runPpt）。交互上是同一排模式，协议上不是
 * 同一条链路——入口统一不等于协议统一。
 */
export type AgentKind = 'chat' | 'analytics' | 'research' | 'ppt'

/**
 * 模式的图标和名字，**选择器和侧栏共用这一份**。
 *
 * <p>此前侧栏是内联的三元 `=== 'analytics' ? '⌁' : '○'`，而名字/图标另在模式选择器里写了
 * 一遍。从两个模式扩到四个时，那个三元不会报错，只会把深度研究和 PPT 静默显示成普通对话——
 * 恰恰是"同一件事写在两处"最典型的失效方式：一处改了，另一处继续无声地给出错误答案。
 */
/** `chat` 这两项只有侧栏在用：模式选择器不给默认态排按钮（见 ChatView 的 MODE_KINDS）。 */
export const AGENT_KIND_MARKS: Record<AgentKind, string> = {
  chat: '○', analytics: '⌁', research: '⌕', ppt: '▣'
}
export const AGENT_KIND_LABELS: Record<AgentKind, string> = {
  chat: '普通对话', analytics: '数据分析', research: '深度研究', ppt: '生成 PPT'
}

const STORAGE_KEY = 'agenttrail.chat-sessions'
/**
 * `conversationId → AgentKind` 的本地映射。为什么单独存一份而不是塞进 `ChatSession`：
 * `hydrateSessions()` 会用服务端返回的列表整体覆盖 `sessions`，而服务端不知道 Agent 这个概念
 * （F6 明确不改后端），挂在 ChatSession 上每次刷新都会丢。
 */
const AGENT_KIND_STORAGE_KEY = 'agenttrail.chat-agent-kinds'
const TITLE_MAX_LENGTH = 60

/** 读不出/解析失败一律当空表——存量会话按普通对话处理是本票有意识的取舍（见 spec 3.4）。 */
function readAgentKinds(): Record<string, AgentKind> {
  const raw = localStorage.getItem(AGENT_KIND_STORAGE_KEY)
  if (!raw) return {}
  try {
    const parsed = JSON.parse(raw) as Record<string, AgentKind>
    return parsed && typeof parsed === 'object' ? parsed : {}
  } catch {
    localStorage.removeItem(AGENT_KIND_STORAGE_KEY)
    return {}
  }
}

/** 侧栏标题永远单行截断显示，但防线不能只放在 CSS 里——曾经真实发生过内部编排子提示词
 *（几千到上万字）被当成"问题"落库当标题，撑爆整个侧栏布局，这里从数据源头也截一刀。 */
function truncateTitle(title: string): string {
  const trimmed = title.trim()
  return trimmed.length > TITLE_MAX_LENGTH ? trimmed.slice(0, TITLE_MAX_LENGTH) + '…' : trimmed
}

function toChatSession(session: ConversationSummary): ChatSession {
  return { id: session.conversationId, title: truncateTitle(session.title) }
}

function readStoredSessions(): ChatSession[] {
  const raw = localStorage.getItem(STORAGE_KEY)
  if (!raw) return []
  try {
    const parsed = JSON.parse(raw) as ChatSession[]
    return parsed.filter(item => item.id && item.title)
  } catch {
    localStorage.removeItem(STORAGE_KEY)
    return []
  }
}

/** 对话状态由侧栏和 ChatView 共同消费，不能留在任一组件的本地 ref 中。 */
export const useChatStore = defineStore('chat', () => {
  const conversationId = ref<string>()
  const messages = ref<ChatMessage[]>([])
  const todos = ref<TodoItem[]>([])
  const sessions = ref<ChatSession[]>([])
  const sessionPage = ref(0)
  const sessionsHasMore = ref(false)
  // conversationId 也会在一次进行中的流式请求里自己变化——AgentStart 事件把它从 undefined
  // 改成服务端刚分配的真实 id（见 acceptConversation）。ChatView 需要区分"用户主动切换/新建
  // 会话"（该中断上一个会话遗留的请求、重置 busy）和"当前这次请求自己刚拿到会话号"（不该把
  // 自己的请求当成"别人的"中断掉）——只 watch conversationId 分不清这两种情况，这个计数器
  // 只在 startNewConversation/openSession 这两个明确的"用户导航"入口才自增。
  const navigationSeq = ref(0)
  const agentKind = ref<AgentKind>('chat')
  const agentKinds = ref<Record<string, AgentKind>>(readAgentKinds())
  const pendingApproval = ref<ApprovalState>()
  const hasPendingApproval = computed(() => pendingApproval.value !== undefined)

  function persistSessions() {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(sessions.value))
  }

  function persistAgentKind(id: string, kind: AgentKind) {
    agentKinds.value = { ...agentKinds.value, [id]: kind }
    localStorage.setItem(AGENT_KIND_STORAGE_KEY, JSON.stringify(agentKinds.value))
  }

  /**
   * 模式随时可切，没有锁定。此前这里有一道 `if (agentLocked) return` 的静默忽略，
   * 是"首条消息后锁定"那套会话级绑定的一部分，已随 §7.3 的重新推导拆掉。
   *
   * <p>已经有会话号时立刻落一次本地映射——否则在一个已存在的会话里换模式，刷新之后
   * 侧栏还显示旧模式（`acceptConversation` 只在会话号首次分配那一刻写过一次）。
   */
  function setAgentKind(kind: AgentKind) {
    agentKind.value = kind
    if (conversationId.value) persistAgentKind(conversationId.value, kind)
  }

  /**
   * 侧栏会话列表用：未知会话（含全部存量会话）一律普通对话。
   *
   * <p><b>一个会话只记一个模式，记的是最后一次用过的那个</b>——模式改成轮次级之后，
   * 一个会话里可以有多种模式的轮次，这个映射表达不了。真正的按轮归属要等后端
   * `agent_session.mode` 落库（R13 的后端那一半，尚未做）；在那之前侧栏图标只是个近似。
   */
  function agentKindFor(id: string): AgentKind {
    return agentKinds.value[id] ?? 'chat'
  }

  function acceptConversation(id: string, title: string) {
    conversationId.value = id
    // 会话号是这一刻才拿到的，绑定关系必须在这里落地，否则刷新后就丢了
    persistAgentKind(id, agentKind.value)
    const safeTitle = truncateTitle(title)
    const existing = sessions.value.find(session => session.id === id)
    if (existing) {
      existing.title = safeTitle || existing.title
    } else {
      sessions.value.unshift({ id, title: safeTitle || '新对话' })
    }
    persistSessions()
  }

  /** 新会话一律从普通对话开始（spec 3.3：零摩擦，数据分析靠引导卡片进入）。 */
  function startNewConversation() {
    conversationId.value = undefined
    messages.value = []
    todos.value = []
    agentKind.value = 'chat'
    pendingApproval.value = undefined
    navigationSeq.value++
  }

  /** Research/PPT/文件问答没有 AgentStart 事件分配会话号，需要在首次调用时自己兜底。 */
  function ensureConversation(seedTitle: string) {
    if (!conversationId.value) acceptConversation(crypto.randomUUID(), seedTitle)
  }

  /** 把聊天 SSE 的语义收敛在 store 里，ChatView 只负责驱动流式迭代和忙碌/错误态 UI。 */
  function applyStreamEvent(event: StreamEvent, assistant: ChatTurn, seedTitle: string): string | undefined {
    if (pendingApproval.value?.status === 'submitting'
        && event.type !== 'Paused' && event.type !== 'RunFailed' && event.type !== 'RunCompleted') {
      pendingApproval.value.status = pendingApproval.value.decision === 'approved' ? 'approved' : 'rejected'
    }
    switch (event.type) {
      case 'RunStarted':
        acceptConversation(event.conversationId, seedTitle)
        return undefined
      case 'ModelDelta':
        assistant.content += event.content
        return undefined
      case 'ThinkingDelta':
        assistant.think = (assistant.think ?? '') + event.content
        return undefined
      case 'ToolStarted':
        assistant.tools?.push({ name: event.toolName, toolCallId: event.toolCallId, detail: event.arguments, argumentsText: event.arguments })
        return undefined
      case 'ToolCompleted': {
        const tool = assistant.tools?.find(item => item.toolCallId === event.toolCallId)
        if (tool) {
          tool.result = event.result
          tool.detail = tool.detail + '\n\n结果：' + event.result
        }
        return undefined
      }
      case 'RunFailed':
        if (pendingApproval.value) {
          pendingApproval.value.status = 'failed'
          pendingApproval.value.error = event.message
        }
        return event.message
      case 'Paused':
        restorePendingApproval({
          conversationId: event.conversationId,
          reason: event.reason,
          pausedAtMillis: Date.now(),
          pendingTools: event.pendingTools,
          safePoint: 'BEFORE_TOOL_EXECUTION'
        }, assistant)
        return undefined
      case 'RunCompleted':
        if (pendingApproval.value) {
          pendingApproval.value.status = pendingApproval.value.decision === 'rejected' ? 'rejected' : 'approved'
          pendingApproval.value.error = undefined
        }
        pendingApproval.value = undefined
        return undefined
    }
  }

  function restorePendingApproval(snapshot: PendingApprovalResponse, assistant: ChatTurn) {
    const state: ApprovalState = { ...snapshot, status: 'pending', assistant }
    assistant.approval = state
    pendingApproval.value = state
  }

  /** 返回 false 表示请求已经在提交，调用方必须据此抑制重复点击。 */
  function beginApproval(approved: boolean): boolean {
    if (!pendingApproval.value || pendingApproval.value.status === 'submitting'
        || pendingApproval.value.status === 'approved' || pendingApproval.value.status === 'rejected') return false
    pendingApproval.value.status = 'submitting'
    pendingApproval.value.decision = approved ? 'approved' : 'rejected'
    pendingApproval.value.error = undefined
    return true
  }

  function failApproval(message: string) {
    if (!pendingApproval.value) return
    pendingApproval.value.status = 'failed'
    pendingApproval.value.error = message
  }

  /**
   * 审批恢复仍是同一轮 assistant 输出：这里持有暂停时的 assistant 引用，并把恢复 SSE 继续
   * 归并进去。组件不自行拼事件，避免普通发送和审批恢复形成两套逐渐漂移的协议实现。
   */
  async function submitApproval(approved: boolean, rejectionReason?: string,
      signal?: AbortSignal): Promise<string | undefined> {
    // AFTER 只代表批准/拒绝/跳过这一工具阶段已经处理；刷新后只能继续生成，不能再提交第二次决定。
    const effectiveApproval = pendingApproval.value?.safePoint === 'AFTER_TOOL_EXECUTION' ? true : approved
    if (!beginApproval(effectiveApproval)) return undefined
    const approval = pendingApproval.value!
    let failureMessage: string | undefined
    try {
      for await (const event of streamApproval(approval.conversationId,
          { approved: effectiveApproval, rejectionReason: effectiveApproval ? undefined : rejectionReason }, signal)) {
        failureMessage = applyStreamEvent(event, approval.assistant, '') ?? failureMessage
      }
    } catch (failure) {
      if ((failure as Error).name === 'AbortError') throw failure
      failureMessage = toErrorMessage(failure)
      failApproval(failureMessage)
    }
    return failureMessage
  }

  function turnToMessages(turns: HistoryTurn[]): ChatMessage[] {
    return turns.flatMap<ChatMessage>(turn => {
      let tools: ChatTurn['tools']
      if (turn.timeline) {
        try {
          const timeline = JSON.parse(turn.timeline) as Array<{
            type?: string
            stage?: string
            data?: { payload?: DeepResearchReport | PptTask; error?: string | null }
            toolName?: string
            toolCallId?: string
            arguments?: string
            result?: string
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
          // 普通对话的 timeline 只存工具调用轨迹（ToolCall 条目）——按落库时同一份数据重建出
          // 和直播时一样的 detail/argumentsText/result 形状，折叠卡片才能在历史回放里对得上。
          if (Array.isArray(timeline)) {
            const toolCalls = timeline
              .filter(event => event.type === 'ToolCall')
              .map(event => ({
                name: event.toolName ?? '',
                toolCallId: event.toolCallId ?? '',
                argumentsText: event.arguments ?? '',
                detail: event.result ? `${event.arguments ?? ''}\n\n结果：${event.result}` : (event.arguments ?? ''),
                result: event.result
              }))
            if (toolCalls.length > 0) tools = toolCalls
          }
        } catch {
          // 老版本或格式异常的 timeline 解析不出结构化内容，继续按标准问答重建。
        }
      }
      return [
        { kind: 'chat' as const, role: 'user' as const, content: turn.question },
        {
          kind: 'chat' as const,
          role: 'assistant' as const,
          content: turn.answer ?? '',
          think: turn.think ?? undefined,
          tools
        }
      ]
    })
  }

  async function openSession(id: string) {
    // 首轮就在工具前暂停时还没有 agent_session；暂停快照和历史必须并行查询，只要任一存在，
    // 这个会话入口就有效，不能让 history 的 404 把随后可成功的 pause 查询短路掉。
    const [historyResult, pauseResult] = await Promise.allSettled([
      chatApi.history(id), chatApi.getPendingApproval(id)
    ])
    if (historyResult.status === 'rejected' && pauseResult.status === 'rejected') throw historyResult.reason
    const page = historyResult.status === 'fulfilled'
      ? historyResult.value
      : { conversationId: id, page: 0, size: 20, hasMore: false, turns: [] }
    conversationId.value = id
    // 切回历史会话要恢复它自己的 Agent，不能沿用上一个会话的——否则在分析会话里点开一个
    // 普通会话，界面会显示"数据分析"而请求实际走普通对话
    agentKind.value = agentKindFor(id)
    messages.value = turnToMessages(page.turns)
    todos.value = []
    pendingApproval.value = undefined
    navigationSeq.value++
    if (!sessions.value.some(session => session.id === id)) {
      acceptConversation(id, page.turns.at(0)?.question ?? '新对话')
    }
    if (pauseResult.status === 'fulfilled') {
      const paused = pauseResult.value
      const assistant = reactive<ChatTurn>({ kind: 'chat', role: 'assistant', content: '', tools: [] })
      messages.value.push(assistant)
      restorePendingApproval(paused, assistant)
    }
  }

  async function hydrateSessions() {
    const stored = readStoredSessions()
    try {
      const page = await chatApi.sessions()
      const serverSessions = page.sessions.map(toChatSession)
      const serverIds = new Set(serverSessions.map(session => session.id))
      const missingLocal = stored.filter(session => !serverIds.has(session.id))
      // 服务端正常返回时只保留确实仍有暂停快照的本地入口，避免把已删除的普通旧会话复活。
      const pausedLocal = (await Promise.all(missingLocal.map(async session => {
        try {
          await chatApi.getPendingApproval(session.id)
          return session
        } catch {
          return undefined
        }
      }))).filter((session): session is ChatSession => session !== undefined)
      sessions.value = [...pausedLocal, ...serverSessions]
      sessionPage.value = page.page
      sessionsHasMore.value = page.hasMore
      persistSessions()
    } catch {
      // 服务暂不可用时仍保留上次已知的会话入口；恢复后下一次刷新会重新以服务端为准。
      sessions.value = stored
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
    conversationId, messages, todos, sessions, sessionsHasMore, navigationSeq,
    agentKind, agentKinds,
    pendingApproval, hasPendingApproval,
    acceptConversation, ensureConversation, applyStreamEvent, setAgentKind, agentKindFor,
    restorePendingApproval, beginApproval, failApproval, submitApproval,
    startNewConversation, openSession, hydrateSessions, loadMoreSessions
  }
})
