<script setup lang="ts">
import type { TodoItem } from '../types/stream-event'

defineProps<{ items: TodoItem[] }>()
</script>

<template>
  <div v-if="items.length" class="todo-card">
    <b>任务进度</b>
    <div v-for="item in items" :key="item.content">
      <span :class="item.status.toLowerCase()">●</span> {{ item.content }}
    </div>
  </div>
</template>
