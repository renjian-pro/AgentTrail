<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import type { ResearchEntry } from '../stores/chat'
import { parseReport } from '../utils/markdown'

const props = defineProps<{ entry: ResearchEntry }>()

const reportBlocks = computed(() => parseReport(props.entry.result?.report ?? ''))
const elapsedSeconds = ref(0)
let timer: ReturnType<typeof setInterval> | undefined

function stopTimer() {
  if (timer) clearInterval(timer)
  timer = undefined
}

onMounted(() => {
  if (!props.entry.result && !props.entry.error) {
    timer = setInterval(() => { elapsedSeconds.value += 1 }, 1000)
  }
})
watch([() => props.entry.result, () => props.entry.error], ([result, error]) => {
  if (result || error) stopTimer()
})
onUnmounted(stopTimer)
</script>

<template>
  <section class="research-entry">
    <header class="research-question">
      <span>⌕</span>
      <div><small>深度研究问题</small><p>{{ entry.question }}</p></div>
    </header>
    <div v-if="!entry.result && !entry.error" class="research-loading">
      <div class="research-loader"><i /></div>
      <div class="research-loading-copy">
        <div class="research-status"><span /> 深度研究进行中 <time>{{ elapsedSeconds }} 秒</time></div>
        <h3>正在为这个问题建立可靠答案</h3>
        <p>服务端正在规划研究路径、检索多来源资料并交叉验证，完成后报告会自动出现在这里。</p>
        <div class="research-flow"><span>规划</span><i /><span>检索</span><i /><span>验证</span><i /><span>综合</span></div>
      </div>
    </div>
    <div v-else-if="entry.error" class="research-error"><b>研究请求未完成</b><p>{{ entry.error }}</p></div>
    <section v-else-if="entry.result?.needsClarification" class="clarification-card">
      <p class="eyebrow">需要补充信息</p>
      <h2>请先澄清研究范围</h2>
      <p>{{ entry.result.clarifyingQuestion }}</p>
    </section>
    <article v-else-if="entry.result" class="report">
      <p class="eyebrow">研究主题</p>
      <h2>{{ entry.result.researchTopic }}</h2>
      <section class="report-body">
        <template v-for="(block, index) in reportBlocks" :key="index">
          <component :is="block.level === 3 ? 'h3' : 'h4'" v-if="block.kind === 'heading'">{{ block.text }}</component>
          <p v-else-if="block.kind === 'paragraph'">{{ block.text }}</p>
          <ul v-else>
            <li v-for="item in block.items" :key="item">{{ item }}</li>
          </ul>
        </template>
      </section>
      <section v-if="entry.result.taskResults.length" class="research-sources">
        <h3>研究任务</h3>
        <details v-for="task in entry.result.taskResults" :key="task.taskId">
          <summary>{{ task.success ? '✓' : '!' }} {{ task.instruction }}</summary>
          <p v-if="task.success">{{ task.output }}</p>
          <p v-else class="error">{{ task.errorMessage }}</p>
        </details>
      </section>
    </article>
  </section>
</template>
