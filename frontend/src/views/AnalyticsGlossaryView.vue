<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { analyticsApi, type GlossaryEntry } from '../api/analytics-api'
import { toErrorMessage } from '../api/http'

const entries = ref<GlossaryEntry[]>([])
const error = ref('')
const loading = ref(true)
const query = ref('')
const copied = ref('')
const visibleEntries = computed(() => {
  const needle = query.value.trim().toLowerCase()
  return entries.value
    .filter(item => !needle || [item.term, ...item.synonyms, item.description].join(' ').toLowerCase().includes(needle))
    .sort((left, right) => Number(right.term === '时间口径') - Number(left.term === '时间口径'))
})

async function copyFragment(entry: GlossaryEntry) {
  if (!entry.sqlFragment) return
  try {
    await navigator.clipboard.writeText(entry.sqlFragment)
    copied.value = entry.term
    window.setTimeout(() => { if (copied.value === entry.term) copied.value = '' }, 1600)
  } catch {
    copied.value = ''
  }
}

onMounted(async () => {
  try { entries.value = await analyticsApi.glossary() } catch (failure) {
    const message = toErrorMessage(failure)
    error.value = /404/.test(message) ? '分析能力未启用，请联系管理员配置只读数据源。' : message
  } finally { loading.value = false }
})
</script>

<template>
  <div class="analytics-page">
    <div class="feature-hero"><p class="eyebrow">ANALYTICS</p><h1>业务术语字典</h1><p>统一业务指标口径和时间锚点，帮助对话分析解释 SQL 与结果。</p></div>
    <div class="glossary-toolbar"><input v-model="query" placeholder="搜索术语或同义词" aria-label="搜索术语" /></div>
    <p v-if="loading" class="empty-state">正在加载术语…</p>
    <div v-else-if="error" class="analytics-disabled"><h2>术语字典暂不可用</h2><p>{{ error }}</p></div>
    <div v-else class="glossary-grid"><article v-for="entry in visibleEntries" :key="entry.term" class="analytics-card glossary-card" :class="{ 'time-anchor': entry.term === '时间口径' }"><div class="glossary-title"><h2>{{ entry.term }}</h2><span v-for="synonym in entry.synonyms" :key="synonym" class="tag">{{ synonym }}</span><span v-if="entry.term === '时间口径'" class="time-anchor-badge">时间锚点</span></div><p class="glossary-description">{{ entry.description }}</p><div v-if="entry.sqlFragment" class="glossary-fragment"><code>{{ entry.sqlFragment }}</code><button type="button" @click="copyFragment(entry)">{{ copied === entry.term ? '已复制' : '复制 SQL' }}</button></div><small v-if="entry.example" class="glossary-example">例：{{ entry.example }}</small></article><p v-if="!visibleEntries.length" class="empty-state">没有匹配的术语。</p></div>
  </div>
</template>
