<script setup lang="ts">
import { computed, ref } from 'vue'
import { pptApi } from '../api/ppt-api'
import { toErrorMessage } from '../api/http'
import type { PptEntry } from '../stores/chat'

const props = defineProps<{ entry: PptEntry }>()
const busy = ref(false)
const answer = ref('')
// 历史记录中的旧 payload 可能存的是服务器磁盘路径；任务号才是稳定且可公开的下载凭证。
const downloadUrl = computed(() => props.entry.task?.status === 'SUCCESS'
  ? `/agent/v1/ppt/${props.entry.task.taskId}/download`
  : undefined)

const labels: Record<string, string> = {
  INIT: '初始化', CLARIFY: '需求澄清', REQUIREMENT: '需求分析', SEARCH: '资料检索', TEMPLATE: '读取模板',
  OUTLINE: '生成大纲', SCHEMA: '编排页面', IMAGE: '生成配图', RENDER: '正在渲染', SUCCESS: '已完成',
  AWAITING_INPUT: '需要补充信息', CANCELLED: '已取消'
}

/** 状态机判定需求不够清晰、停下来等人——不是错误，也不是"运行中"，是这张卡片唯一需要用户动手的状态。 */
const awaitingInput = computed(() => props.entry.task?.status === 'AWAITING_INPUT')
/** 等人的任务点"继续"是空转（后端 run() 见到 AWAITING_INPUT 立刻返回），不给这颗按钮。 */
const canResume = computed(() => !!props.entry.task
  && props.entry.task.status !== 'SUCCESS' && props.entry.task.status !== 'CANCELLED'
  && !awaitingInput.value)

const statusText = computed(() => {
  const task = props.entry.task
  if (!task) return ''
  if (task.status === 'SUCCESS') return 'PPT 已生成，可以预览或下载。'
  if (awaitingInput.value) return '补充之后会从需求分析这一步继续，不会从头重来。'
  return task.errorMsg || '任务正在等待或运行中。'
})

/**
 * 轮询到任务不再自行推进为止。终止条件必须带上 AWAITING_INPUT——它既不是 SUCCESS 也没有
 * errorMsg，漏掉这一条就是一个永不退出的 1.5 秒定时循环。
 */
async function pollUntilSettled(taskId: number) {
  while (props.entry.task && props.entry.task.status !== 'SUCCESS'
      && props.entry.task.status !== 'AWAITING_INPUT' && !props.entry.task.errorMsg) {
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
  if (!props.entry.task || !answer.value.trim()) return
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
        <h2>{{ labels[entry.task.status] ?? entry.task.status }}</h2>
        <p>{{ statusText }}</p>
      </div>
      <div class="task-actions">
        <button v-if="canResume" :disabled="busy" @click="resume">↻ 继续</button>
        <a v-if="downloadUrl" :href="downloadUrl" target="_blank">预览 / 下载 PPT ↗</a>
      </div>
      <section v-if="awaitingInput" class="clarification-card ppt-clarify">
        <p class="eyebrow">助手追问</p>
        <p class="clarify-question">{{ entry.task.clarifyingQuestion }}</p>
        <form class="clarify-form" @submit.prevent="submitAnswer">
          <textarea v-model="answer" :disabled="busy" rows="2"
              placeholder="补充一下这份 PPT 要讲什么、给谁看…" @keydown.enter.exact.prevent="submitAnswer" />
          <button type="submit" :disabled="busy || !answer.trim()">提交并继续</button>
        </form>
      </section>
    </template>
    <p v-if="entry.error" class="error">{{ entry.error }}</p>
  </section>
</template>
