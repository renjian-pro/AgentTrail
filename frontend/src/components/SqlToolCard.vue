<script setup lang="ts">
import { computed, ref } from 'vue'
import { formatSql, parseSqlResult } from '../utils/sqlResult'

const props = defineProps<{ name: string; argumentsText: string; result?: string }>()
const copied = ref(false)
const parsed = computed(() => parseSqlResult(props.result ?? ''))
const sql = computed(() => {
  try { return formatSql(JSON.parse(props.argumentsText || '{}').sql ?? props.argumentsText) } catch { return formatSql(props.argumentsText) }
})
const label = computed(() => props.name === 'validate_sql' ? '校验 SQL' : '执行查询')

async function copySql() {
  try {
    await navigator.clipboard.writeText(sql.value)
    copied.value = true
    window.setTimeout(() => { copied.value = false }, 1600)
  } catch {
    copied.value = false
  }
}
</script>

<template>
  <details class="sql-tool-card" open>
    <summary><span class="tool-dot" />{{ label }}<small>{{ name }}</small></summary>
    <div class="sql-tool-body">
      <div class="sql-code-head"><span>SQL</span><button type="button" @click.stop="copySql">{{ copied ? '已复制' : '复制' }}</button></div>
      <pre class="sql-code">{{ sql }}</pre>
      <div v-if="parsed.kind === 'table'" class="sql-status">
        共 {{ parsed.totalRows ?? parsed.rows.length }} 行
        <span v-if="parsed.elapsedMs != null">· {{ parsed.elapsedMs }}ms</span>
        <span v-if="parsed.truncated">· {{ parsed.note || '结果已截断，仅展示预览行' }}</span>
      </div>
      <div v-else-if="parsed.kind === 'empty'" class="sql-status sql-status-empty">{{ parsed.guidance }}</div>
      <div v-else-if="parsed.kind === 'error'" class="sql-status sql-status-error">{{ parsed.message }}</div>
      <div v-else class="sql-status">结果文本</div>
      <div v-if="parsed.kind === 'table'" class="sql-result-scroll">
        <table class="sql-result-table">
          <thead><tr><th v-for="header in parsed.headers" :key="header">{{ header }}</th></tr></thead>
          <tbody>
            <tr v-for="(row, rowIndex) in parsed.rows" :key="rowIndex">
              <td v-for="(cell, cellIndex) in row" :key="cellIndex" :class="{ 'is-masked': cell === '********' }" :title="cell === '********' ? '该字段已脱敏' : undefined">
                <span v-if="cell === '********'">🔒 </span>{{ cell }}
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      <pre v-else-if="parsed.kind === 'raw'" class="sql-raw-result">{{ parsed.text || '等待结果…' }}</pre>
    </div>
  </details>
</template>
