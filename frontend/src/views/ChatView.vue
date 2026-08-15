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
import TodoProgressBar from '../components/TodoProgressBar.vue'
import { chatApi, streamChat } from '../api/chat-api'
import { fileApi, type AttachedFile } from '../api/file-api'
import { toErrorMessage } from '../api/http'
import { pptApi, type PptTask } from '../api/ppt-api'
import { researchApi, type ResearchTask } from '../api/research-api'
import { renderMarkdown } from '../utils/renderMarkdown'
import { chartImageUrl } from '../utils/chartResult'
import { resolveDroppedFile } from '../utils/fileDrop'
import { useChatStore, type ChatTurn, type PptEntry, type ResearchEntry } from '../stores/chat'

/**
 * 三种能力共用一个入口：默认发送走普通对话，先选中下面的模式再发送才会触发对应能力。
 * research/ppt 各自另起一条同步创建任务 + 轮询的链路（见 runResearch/runPpt），提交后立即
 * 解锁 busy；analytics 不脱离聊天的 SSE 流本身——只是随消息带上 mode:'analytics'，
 * 让后端把这一轮切到数据分析执行器（见 send() 里的 streamChat 调用）。
 */
type Mode = 'research' | 'ppt' | 'analytics' | undefined

const chat = useChatStore()
const { conversationId, messages, todos, navigationSeq } = storeToRefs(chat)
const busy = ref(false)
const error = ref('')
const webSearch = ref(false)
const modelId = ref('qwen-plus')
const files = ref<AttachedFile[]>([])
const uploadBusy = ref(false)
const uploadError = ref('')
const composerDragging = ref(false)
const pendingMode = ref<Mode>(undefined)
const initialMessage = ref('')
let aborter: AbortController | undefined

onMounted(() => {
  if (typeof window === 'undefined' || window.location.pathname !== '/chat') return
  const params = new URLSearchParams(window.location.search)
  if (params.get('mode')?.toLowerCase() === 'analytics') pendingMode.value = 'analytics'
  initialMessage.value = params.get('q') ?? ''
})

// PPT/Research 提交后立即解锁 busy、靠轮询在后台推进（见 runPpt/runResearch），不会再让 busy
// 跨会话悬空；但普通聊天的 SSE 流仍然可能在切换会话那一刻正流着。ChatView 是同一个组件实例
// 跨会话复用的（路由没变，只是 conversationId 这个 store 里的值变了），不重置的话上一个会话
// 遗留的 busy/pendingMode/error 会继续锁住新会话的输入框和工具栏——这正是切换会话后 PPT 任务
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
  pendingMode.value = undefined
})

// Only the chat SSE owns an AbortController. Synchronous Research/PPT calls
// cannot be cancelled by the chat stop endpoint, so showing that control there
// would promise an action the backend cannot perform.
const canStop = computed(() => busy.value && aborter !== undefined)
const modeLabel = computed(() => pendingMode.value === 'research' ? 'Deep Research' : pendingMode.value === 'ppt' ? 'PPT 生成' : pendingMode.value === 'analytics' ? '数据分析' : '')

function toggleMode(mode: Mode) {
  pendingMode.value = pendingMode.value === mode ? undefined : mode
  // PPT 状态机和 DeepResearch 各自内部都无条件做自己的资料检索，都不接收/不使用这个开关——
  // 勾着它在这两种模式下纯粹是摆设，还会让人以为真的多做了一次联网搜索。数据分析同理：
  // forAnalytics 装配的执行器明确不挂联网搜索工具（DataAgent 不复用通用工具），开着这个
  // 开关同样只是摆设。
  if (pendingMode.value === 'ppt' || pendingMode.value === 'research' || pendingMode.value === 'analytics') {
    webSearch.value = false
  }
}

const TOOL_LABELS: Record<string, string> = {
  list_tables: '查看数据表',
  describe_tables: '展开表结构',
  lookup_glossary: '查询业务术语',
  calculate: '计算',
  tool_search: '查找可用工具'
}
const toolLabel = (name: string) => TOOL_LABELS[name] ?? name

async function send(message: string) {
  if (pendingMode.value === 'research') return runResearch(message)
  if (pendingMode.value === 'ppt') return runPpt(message)

  const mode = pendingMode.value
  pendingMode.value = undefined
  error.value = ''
  busy.value = true
  messages.value.push({ kind: 'chat', role: 'user', content: message })
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
      mode
    }, aborter.signal)) {
      const failureMessage = chat.applyStreamEvent(event, assistant, message)
      if (failureMessage) error.value = failureMessage
    }
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
  while (!isTerminal(task)) {
    await new Promise(resolve => setTimeout(resolve, intervalMs))
    task = await fetchStatus()
    onUpdate(task)
  }
}

async function runResearch(question: string) {
  pendingMode.value = undefined
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

async function runPpt(prompt: string) {
  pendingMode.value = undefined
  const entry = reactive<PptEntry>({ kind: 'ppt', prompt })
  chat.ensureConversation(prompt)
  messages.value.push(entry)
  busy.value = true
  let created: PptTask | undefined
  try {
    created = await pptApi.create(conversationId.value!, prompt)
    entry.task = created
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
      task => task.status === 'SUCCESS' || !!task.errorMsg,
      task => { entry.task = task })
  } catch (failure) {
    entry.error = toErrorMessage(failure)
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
          </div>
        </article>
        <PptTaskCard v-else-if="message.kind === 'ppt'" :entry="message" />
        <ResearchReportCard v-else-if="message.kind === 'research'" :entry="message" />
      </template>
    </div>
    <TodoProgressBar :items="todos" />
    <div class="chat-bottom" :class="{ dragging: composerDragging }"
        @dragover.prevent="composerDragging = true" @dragleave="composerDragging = false" @drop.prevent="onComposerDrop">
      <AttachedFileList :files="files" :busy="uploadBusy" :error="uploadError" @remove="removeFile" />
      <div class="capability-bar">
        <div class="mode-picker">
          <button type="button" :disabled="busy" :class="{ active: pendingMode === 'research' }" @click="toggleMode('research')">⌕ 深度研究</button>
          <button type="button" :disabled="busy" :class="{ active: pendingMode === 'ppt' }" @click="toggleMode('ppt')">▣ 生成 PPT</button>
          <button type="button" :disabled="busy" :class="{ active: pendingMode === 'analytics' }" @click="toggleMode('analytics')">⌁ 数据分析</button>
          <button type="button" :disabled="busy || pendingMode === 'ppt' || pendingMode === 'research' || pendingMode === 'analytics'"
              :title="pendingMode === 'ppt' || pendingMode === 'research' ? 'PPT 生成和深度研究都会自己做资料检索，这个开关对它们不生效' : pendingMode === 'analytics' ? '数据分析不挂载联网搜索工具，这个开关对它不生效' : ''"
              :class="{ active: webSearch }" @click="webSearch = !webSearch">◎ 联网搜索</button>
        </div>
        <FileUploadWidget @upload="upload" />
      </div>
      <span v-if="pendingMode" class="mode-hint">下一条消息将使用 {{ modeLabel }}</span>
      <MessageInput :busy="busy" :initial-value="initialMessage" @send="send" />
      <div class="controls">
        <span>当前模型</span>
        <select v-model="modelId" aria-label="当前模型"><option>qwen-plus</option><option>deepseek-chat</option></select>
        <button v-if="canStop" class="stop" @click="stop">■ 停止生成</button>
      </div>
      <p v-if="error" class="error">对话出错：{{ error }}</p>
    </div>
  </div>
</template>
