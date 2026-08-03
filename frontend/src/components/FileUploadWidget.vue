<script setup lang="ts">
import { ref } from 'vue'

const emit = defineEmits<{ upload: [File] }>()
const input = ref<HTMLInputElement>()
const dragging = ref(false)

function pick() {
  input.value?.click()
}

function receive(files: FileList | null) {
  const file = files?.[0]
  if (file) emit('upload', file)
}

function selected(event: Event) {
  receive((event.target as HTMLInputElement).files)
}
</script>

<template>
  <div class="file-upload" :class="{ dragging }" @dragover.prevent="dragging = true" @dragleave="dragging = false" @drop.prevent="dragging = false; receive($event.dataTransfer?.files ?? null)">
    <input ref="input" type="file" hidden @change="selected">
    <button type="button" @click="pick">＋ 添加文件</button>
    <span>也可拖放文件</span>
  </div>
</template>
