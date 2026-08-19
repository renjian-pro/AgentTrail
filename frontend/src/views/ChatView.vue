<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { storeToRefs } from 'pinia'
import AttachedFileList from '../components/AttachedFileList.vue'
import ChartToolCard from '../components/ChartToolCard.vue'
import CollapsibleChip from '../components/CollapsibleChip.vue'
import SqlToolCard from '../components/SqlToolCard.vue'
import FileUploadWidget from '../components/FileUploadWidget.vue'
import MessageInput from '../components/MessageInput.vue'
import PptTaskCard from '../components/PptTaskCard.vue'
import ResearchReportCard from '../components/ResearchReportCard.vue'
import SwitchAgentHint from '../components/SwitchAgentHint.vue'
import TodoProgressBar from '../components/TodoProgressBar.vue'
import ToolApprovalCard from '../components/ToolApprovalCard.vue'
import { chatApi, streamChat } from '../api/chat-api'
import { fileApi, type AttachedFile } from '../api/file-api'
import { toErrorMessage } from '../api/http'
import { pptApi, type PptTask } from '../api/ppt-api'
import { researchApi, type ResearchTask } from '../api/research-api'
import { renderMarkdown } from '../utils/renderMarkdown'
import { chartImageUrl } from '../utils/chartResult'
import { resolveDroppedFile } from '../utils/fileDrop'
import { looksLikeDataQuestion } from '../utils/dataQuestionHint'
import { AGENT_KIND_LABELS, AGENT_KIND_MARKS, useChatStore,
  type AgentKind, type ChatTurn, type PptEntry, type ResearchEntry } from '../stores/chat'

/**
 * 交互一层、协议两类（见 `docs/requirements.md` §7.2）。
 *
 * <p><b>交互层</b>：四个模式互斥，同一时刻只能选一个，会话内随时可切，由 store 的
 * `agentKind` 持有。选中后一直保持，不因发送而复位。
 *
 * <p><b>协议层</b>，`send()` 按模式分派到两条完全不同的链路：
 * <ul>
 *   <li>`chat` / `analytics` —— `/agent/v1/chat` 的 SSE 单次流，差别只在后端换执行器
 *   <li>`research` / `ppt` —— 各自创建异步任务再轮询（runResearch/runPpt），提交成功即解锁
 *       busy，报告/幻灯片在后台跑
 * </ul>
 * 协议不统一是刻意的：SSE 单次流承载不了"刷新页面还能看进度""断点续跑""产物下载"，PPT 已有的
 * checkpoint 机制不能为了入口统一而丢掉。这层映射就是下面这个 `send` 里的两行 if，不引入调度层。
 *
 * <p><b>为什么模式和文件/联网搜索同一排</b>：它们此前被拆成两处——模式在页面顶部的
 * AgentHeader 里，文件/联网搜索在输入框上方（issue #93 更早还有一版是"身份在头部、任务在
 * 输入框上方"）。那个分法的依据（换执行器 vs 异步任务）是后端事实，用户认知里"这条消息怎么发"
 * 只有一个维度，差异已经下沉到 `send` 的分派。现在整排都贴着输入框——用户的视线本来就在那里，
 * 而顶部那一行离要打字的地方最远、却挂着最需要在发送前确认的东西。AgentHeader 随之删除。
 *
 * <p><b>选中态即常驻标识</b>：不额外写"本次会话：XX"，chip 的高亮已经说清楚了。这一点是从
 * AgentHeader 继承来的、不能丢的性质——最早的实现只在切换时提示一行"下一条消息将使用 XX"，
 * 提示消失之后用户就不知道自己在哪个模式下了，叠加"发完静默复位"就是"用户以为在数据分析、
 * 模型却在编数据"那条静默失败链路（issue #92）。模式一直保持到用户显式切换，且**不锁定**：
 * 会话跑起来之后照样能改（跨轮历史只回放 question/answer，从不带 tool_calls，
 * 见 `JdbcSessionStore.loadHistory` 与 requirements.md §7.3）。
 */

const chat = useChatStore()
const { conversationId, messages, todos, navigationSeq, agentKind, hasPendingApproval } = storeToRefs(chat)
const busy = ref(false)
const error = ref('')
const webSearch = ref(false)
const modelId = ref('qwen-plus')
const files = ref<AttachedFile[]>([])
const uploadBusy = ref(false)
const uploadError = ref('')
const composerDragging = ref(false)
const initialMessage = ref('')
let aborter: AbortController | undefined

/** 输入框的占位文案随模式变，用户不用猜"这个模式下该写什么"。 */
const PLACEHOLDERS: Record<AgentKind, string> = {
  chat: '问我任何事',
  analytics: '问一个关于业务数据的问题',
  research: '描述你要研究的问题，生成深度研究报告',
  ppt: '输入你想创作的 PPT 主题'
}

onMounted(() => {
  if (typeof window === 'undefined' || window.location.pathname !== '/chat') return
  const params = new URLSearchParams(window.location.search)
  if (params.get('mode')?.toLowerCase() === 'analytics') chat.setAgentKind('analytics')
  initialMessage.value = params.get('q') ?? ''
})

// PPT/Research 提交后立即解锁 busy、靠轮询在后台推进（见 runPpt/runResearch），不会再让 busy
// 跨会话悬空；但普通聊天的 SSE 流仍然可能在切换会话那一刻正流着。ChatView 是同一个组件实例
// 跨会话复用的（路由没变，只是 conversationId 这个 store 里的值变了），不重置的话上一个会话
// 遗留的 busy/error 会继续锁住新会话的输入框和工具栏——这正是切换会话后 PPT 任务
// "消失"、界面卡死的根因。切会话时顺手把还挂着的 SSE 流也中断掉，没有理由让它继续跑。
//
// watch 的是 navigationSeq，不是 conversationId 本身——同一个 send() 请求成功后，服务端
// 分配的会话号会通过 AgentStart 事件把 conversationId 从 undefined 改写成真实值（见
// acceptConversation），这也是 conversationId 的一次变化，但那是"这次请求认领了它自己的会话
// 号"，不是"用户换了个会话"，不该被当成后者去中断请求自己。navigationSeq 只在
// startNewConversation/openSession 这两个真正的用户导航入口才自增，两种情况天然分得开。
watch(navigationSeq, () => {
  aborter?.abort()
  aborter = undefined
  busy.value = false
  error.value = ''
  if (conversationId.value) void restorePptTasks(conversationId.value)
})

// Only the chat SSE owns an AbortController. Synchronous Research/PPT calls
// cannot be cancelled by the chat stop endpoint, so showing that control there
// would promise an action the backend cannot perform.
const canStop = computed(() => busy.value && aborter !== undefined)
/** 暂停中的同一会话必须先做出审批决定，不能并行塞入一条新的普通消息破坏恢复快照。 */
const interactionBusy = computed(() => busy.value || hasPendingApproval.value)
/**
 * 联网搜索与模式**不正交**，四个模式各不相同（requirements.md §7.2 / R14a）：
 *
 * <ul>
 *   <li>`chat` —— 用户可开关，有隐私/成本权衡要留给用户
 *   <li>`analytics` —— 禁用。`forAnalytics(String modelId)` 根本不接 `webSearchEnabled` 参数，
 *       开着只是摆设。禁用理由常驻显示，不能只写 title（触屏没有 hover）
 *   <li>`research` / `ppt` —— **整个开关不渲染**。两者内部无条件做自己的资料检索
 *       （`SearchStrategy` 固定两个角度喂给 OUTLINE 状态；DeepResearch 本身就是检索驱动的），
 *       渲染成"已开启的开关"会暗示用户可以关掉，而关掉它们压根跑不起来
 * </ul>
 */
const webSearchApplies = computed(() => agentKind.value === 'chat' || agentKind.value === 'analytics')
const webSearchDisabled = computed(() => interactionBusy.value || agentKind.value === 'analytics')
/** 查表而不是链式 if：后者的第二个分支要靠"前一个分支已经排除了 analytics"才成立，
 *  等于把正确性押在两个 computed 的求值顺序上。和上面的 PLACEHOLDERS 同一种写法。 */
const BAR_NOTES: Partial<Record<AgentKind, string>> = {
  analytics: '数据分析不挂载联网搜索工具，这个开关对它不生效',
  research: '这个模式自带资料检索，不需要单独开联网搜索',
  ppt: '这个模式自带资料检索，不需要单独开联网搜索'
}
const barNote = computed(() => BAR_NOTES[agentKind.value] ?? '')

/**
 * 栏里排出来的模式，**不含 `chat`**：普通对话是"一个都没选"的默认态，不是第四种能力。
 * 给它单独立一颗按钮，用户会当成一种和数据分析并列的能力去点；而"回到普通对话"这件事，
 * 再点一次已选中的 chip 就做到了（见 `pickMode`），不需要第二个入口。
 *
 * <p>顺序：数据分析打头——这是当前要突出的主业务；其余按"换执行器 → 异步任务"排。
 * 写成显式数组而不是 `Object.keys(LABELS).filter(...)`：这里的顺序是产品决定，
 * 不该跟着 store 里那张表的键序漂。少一个模式编译器不会报错，但下面 `send` 的分派
 * 覆盖了全部四个，漏排一个只是不给入口，不会走到没人处理的分支。
 */
const MODE_KINDS: AgentKind[] = ['analytics', 'research', 'ppt']

function changeAgent(kind: AgentKind) {
  chat.setAgentKind(kind)
  if (kind !== 'chat') webSearch.value = false
}

/** 再点一次当前模式＝取消选择，落回普通对话——普通对话没有自己的按钮，这是它唯一的入口。 */
function pickMode(kind: AgentKind) {
  changeAgent(kind === agentKind.value ? 'chat' : kind)
}

/**
 * 引导卡片的动作：就地切到数据分析，**不再新开会话**。
 *
 * <p>此前这里是 `startNewConversation('analytics')`——那是会话级绑定时代的唯一出路（会话一旦
 * 锁定就换不了 Agent）。模式改成轮次级之后，就地切换是对的：用户刚问的那个数据问题还在上面，
 * 换个会话反而把它丢了。
 */
function switchToAnalytics() {
  changeAgent('analytics')
}

const TOOL_LABELS: Record<string, string> = {
  list_tables: '查看数据表',
  describe_tables: '展开表结构',
  lookup_glossary: '查询业务术语',
  calculate: '计算',
  tool_search: '查找可用工具'
}
const toolLabel = (name: string) => TOOL_LABELS[name] ?? name

/**
 * 唯一的发送入口，按当前模式分派到两条协议链路（见文件头部）。
 *
 * <p>**模式不在这里复位**——曾经这一行是 `pendingMode.value = undefined`，导致用户追问时
 * 静默掉回普通对话、模型没有数据库工具就去编数据（issue #92）。模式一直保持到用户显式切换。
 */
async function send(message: string) {
  if (hasPendingApproval.value) return
  error.value = ''
  // 异步任务链路：不推 user 气泡，问题/主题就在各自的卡片里，推一条只会重复显示。
  if (agentKind.value === 'research') return runResearch(message)
  if (agentKind.value === 'ppt') return runPpt(message)

  const mode = agentKind.value === 'analytics' ? 'analytics' : undefined
  busy.value = true
  messages.value.push({ kind: 'chat', role: 'user', content: message })
  // 卡片插在提问之后、回答之前：这条会话本来就没有数据库工具，等模型答完再提示就晚了——
  // 用户已经读到一个可能是编出来的数字了（issue #94）。
  if (agentKind.value === 'chat' && looksLikeDataQuestion(message)) {
    messages.value.push({ kind: 'switch-hint' })
  }
  // Keep the exact object mutated by the SSE loop reactive. Vue wraps objects read
  // through a reactive array, but mutating this original reference would otherwise
  // bypass that proxy and make the answer appear only when the stream completes.
  const assistant = reactive<ChatTurn>({ kind: 'chat', role: 'assistant', content: '', tools: [] })
  messages.value.push(assistant)
  aborter = new AbortController()

  try {
    for await (const event of streamChat({
      message,
      conversationId: conversationId.value,
      modelId: modelId.value,
      webSearchEnabled: webSearch.value,
      mode,
      // 显式带上这一轮附的文件（issue #110）——后端据此绑定，不再扫全会话
      fileIds: files.value.map(file => file.fileId)
    }, aborter.signal)) {
      const failureMessage = chat.applyStreamEvent(event, assistant, message)
      if (failureMessage) error.value = failureMessage
    }
    // 附件已经绑到这一轮了，输入框上的挂件该清空——它表达的是"下一条消息要带什么"。
    // 只在成功路径清：失败时保留着，用户重发一次就行，不用重新上传一遍。
    files.value = []
  } catch (failure) {
    if ((failure as Error).name !== 'AbortError') error.value = toErrorMessage(failure)
  } finally {
    busy.value = false
    aborter = undefined
  }
}

async function decideApproval(approved: boolean, rejectionReason?: string) {
  error.value = ''
  aborter = new AbortController()
  busy.value = true
  try {
    const failureMessage = await chat.submitApproval(approved, rejectionReason, aborter.signal)
    if (failureMessage) error.value = failureMessage
  } catch (failure) {
    if ((failure as Error).name !== 'AbortError') error.value = toErrorMessage(failure)
  } finally {
    busy.value = false
    aborter = undefined
  }
}

/**
 * 提交后反复轮询直到终态——PPT/DeepResearch 都是"提交即返回，后台跑"，这是唯一能看到进度
 * 推进的办法（PPT 每完成一个状态就有新 checkpoint 可看；DeepResearch 只有 RUNNING→终态一步跳）。
 * 不在 busy 里等它：调用方在提交成功后就该解锁 UI，轮询循环只管更新 entry 本身。
 */
async function pollUntilTerminal<T>(fetchStatus: () => Promise<T>, isTerminal: (task: T) => boolean,
    onUpdate: (task: T) => void, intervalMs = 1500): Promise<void> {
  let task = await fetchStatus()
  onUpdate(task)
  let currentInterval = intervalMs
  while (!isTerminal(task)) {
    await new Promise(resolve => setTimeout(resolve, currentInterval))
    task = await fetchStatus()
    onUpdate(task)
    // 后台任务通常几秒才推进一次；退避减少刷新/恢复页面时对服务端的无效压力。
    currentInterval = Math.min(currentInterval * 2, 10000)
  }
}

async function runResearch(question: string) {
  const entry = reactive<ResearchEntry>({ kind: 'research', question, currentStep: null })
  chat.ensureConversation(question)
  messages.value.push(entry)
  busy.value = true
  let created: ResearchTask | undefined
  try {
    created = await researchApi.run(conversationId.value!, question)
  } catch (failure) {
    entry.error = toErrorMessage(failure)
    return
  } finally {
    // 提交成功（或失败）这一刻就解锁——真正的报告在后台跑，不该占着输入框和工具栏。
    busy.value = false
  }
  applyResearchTask(entry, created)
  if (created.status !== 'RUNNING') return
  try {
    await pollUntilTerminal(
      () => researchApi.status(created!.taskId),
      task => task.status !== 'RUNNING',
      task => applyResearchTask(entry, task))
  } catch (failure) {
    entry.error = toErrorMessage(failure)
  }
}

function applyResearchTask(entry: ResearchEntry, task: ResearchTask) {
  entry.currentStep = task.currentStep ?? null
  if (task.status === 'SUCCESS') entry.result = task.report ?? undefined
  else if (task.status === 'FAILED') entry.error = task.errorMsg ?? '深度研究失败，请重试'
}

/**
 * 回答深度研究的澄清追问。走 {@code researchApi.reply} 而不是 {@code run}——后者会开一个从零开始的
 * 新研究，把原始问题和刚才那句追问一起丢掉，用户答了等于没答（这条链路此前就是这么断的：
 * 卡片把追问渲染出来了，但没有任何地方调 reply）。
 *
 * <p>复用同一个 entry 而不是新推一张卡片：这是同一次研究请求的延续，不是第二次提问。清空
 * result 让卡片自己翻回"研究中"那一屏，后面的轮询和首次发起共用 applyResearchTask。
 */
async function replyToResearch(entry: ResearchEntry, answer: string) {
  const clarifyingQuestion = entry.result?.clarifyingQuestion
  if (!clarifyingQuestion || !conversationId.value) return
  entry.result = undefined
  entry.error = undefined
  entry.currentStep = 'CLARIFYING'
  let created: ResearchTask | undefined
  try {
    created = await researchApi.reply(conversationId.value, entry.question, clarifyingQuestion, answer)
  } catch (failure) {
    entry.error = toErrorMessage(failure)
    return
  }
  applyResearchTask(entry, created)
  if (created.status !== 'RUNNING') return
  try {
    await pollUntilTerminal(
      () => researchApi.status(created!.taskId),
      task => task.status !== 'RUNNING',
      task => applyResearchTask(entry, task))
  } catch (failure) {
    entry.error = toErrorMessage(failure)
  }
}

async function runPpt(prompt: string) {
  const entry = reactive<PptEntry>({ kind: 'ppt', prompt })
  chat.ensureConversation(prompt)
  messages.value.push(entry)
  busy.value = true
  let created: PptTask | undefined
  try {
    created = await pptApi.create(conversationId.value!, prompt)
    applyPptTask(entry, created)
  } catch (failure) {
    entry.error = toErrorMessage(failure)
    return
  } finally {
    busy.value = false
  }
  if (created.status === 'SUCCESS' || created.errorMsg) return
  try {
    await pollUntilTerminal(
      () => pptApi.status(created!.taskId),
      // AWAITING_INPUT 也是终止条件：需求不够清晰时状态机停下来等用户在卡片里回答，
      // 它既不是 SUCCESS 也没有 errorMsg，漏掉就是一个永不退出的轮询。
      task => isPptTerminal(task),
      task => applyPptTask(entry, task))
  } catch (failure) {
    entry.error = toErrorMessage(failure)
  }
}

function pptRevision(task: PptTask | undefined): number | undefined {
  return task?.taskView?.revision
}

/** 轮询结果可能乱序返回；旧 revision 不能覆盖已经展示的新 checkpoint。 */
function applyPptTask(entry: PptEntry, incoming: PptTask) {
  const current = pptRevision(entry.task)
  const next = pptRevision(incoming)
  if (current !== undefined && next !== undefined && next < current) return
  entry.task = incoming
}

function isPptTerminal(task: PptTask) {
  return task.status === 'SUCCESS' || task.status === 'FAILED' || task.status === 'CANCELLED'
      || task.status === 'AWAITING_INPUT' || !!task.errorMsg
}

/**
 * 会话重新打开时恢复所有 PPT 卡片，并用批量接口补齐仍在运行的任务。
 * 这里不把整份上下文快照塞进前端，只消费后端的任务视图，避免泄露内部提示词和路径。
 */
async function restorePptTasks(id: string) {
  if (typeof pptApi.conversation !== 'function' || conversationId.value !== id) return
  try {
    const tasks = await pptApi.conversation(id)
    if (conversationId.value !== id) return
    const activeIds: number[] = []
    for (const task of tasks) {
      const existing = messages.value.find(message => message.kind === 'ppt'
          && message.task?.taskId === task.taskId) as PptEntry | undefined
      if (existing) applyPptTask(existing, task)
      else messages.value.push(reactive<PptEntry>({
        kind: 'ppt', prompt: `PPT 任务 #${task.taskId}`, task
      }))
      if (!isPptTerminal(task)) activeIds.push(task.taskId)
    }
    if (activeIds.length > 0 && typeof pptApi.batchStatus === 'function') {
      void pollPptBatch(id, activeIds)
    }
  } catch (failure) {
    // PPT 历史接口不可用时不阻断聊天历史；下次导航仍会再次尝试恢复。
    error.value = toErrorMessage(failure)
  }
}

async function pollPptBatch(id: string, taskIds: number[]) {
  let interval = 1500
  let pending = [...taskIds]
  while (pending.length > 0 && conversationId.value === id) {
    await new Promise(resolve => setTimeout(resolve, interval))
    try {
      const tasks = await pptApi.batchStatus(taskIds)
      pending = []
      for (const task of tasks) {
        const entry = messages.value.find(message => message.kind === 'ppt'
            && message.task?.taskId === task.taskId) as PptEntry | undefined
        if (entry) applyPptTask(entry, task)
        if (!isPptTerminal(task)) pending.push(task.taskId)
      }
      interval = Math.min(interval * 2, 10000)
    } catch (failure) {
      error.value = toErrorMessage(failure)
      return
    }
  }
}

async function stop() {
  aborter?.abort()
  if (conversationId.value) await chatApi.stop(conversationId.value)
  busy.value = false
}

async function upload(file: File) {
  chat.ensureConversation('文件问答')
  uploadBusy.value = true
  uploadError.value = ''
  try {
    files.value.push(await fileApi.upload(conversationId.value!, file))
  } catch (failure) {
    uploadError.value = toErrorMessage(failure)
  } finally {
    uploadBusy.value = false
  }
}

/** 整个底部输入区（工具栏 + 输入框）都是拖放目标，不只是"添加文件"按钮那一小条。 */
function onComposerDrop(event: DragEvent) {
  composerDragging.value = false
  const result = resolveDroppedFile(event)
  if (result.kind === 'rejected') uploadError.value = result.reason
  else if (result.kind === 'file') void upload(result.file)
}

/**
 * 之前"×"只是把文件从本地列表里过滤掉，服务端那份记录（以及大文件在向量库里的分块）
 * 从没被真的删过，继续提问时模型和 RAG 检索依然能看到它——删除只是前端幻觉。
 * 这里先调后端真正删除，成功了才从本地列表移除；失败则保留在列表里并提示错误，
 * 不能让用户以为删掉了、其实还在。
 */
async function removeFile(fileId: number) {
  uploadError.value = ''
  try {
    await fileApi.remove(fileId)
    files.value = files.value.filter(file => file.fileId !== fileId)
  } catch (failure) {
    uploadError.value = toErrorMessage(failure)
  }
}
</script>

<template>
  <div class="chat-view" :class="{ 'is-empty': !messages.length }">
    <div v-if="!messages.length" class="welcome">
      <span class="welcome-mark">✦</span>
      <h1>AgentTrail，我帮你</h1>
      <p>从一个想法开始，对话、研究、分析与演示都可以在这里完成。</p>
    </div>
    <div class="messages">
      <template v-for="(message, index) in messages" :key="index">
        <article v-if="message.kind === 'chat'" class="message-row" :class="message.role">
          <div class="avatar">{{ message.role === 'user' ? '你' : '✦' }}</div>
          <div class="bubble">
            <p v-if="message.role === 'user'">{{ message.content }}</p>
            <div v-else-if="message.content" class="markdown-body" v-html="renderMarkdown(message.content)" />
            <p v-else class="thinking-placeholder">{{ busy ? '正在思考…' : '' }}</p>
            <CollapsibleChip v-if="message.think" label="Thought" :content="message.think" />
            <template v-for="tool in message.tools" :key="tool.toolCallId">
              <SqlToolCard v-if="tool.name === 'execute_sql' || tool.name === 'validate_sql'" :name="tool.name" :arguments-text="tool.argumentsText" :result="tool.result" />
              <!-- 图表工具按结果形状认，不按工具名：mcp-echarts 动态挂着十几个工具名（见 chartResult.ts）。 -->
              <ChartToolCard v-else-if="chartImageUrl(tool.result)" :name="tool.name" :arguments-text="tool.argumentsText" :result="tool.result" />
              <CollapsibleChip v-else :label="toolLabel(tool.name)" :content="tool.detail" />
            </template>
            <ToolApprovalCard v-if="message.approval" :approval="message.approval" @decide="decideApproval" />
          </div>
        </article>
        <SwitchAgentHint v-else-if="message.kind === 'switch-hint'" @switch-to-analytics="switchToAnalytics" />
        <PptTaskCard v-else-if="message.kind === 'ppt'" :entry="message" />
        <ResearchReportCard v-else-if="message.kind === 'research'" :entry="message"
            @reply="answer => replyToResearch(message, answer)" />
      </template>
    </div>
    <TodoProgressBar :items="todos" />
    <div class="chat-bottom" :class="{ dragging: composerDragging }"
        @dragover.prevent="composerDragging = true" @dragleave="composerDragging = false" @drop.prevent="onComposerDrop">
      <AttachedFileList :files="files" :busy="uploadBusy" :error="uploadError" @remove="removeFile" />
      <!--
        一排到底：模式（数据分析/深度研究/生成 PPT）和附加项（文件、联网搜索）都在这里，
        因为它们回答的是同一个问题——"这条消息怎么发"。屏幕上不再有两组外观相同、作用域不同的
        按钮，那正是 issue #93 要解决的问题；解法不是靠"身份在上、动作在下"的位置约定去区分，
        而是根本不分两处。
      -->
      <div class="capability-bar">
        <FileUploadWidget @upload="upload" />
        <button v-for="kind in MODE_KINDS" :key="kind" type="button" class="agent-option"
            :class="{ active: kind === agentKind }" :aria-pressed="kind === agentKind" @click="pickMode(kind)">
          <i>{{ AGENT_KIND_MARKS[kind] }}</i>{{ AGENT_KIND_LABELS[kind] }}
        </button>
        <!-- 联网搜索排在模式 chip **之后**：它在 research/ppt 下整颗不渲染（见 webSearchApplies），
             排在前面的话每切一次模式，右边的 chip 就整体左移一格——刚点下去的那颗会从光标底下
             跑掉，下一颗顶上来。排在末尾，消失的是行尾，留着的按钮一个都不动。
             禁用理由必须看得见：原来只写在 title 里，触屏设备根本没有 hover，用户只看到一颗
             点不动的按钮，不知道是坏了还是不该用。理由现在由 .bar-notes 常驻显示，title 就是
             同一句话的第二个副本了——两处绑同一个 computed，只会各自漂移。 -->
        <button v-if="webSearchApplies" type="button" :disabled="webSearchDisabled"
            :class="{ active: webSearch }" @click="webSearch = !webSearch">◎ 联网搜索</button>
        <!-- 灰字说明统一放到这一排的末尾，不夹在按钮中间——那一排的意思就是"这些是同一类东西"，
             中间插一句说明会把它从视觉上切成两段。
             条件直接问 barNote 有没有话说，不问 webSearchDisabled：后者还包含 busy，
             而 busy 期间是没有理由可讲的——按那个条件走，普通会话每次生成都会让这格变成空白，
             "也可拖放文件"莫名其妙消失一次，原因和拖放毫无关系。 -->
        <span v-if="barNote" class="bar-notes">{{ barNote }}</span>
        <span v-else class="bar-notes drop-note">也可拖放文件</span>
      </div>
      <!-- 停止不再单独占一颗按钮：发送键本身在可中断时变成停止键（见 MessageInput 的 action）。
           两颗按钮同时在场时，用户要在"输入框右下角"和"模型选择那一排"之间挑一个，而它们
           从来不会同时可用——同一个位置换个状态就够了。 -->
      <MessageInput :busy="interactionBusy" :can-stop="canStop" :initial-value="initialMessage"
          :placeholder="PLACEHOLDERS[agentKind]" @send="send" @stop="stop" />
      <div class="controls">
        <span>当前模型</span>
        <select v-model="modelId" aria-label="当前模型"><option>qwen-plus</option><option>deepseek-chat</option></select>
      </div>
      <p v-if="error" class="error">对话出错：{{ error }}</p>
    </div>
  </div>
</template>
