<script setup lang="ts">
import { computed } from 'vue'
import type { EvaluationHistoryItem } from '../api/evaluation-api'

const props = defineProps<{ history: EvaluationHistoryItem[] }>()

const WIDTH = 640
const HEIGHT = 160
const PAD_LEFT = 34
const PAD_RIGHT = 12
const PAD_TOP = 12
const PAD_BOTTOM = 26

// history() comes back newest-first (capped at 50 runs); a trend reads left-to-right chronologically.
const ordered = computed(() => [...props.history].filter(item => item.status !== 'RUNNING').reverse())

function x(index: number) {
  const count = ordered.value.length
  if (count <= 1) return (WIDTH - PAD_LEFT - PAD_RIGHT) / 2 + PAD_LEFT
  return PAD_LEFT + (index / (count - 1)) * (WIDTH - PAD_LEFT - PAD_RIGHT)
}
function y(passRate: number) {
  return PAD_TOP + (1 - passRate) * (HEIGHT - PAD_TOP - PAD_BOTTOM)
}
const points = computed(() => ordered.value.map((item, index) => ({
  cx: x(index), cy: y(item.passRate), item
})))
const linePath = computed(() => points.value.map((point, index) => `${index === 0 ? 'M' : 'L'}${point.cx},${point.cy}`).join(' '))
const gridLines = [0, 0.25, 0.5, 0.75, 1]

function formatDate(millis: number) {
  return new Date(millis).toLocaleDateString(undefined, { month: 'short', day: 'numeric' })
}
</script>

<template>
  <div class="trend-chart">
    <p v-if="ordered.length < 2" class="empty-state">Run the evaluation a couple more times to see a trend.</p>
    <svg v-else :viewBox="`0 0 ${WIDTH} ${HEIGHT}`" role="img" aria-label="Pass rate trend across evaluation runs">
      <line v-for="level in gridLines" :key="level"
            :x1="PAD_LEFT" :x2="WIDTH - PAD_RIGHT" :y1="y(level)" :y2="y(level)"
            stroke="var(--line)" stroke-width="1" />
      <text v-for="level in gridLines" :key="'label-' + level" :x="PAD_LEFT - 6" :y="y(level) + 3"
            text-anchor="end" font-size="9" fill="var(--muted)">{{ Math.round(level * 100) }}%</text>
      <path :d="linePath" fill="none" stroke="var(--accent)" stroke-width="2" />
      <g v-for="point in points" :key="point.item.taskId">
        <circle :cx="point.cx" :cy="point.cy" r="3.5"
                :fill="point.item.status === 'SUCCESS' ? 'var(--accent)' : '#a83d35'">
          <title>{{ formatDate(point.item.startedAtMillis) }} — {{ Math.round(point.item.passRate * 100) }}% ({{ point.item.status }})</title>
        </circle>
      </g>
      <text :x="PAD_LEFT" :y="HEIGHT - 6" font-size="10" fill="var(--muted)">{{ formatDate(ordered[0].startedAtMillis) }}</text>
      <text :x="WIDTH - PAD_RIGHT" :y="HEIGHT - 6" text-anchor="end" font-size="10" fill="var(--muted)">{{ formatDate(ordered[ordered.length - 1].startedAtMillis) }}</text>
    </svg>
  </div>
</template>
