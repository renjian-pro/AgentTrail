<script setup lang="ts">
import { computed, ref } from 'vue'
import { pptApi } from '../api/ppt-api'
import { saveBlob, toErrorMessage } from '../api/http'
import type { PptEntry } from '../stores/chat'

const props = defineProps<{ entry: PptEntry }>()
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
  INIT: '初始化', CLARIFY: '需求澄清', REQUIREMENT: '需求分析', SEARCH: '资料检索', VISUAL_PLAN: '视觉规划', TEMPLATE: '读取模板',
  OUTLINE: '生成大纲', SCHEMA: '编排页面', IMAGE: '生成配图', RENDER: '正在渲染', VERIFY: '校验产物',
  SUCCESS: '已完成', FAILED: '生成失败',
  AWAITING_INPUT: '需要补充信息', CANCELLED: '已取消'
}

/** 状态机判定需求不够清晰、停下来等人——不是错误，也不是"运行中"，是这张卡片唯一需要用户动手的状态。 */
const awaitingInput = computed(() => pipelineState.value === 'AWAITING_INPUT')
const canModify = computed(() => capabilities.value?.canModify ?? pipelineState.value === 'SUCCESS')
const progressEvents = computed(() => taskView.value?.progressEvents ?? [])
const timelineOpen = computed(() => pipelineState.value !== 'SUCCESS' && pipelineState.value !== 'CANCELLED')

const statusText = computed(() => {
  const task = props.entry.task
  if (!task) return ''
  if (pipelineState.value === 'SUCCESS') return 'PPT 已生成，可以下载；继续在下方输入框发送修改要求。'
  if (awaitingInput.value) return '请直接在下方会话输入框回复，补充后会继续当前任务。'
  if (pipelineState.value === 'CANCELLED') return '任务已取消；在下方输入新主题可以重新生成。'
  return taskView.value?.failure?.userMessage || task.errorMsg || '任务正在等待或运行中。'
})

const interactionHint = computed(() => {
  if (awaitingInput.value) return '请直接在下方会话输入框回复上面的追问。'
  if (canModify.value) return '例如：第二页换成流程图。请直接在下方会话输入框发送。'
  return '可在下方会话输入框发送“取消”或“继续生成”。'
})

function progressMark(status: string) {
  if (status === 'COMPLETED') return '✓'
  if (status === 'WARNING') return '!'
  if (status === 'FAILED') return '×'
  if (status === 'CANCELLED') return '■'
  return '●'
}
</script>

<template>
  <section class="task-card">
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
        <button v-if="canDownload" :disabled="downloading" @click="download">
          {{ downloading ? '正在下载…' : '↓ 下载 PPT' }}
        </button>
      </div>
      <details v-if="taskView" class="ppt-timeline" :open="timelineOpen">
        <summary><span>生成过程</span><small>{{ progressEvents.length }} 条 · {{ taskView.progressPercent }}%</small></summary>
        <ol v-if="progressEvents.length">
          <li v-for="event in progressEvents" :key="event.sequence"
              :class="[`is-${event.status.toLowerCase()}`, `is-${event.level.toLowerCase()}`]">
            <span class="ppt-event-mark">{{ progressMark(event.status) }}</span>
            <span>{{ event.message }}</span>
          </li>
        </ol>
        <p v-else class="ppt-timeline-empty">{{ taskView.currentStageLabel }}</p>
      </details>
      <section v-if="interactionHint" class="ppt-conversation-hint" aria-live="polite">
        <b>{{ awaitingInput ? '需要补充主题' : '继续操作' }}</b>
        <span>{{ interactionHint }}</span>
      </section>
    </template>
    <p v-if="entry.error" class="error">{{ entry.error }}</p>
  </section>
</template>
