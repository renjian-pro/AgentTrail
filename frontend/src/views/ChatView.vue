<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { storeToRefs } from 'pinia'
import AttachedFileList from '../components/AttachedFileList.vue'
import CollapsibleChip from '../components/CollapsibleChip.vue'
import FileUploadWidget from '../components/FileUploadWidget.vue'
import MessageInput from '../components/MessageInput.vue'
import PptTaskCard from '../components/PptTaskCard.vue'
import ResearchReportCard from '../components/ResearchReportCard.vue'
import TodoProgressBar from '../components/TodoProgressBar.vue'
import { chatApi, streamChat } from '../api/chat-api'
import { fileApi, type AttachedFile } from '../api/file-api'
import { toErrorMessage } from '../api/http'
import { pptApi } from '../api/ppt-api'
import { researchApi } from '../api/research-api'
import { useChatStore, type ChatTurn, type PptEntry, type ResearchEntry } from '../stores/chat'

/** 三种能力共用一个入口：默认发送走普通对话，先选中下面的模式再发送才会触发 Deep Research / PPT。 */
type Mode = 'research' | 'ppt' | undefined

const chat = useChatStore()
const { conversationId, messages, todos } = storeToRefs(chat)
const busy = ref(false)
const error = ref('')
const webSearch = ref(false)
const modelId = ref('qwen-plus')
const files = ref<AttachedFile[]>([])
const uploadBusy = ref(false)
const uploadError = ref('')
const pendingMode = ref<Mode>(undefined)
let aborter: AbortController | undefined

// Only the chat SSE owns an AbortController. Synchronous Research/PPT calls
// cannot be cancelled by the chat stop endpoint, so showing that control there
// would promise an action the backend cannot perform.
const canStop = computed(() => busy.value && aborter !== undefined)
const modeLabel = computed(() => pendingMode.value === 'research' ? 'Deep Research' : pendingMode.value === 'ppt' ? 'PPT 生成' : '')

function toggleMode(mode: Mode) {
  pendingMode.value = pendingMode.value === mode ? undefined : mode
}

async function send(message: string) {
  if (pendingMode.value === 'research') return runResearch(message)
  if (pendingMode.value === 'ppt') return runPpt(message)

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
      webSearchEnabled: webSearch.value
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

/** Research 和 PPT 共享同一套忙碌态/会话兜底/错误处理流程，只有发起调用的方式不同。 */
async function runCapability(seedTitle: string, entry: ResearchEntry | PptEntry, invoke: (conversationId: string) => Promise<void>) {
  pendingMode.value = undefined
  busy.value = true
  chat.ensureConversation(seedTitle)
  messages.value.push(entry)
  try {
    await invoke(conversationId.value!)
  } catch (failure) {
    entry.error = toErrorMessage(failure)
  } finally {
    busy.value = false
  }
}

async function runResearch(question: string) {
  const entry = reactive<ResearchEntry>({ kind: 'research', question })
  await runCapability(question, entry, async id => { entry.result = await researchApi.run(id, question) })
}

async function runPpt(prompt: string) {
  const entry = reactive<PptEntry>({ kind: 'ppt', prompt })
  await runCapability(prompt, entry, async id => { entry.task = await pptApi.create(id, prompt) })
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
            <p>{{ message.content || (busy && message.role === 'assistant' ? '正在思考…' : '') }}</p>
            <CollapsibleChip v-if="message.think" label="Thought" :content="message.think" />
            <CollapsibleChip v-for="tool in message.tools" :key="tool.toolCallId" :label="tool.name" :content="tool.detail" />
          </div>
        </article>
        <PptTaskCard v-else-if="message.kind === 'ppt'" :entry="message" />
        <ResearchReportCard v-else-if="message.kind === 'research'" :entry="message" />
      </template>
    </div>
    <TodoProgressBar :items="todos" />
    <div class="chat-bottom">
      <AttachedFileList :files="files" :busy="uploadBusy" :error="uploadError" @remove="files = files.filter(file => file.fileId !== $event)" />
      <div class="capability-bar">
        <div class="mode-picker">
          <button type="button" :disabled="busy" :class="{ active: pendingMode === 'research' }" @click="toggleMode('research')">⌕ 深度研究</button>
          <button type="button" :disabled="busy" :class="{ active: pendingMode === 'ppt' }" @click="toggleMode('ppt')">▣ 生成 PPT</button>
          <button type="button" :disabled="busy" :class="{ active: webSearch }" @click="webSearch = !webSearch">◎ 联网搜索</button>
        </div>
        <FileUploadWidget @upload="upload" />
      </div>
      <span v-if="pendingMode" class="mode-hint">下一条消息将使用 {{ modeLabel }}</span>
      <MessageInput :busy="busy" @send="send" />
      <div class="controls">
        <span>当前模型</span>
        <select v-model="modelId" aria-label="当前模型"><option>qwen-plus</option><option>deepseek-chat</option></select>
        <button v-if="canStop" class="stop" @click="stop">■ 停止生成</button>
      </div>
      <p v-if="error" class="error">对话出错：{{ error }}</p>
    </div>
  </div>
</template>
