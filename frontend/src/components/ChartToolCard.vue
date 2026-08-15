<script setup lang="ts">
import { computed, ref } from 'vue'
import { chartImageUrl } from '../utils/chartResult'

const props = defineProps<{ name: string; argumentsText: string; result?: string }>()
const broken = ref(false)
const source = computed(() => chartImageUrl(props.result))
// 图表工具的入参里带着 title，直接拿来当 alt——比统一写"图表"对读屏和加载失败时都更有信息量。
const title = computed(() => {
  try {
    return JSON.parse(props.argumentsText || '{}').title || props.name
  } catch {
    return props.name
  }
})
</script>

<template>
  <details class="chart-tool-card" open>
    <summary><span class="tool-dot" />生成图表<small>{{ name }}</small></summary>
    <div class="chart-tool-body">
      <template v-if="source && !broken">
        <img class="chart-image" :src="source" :alt="title" @error="broken = true" />
        <a class="chart-open" :href="source" target="_blank" rel="noopener noreferrer">打开原图</a>
      </template>
      <p v-else-if="source" class="chart-fallback">图片加载失败，原始地址：{{ source }}</p>
      <p v-else class="chart-pending">生成图表中…</p>
    </div>
  </details>
</template>
