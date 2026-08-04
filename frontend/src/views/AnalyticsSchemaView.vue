<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { analyticsApi, type AnalyticsSchema } from '../api/analytics-api'
import { toErrorMessage } from '../api/http'

const data = ref<AnalyticsSchema>()
const selected = ref('')
const query = ref('')
const error = ref('')
const loading = ref(true)
const selectedTable = computed(() => data.value?.tables.find(table => table.name === selected.value))
const filteredTables = computed(() => {
  const needle = query.value.trim().toLowerCase()
  if (!needle) return data.value?.tables ?? []
  return (data.value?.tables ?? []).filter(table => [
    table.name,
    table.comment,
    ...table.fields.flatMap(field => [field.name, field.comment])
  ].join(' ').toLowerCase().includes(needle))
})

watch(filteredTables, tables => {
  if (!tables.length) {
    selected.value = ''
  } else if (selected.value && !tables.some(table => table.name === selected.value)) {
    selected.value = tables[0].name
  } else if (query.value.trim() && !selected.value) {
    selected.value = tables[0].name
  }
})

onMounted(async () => {
  try {
    data.value = await analyticsApi.schema()
  } catch (failure) {
    error.value = /404/.test(toErrorMessage(failure))
      ? '分析能力未启用，请联系管理员配置只读数据源。'
      : toErrorMessage(failure)
  } finally {
    loading.value = false
  }
})
</script>

<template>
  <div class="analytics-page">
    <div class="feature-hero"><p class="eyebrow">ANALYTICS</p><h1>Schema 浏览</h1><p>查看分析数据源的业务表、字段说明、主键、样例和关联关系。</p></div>
    <p v-if="loading" class="empty-state">正在加载 Schema…</p>
    <div v-else-if="error" class="analytics-disabled"><h2>分析数据源暂不可用</h2><p>{{ error }}</p></div>
    <div v-else class="schema-browser">
      <aside class="schema-list"><h2>{{ data?.database }}</h2><input v-model="query" class="schema-search" placeholder="搜索表或字段" aria-label="搜索表或字段" /><button v-for="table in filteredTables" :key="table.name" :class="{ selected: table.name === selected }" @click="selected = table.name">{{ table.name }}<small>{{ table.fields.length }} fields</small></button><p v-if="!filteredTables.length" class="empty-state">没有匹配的表。</p></aside>
      <section v-if="selectedTable" class="analytics-card"><div class="table-heading"><div><h2>{{ selectedTable.name }}</h2><p class="muted">{{ selectedTable.comment || '暂无表说明' }}</p></div><RouterLink class="table-question" :to="{ path: '/chat', query: { mode: 'analytics', q: `请分析 ${selectedTable.name} 表` } }">问一下这张表</RouterLink></div><table class="data-table"><thead><tr><th>字段</th><th>类型</th><th>说明</th><th>样例</th></tr></thead><tbody><tr v-for="field in selectedTable.fields" :key="field.name"><td><code>{{ field.name }}</code><span v-if="field.primaryKey" class="tag">PK</span></td><td>{{ field.type }}</td><td>{{ field.comment || '—' }}</td><td><span v-if="field.examples.length">{{ field.examples.join('、') }}</span><span v-else class="muted">—</span></td></tr></tbody></table><div v-if="selectedTable.foreignKeys.length" class="foreign-keys"><b>关联关系</b><span v-for="fk in selectedTable.foreignKeys" :key="fk.fromColumn + fk.toTable"> {{ fk.fromColumn }} → {{ fk.toTable }}.{{ fk.toColumn }}</span></div></section>
      <p v-else class="empty-state">请选择一张表。</p>
    </div>
  </div>
</template>
