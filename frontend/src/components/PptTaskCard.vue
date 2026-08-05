<script setup lang="ts">
import { computed, ref } from 'vue'
import { pptApi } from '../api/ppt-api'
import { toErrorMessage } from '../api/http'
import type { PptEntry } from '../stores/chat'

const props = defineProps<{ entry: PptEntry }>()
const busy = ref(false)
// 历史记录中的旧 payload 可能存的是服务器磁盘路径；任务号才是稳定且可公开的下载凭证。
const downloadUrl = computed(() => props.entry.task?.status === 'SUCCESS'
  ? `/agent/v1/ppt/${props.entry.task.taskId}/download`
  : undefined)

const labels: Record<string, string> = {
  INIT: '初始化', REQUIREMENT: '需求分析', SEARCH: '资料检索', TEMPLATE: '读取模板',
  OUTLINE: '生成大纲', SCHEMA: '编排页面', IMAGE: '生成配图', RENDER: '正在渲染', SUCCESS: '已完成'
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
  while (props.entry.task && props.entry.task.status !== 'SUCCESS' && !props.entry.task.errorMsg) {
    await new Promise(resolve => setTimeout(resolve, 1500))
    try {
      props.entry.task = await pptApi.status(taskId)
    } catch (failure) {
      props.entry.error = toErrorMessage(failure)
      return
    }
  }
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
        <p>{{ entry.task.status === 'SUCCESS' ? 'PPT 已生成，可以预览或下载。' : entry.task.errorMsg || '任务正在等待或运行中。' }}</p>
      </div>
      <div class="task-actions">
        <button v-if="entry.task.status !== 'SUCCESS'" :disabled="busy" @click="resume">↻ 继续</button>
        <a v-if="downloadUrl" :href="downloadUrl" target="_blank">预览 / 下载 PPT ↗</a>
      </div>
    </template>
    <p v-if="entry.error" class="error">{{ entry.error }}</p>
  </section>
</template>
