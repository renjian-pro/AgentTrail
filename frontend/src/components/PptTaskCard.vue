<script setup lang="ts">
import { computed, ref } from 'vue'
import { pptApi } from '../api/ppt-api'
import { saveBlob, toErrorMessage } from '../api/http'
import type { PptEntry } from '../stores/chat'

const props = defineProps<{ entry: PptEntry }>()
const busy = ref(false)
const answer = ref('')
const modifyMessage = ref('')
const cancelling = ref(false)
const modifying = ref(false)
// 历史记录中的旧 payload 可能存的是服务器磁盘路径；任务号才是稳定且可公开的下载凭证。
const taskView = computed(() => props.entry.task?.taskView ?? null)
const pipelineState = computed(() => taskView.value?.pipelineState ?? props.entry.task?.status)
const capabilities = computed(() => taskView.value?.capabilities)
const canDownload = computed(() => capabilities.value?.canDownload ?? pipelineState.value === 'SUCCESS')
const downloading = ref(false)

/**
 * 下载走带鉴权的 fetch，不是 `<a href>`。
 *
 * <p>这里曾经是一个普通链接 + `target="_blank"`，看起来最自然，但**浏览器导航带不上
 * Authorization 请求头**，而这套鉴权的 token 只存在 localStorage 里、只靠请求头送出。
 * 结果是每次点下载都在新标签页里拿到 `{"code":"AUTH_REQUIRED"}`，而且因为错误发生在
 * 另一个标签页，这张卡片上的错误提示一个字都不会显示——用户看到的是"生成成功但下载不了"。
 *
 * <p>把 token 拼进 query 也能让链接活过来，但那会让凭证进浏览器历史、服务端访问日志和
 * Referer，是拿一个鉴权漏洞换一个下载按钮。
 */
async function download() {
  if (!props.entry.task) return
  downloading.value = true
  props.entry.error = undefined
  try {
    const file = await pptApi.download(props.entry.task.taskId)
    saveBlob(file.blob, file.filename)
  } catch (failure) {
    props.entry.error = toErrorMessage(failure)
  } finally {
    downloading.value = false
  }
}

const labels: Record<string, string> = {
  INIT: '初始化', CLARIFY: '需求澄清', REQUIREMENT: '需求分析', SEARCH: '资料检索', TEMPLATE: '读取模板',
  OUTLINE: '生成大纲', SCHEMA: '编排页面', IMAGE: '生成配图', RENDER: '正在渲染', VERIFY: '校验产物',
  SUCCESS: '已完成', FAILED: '生成失败',
  AWAITING_INPUT: '需要补充信息', CANCELLED: '已取消'
}

/** 状态机判定需求不够清晰、停下来等人——不是错误，也不是"运行中"，是这张卡片唯一需要用户动手的状态。 */
const awaitingInput = computed(() => pipelineState.value === 'AWAITING_INPUT')
/** 等人的任务点"继续"是空转（后端 run() 见到 AWAITING_INPUT 立刻返回），不给这颗按钮。 */
const canResume = computed(() => capabilities.value?.canResume
  ?? (!!props.entry.task && pipelineState.value !== 'SUCCESS' && pipelineState.value !== 'CANCELLED'
    && pipelineState.value !== 'AWAITING_INPUT'))
const canCancel = computed(() => capabilities.value?.canCancel ?? canResume.value)
const canAnswer = computed(() => capabilities.value?.canAnswer ?? awaitingInput.value)
const canModify = computed(() => capabilities.value?.canModify ?? pipelineState.value === 'SUCCESS')

const statusText = computed(() => {
  const task = props.entry.task
  if (!task) return ''
  if (pipelineState.value === 'SUCCESS') return 'PPT 已生成，可以预览或下载。'
  if (awaitingInput.value) return '补充之后会从需求分析这一步继续，不会从头重来。'
  return taskView.value?.failure?.userMessage || task.errorMsg || '任务正在等待或运行中。'
})

/**
 * 轮询到任务不再自行推进为止。终止条件必须带上 AWAITING_INPUT——它既不是 SUCCESS 也没有
 * errorMsg，漏掉这一条就是一个永不退出的 1.5 秒定时循环。
 */
async function pollUntilSettled(taskId: number) {
  while (props.entry.task && pipelineState.value !== 'SUCCESS'
      && pipelineState.value !== 'AWAITING_INPUT' && pipelineState.value !== 'CANCELLED'
      && !props.entry.task.errorMsg) {
    await new Promise(resolve => setTimeout(resolve, 1500))
    try {
      props.entry.task = await pptApi.status(taskId)
    } catch (failure) {
      props.entry.error = toErrorMessage(failure)
      return
    }
  }
}

async function resume() {
  if (!props.entry.task) return
  const taskId = props.entry.task.taskId
  busy.value = true
  try {
    props.entry.task = await pptApi.resume(taskId)
    props.entry.error = undefined
  } catch (failure) {
    props.entry.error = toErrorMessage(failure)
    busy.value = false
    return
  }
  // /resume 和 /create 一样是提交即返回、后台跑——释放 busy 后靠轮询把卡片更新到最新 checkpoint，
  // 不占着"继续"按钮等整个状态机跑完。
  busy.value = false
  await pollUntilSettled(taskId)
}

/**
 * 回答澄清追问。后端把回答并回需求、把 checkpoint 推到 REQUIREMENT 再继续跑，
 * 所以这里和 resume 一样是"提交即返回 + 轮询"，不需要另一套协议。
 */
async function submitAnswer() {
  if (!props.entry.task || !answer.value.trim() || !canAnswer.value) return
  const taskId = props.entry.task.taskId
  busy.value = true
  try {
    props.entry.task = await pptApi.clarify(taskId, answer.value.trim())
    props.entry.error = undefined
    answer.value = ''
  } catch (failure) {
    props.entry.error = toErrorMessage(failure)
    return
  } finally {
    busy.value = false
  }
  await pollUntilSettled(taskId)
}

/** 取消是协作式的：后端先写 CANCEL_REQUESTED，状态机在边界安全停止。 */
async function cancel() {
  if (!props.entry.task || !canCancel.value) return
  cancelling.value = true
  try {
    props.entry.task = await pptApi.cancel(props.entry.task.taskId)
    props.entry.error = undefined
  } catch (failure) {
    props.entry.error = toErrorMessage(failure)
  } finally {
    cancelling.value = false
  }
}

/** 修改创建新版本，不覆盖当前成功版本；后端返回的新 taskView 会刷新卡片能力。 */
async function modify() {
  if (!props.entry.task || !modifyMessage.value.trim() || !canModify.value) return
  modifying.value = true
  try {
    props.entry.task = await pptApi.modify(props.entry.task.taskId, modifyMessage.value.trim())
    modifyMessage.value = ''
    props.entry.error = undefined
  } catch (failure) {
    props.entry.error = toErrorMessage(failure)
  } finally {
    modifying.value = false
  }
  await pollUntilSettled(props.entry.task.taskId)
}
</script>

<template>
  <section class="task-card">
    <header class="capability-question"><span>▣</span><div><small>PPT 生成需求</small><p>{{ entry.prompt }}</p></div></header>
    <div v-if="!entry.task" class="task-summary">
      <p class="eyebrow">PPT 生成</p>
      <h2>正在创建任务…</h2>
    </div>
    <template v-else>
      <div class="task-summary">
        <p class="eyebrow">任务 #{{ entry.task.taskId }}</p>
        <h2>{{ labels[pipelineState ?? ''] ?? pipelineState }}</h2>
        <p>{{ statusText }}</p>
        <div v-if="taskView" class="ppt-progress" aria-label="PPT 生成进度">
          <div class="ppt-progress-track"><span :style="{ width: `${taskView.progressPercent}%` }" /></div>
          <small>{{ taskView.currentStageLabel }} · {{ taskView.progressPercent }}%</small>
        </div>
      </div>
      <div class="task-actions">
        <button v-if="canResume" :disabled="busy" @click="resume">↻ 继续</button>
        <button v-if="canCancel" :disabled="cancelling" @click="cancel">{{ cancelling ? '正在取消…' : '取消' }}</button>
        <button v-if="canDownload" :disabled="downloading" @click="download">
          {{ downloading ? '正在下载…' : '↓ 下载 PPT' }}
        </button>
      </div>
      <section v-if="awaitingInput && canAnswer" class="clarification-card ppt-clarify">
        <p class="eyebrow">助手追问</p>
        <p class="clarify-question">{{ entry.task.clarifyingQuestion }}</p>
        <form class="clarify-form" @submit.prevent="submitAnswer">
          <textarea v-model="answer" :disabled="busy" rows="2"
              placeholder="补充一下这份 PPT 要讲什么、给谁看…" @keydown.enter.exact.prevent="submitAnswer" />
          <button type="submit" :disabled="busy || !answer.trim()">提交并继续</button>
        </form>
      </section>
      <section v-if="canModify" class="clarification-card ppt-clarify">
        <p class="eyebrow">基于当前版本修改</p>
        <form class="clarify-form" @submit.prevent="modify">
          <textarea v-model="modifyMessage" :disabled="modifying" rows="2"
              placeholder="例如：把第 2 页改成对比表…" />
          <button type="submit" :disabled="modifying || !modifyMessage.trim()">提交修改</button>
        </form>
      </section>
    </template>
    <p v-if="entry.error" class="error">{{ entry.error }}</p>
  </section>
</template>
