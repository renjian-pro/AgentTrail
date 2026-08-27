<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { evaluationApi, type EvaluationHistoryItem, type EvaluationTask, type GoldenObservation } from '../api/evaluation-api'
import { toErrorMessage } from '../../api/http'
import EvaluationTrendChart from '../components/EvaluationTrendChart.vue'

const task = ref<EvaluationTask | null>(null)
const history = ref<EvaluationHistoryItem[]>([])
const busy = ref(false)
const error = ref('')
const compareIds = ref<string[]>([])

const observations = computed<GoldenObservation[]>(() => task.value?.report?.observations ?? [])
const failedObservations = computed(() => observations.value.filter(item => !item.passed))
const overallPassRate = computed(() => {
  if (!observations.value.length) return 0
  return observations.value.filter(item => item.passed).length / observations.value.length
})
const dimensionPassRates = computed(() => {
  const grouped = new Map<string, { passed: number; total: number }>()
  for (const item of observations.value) {
    const bucket = grouped.get(item.dimension) ?? { passed: 0, total: 0 }
    bucket.total += 1
    if (item.passed) bucket.passed += 1
    grouped.set(item.dimension, bucket)
  }
  return [...grouped.entries()].map(([dimension, bucket]) => ({ dimension, rate: bucket.passed / bucket.total }))
})
const progressPercent = computed(() => {
  if (!task.value || task.value.totalCases === 0) return 0
  return Math.round((task.value.completedCases / task.value.totalCases) * 100)
})
const compareItems = computed(() => compareIds.value
  .map(id => history.value.find(item => item.taskId === id))
  .filter((item): item is EvaluationHistoryItem => item !== undefined))
const compareDimensions = computed(() => {
  const [a, b] = compareItems.value
  if (!a || !b) return []
  const keys = new Set([...Object.keys(a.dimensionPassRates), ...Object.keys(b.dimensionPassRates)])
  return [...keys].sort().map(dimension => ({
    dimension,
    a: a.dimensionPassRates[dimension] ?? 0,
    b: b.dimensionPassRates[dimension] ?? 0
  }))
})

function formatPercent(value: number) {
  return `${Math.round(value * 100)}%`
}

function formatStartedAt(millis: number) {
  return new Date(millis).toLocaleString()
}

function toggleCompare(taskId: string) {
  const index = compareIds.value.indexOf(taskId)
  if (index >= 0) {
    compareIds.value.splice(index, 1)
    return
  }
  if (compareIds.value.length >= 2) compareIds.value.shift()
  compareIds.value.push(taskId)
}

/** 提交后反复轮询直到终态——沿用 ChatView.vue 里 PPT/DeepResearch 已验证过的轮询写法。 */
async function pollUntilTerminal<T>(fetchStatus: () => Promise<T>, isTerminal: (value: T) => boolean,
    onUpdate: (value: T) => void, intervalMs = 1500): Promise<void> {
  let current = await fetchStatus()
  onUpdate(current)
  while (!isTerminal(current)) {
    await new Promise(resolve => setTimeout(resolve, intervalMs))
    current = await fetchStatus()
    onUpdate(current)
  }
}

async function loadHistory() {
  try {
    history.value = await evaluationApi.history()
  } catch (cause) {
    error.value = toErrorMessage(cause)
  }
}

async function run() {
  error.value = ''
  busy.value = true
  try {
    const created = await evaluationApi.run()
    task.value = created
    if (created.status === 'RUNNING') {
      await pollUntilTerminal(
        () => evaluationApi.status(created.taskId),
        current => current.status !== 'RUNNING',
        current => { task.value = current })
    }
    await loadHistory()
  } catch (cause) {
    error.value = toErrorMessage(cause)
  } finally {
    busy.value = false
  }
}

onMounted(() => void loadHistory())
</script>

<template>
  <main class="admin-page">
    <section class="feature-hero">
      <p class="eyebrow">Quality</p>
      <h1>Evaluation</h1>
      <p>Run the Golden Set against the current build and compare it against past runs.</p>
    </section>

    <p v-if="error" class="error">{{ error }}</p>

    <section class="admin-card">
      <button class="primary-button" :disabled="busy" @click="run">
        {{ busy ? 'Running…' : 'Run evaluation' }}
      </button>

      <div v-if="task" style="margin-top: 16px">
        <p>{{ task.completedCases }} / {{ task.totalCases }} cases — {{ task.status }}</p>
        <div style="height: 8px; border-radius: 4px; background: var(--line); overflow: hidden">
          <div :style="{ width: progressPercent + '%', height: '100%', background: '#292b28' }" />
        </div>

        <p v-if="task.status === 'FAILED'" class="error">{{ task.error }}</p>

        <div v-if="task.report">
          <h2 style="margin-top: 20px">Overall pass rate: {{ formatPercent(overallPassRate) }}</h2>

          <table class="data-table">
            <thead><tr><th>Dimension</th><th>Pass rate</th></tr></thead>
            <tbody>
              <tr v-for="row in dimensionPassRates" :key="row.dimension">
                <td>{{ row.dimension }}</td>
                <td>{{ formatPercent(row.rate) }}</td>
              </tr>
            </tbody>
          </table>

          <h3 style="margin-top: 20px">Failed cases ({{ failedObservations.length }})</h3>
          <p v-if="!failedObservations.length" class="empty-state">All cases passed.</p>
          <details v-for="item in failedObservations" :key="item.id" style="margin: 8px 0">
            <summary>{{ item.id }} — {{ item.question }}</summary>
            <p><strong>Reason:</strong> {{ item.reason }}</p>
            <p><strong>Actual result:</strong> {{ item.actualResult }}</p>
          </details>
        </div>
      </div>
    </section>

    <section class="admin-card" style="margin-top: 16px">
      <h2>Pass rate trend</h2>
      <EvaluationTrendChart :history="history" />
    </section>

    <section class="admin-card" style="margin-top: 16px">
      <h2>History</h2>
      <p class="empty-state" v-if="!history.length">No past runs yet.</p>
      <table v-else class="data-table">
        <thead><tr><th>Compare</th><th>Started</th><th>Status</th><th>Pass rate</th><th>Cases</th></tr></thead>
        <tbody>
          <tr v-for="item in history" :key="item.taskId">
            <td><input type="checkbox" :checked="compareIds.includes(item.taskId)" @change="toggleCompare(item.taskId)" /></td>
            <td>{{ formatStartedAt(item.startedAtMillis) }}</td>
            <td>{{ item.status }}</td>
            <td>{{ formatPercent(item.passRate) }}</td>
            <td>{{ item.completedCases }} / {{ item.totalCases }}</td>
          </tr>
        </tbody>
      </table>

      <div v-if="compareItems.length === 2" style="margin-top: 16px">
        <h3>Comparison</h3>
        <table class="data-table">
          <thead>
            <tr>
              <th>Dimension</th>
              <th>{{ formatStartedAt(compareItems[0].startedAtMillis) }}</th>
              <th>{{ formatStartedAt(compareItems[1].startedAtMillis) }}</th>
              <th>Δ</th>
            </tr>
          </thead>
          <tbody>
            <tr>
              <td>Overall</td>
              <td>{{ formatPercent(compareItems[0].passRate) }}</td>
              <td>{{ formatPercent(compareItems[1].passRate) }}</td>
              <td>{{ formatPercent(compareItems[1].passRate - compareItems[0].passRate) }}</td>
            </tr>
            <tr v-for="row in compareDimensions" :key="row.dimension">
              <td>{{ row.dimension }}</td>
              <td>{{ formatPercent(row.a) }}</td>
              <td>{{ formatPercent(row.b) }}</td>
              <td>{{ formatPercent(row.b - row.a) }}</td>
            </tr>
          </tbody>
        </table>
      </div>
    </section>
  </main>
</template>
