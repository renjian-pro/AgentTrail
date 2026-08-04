<script setup lang="ts">
import { ref } from 'vue'

const emit = defineEmits<{ upload: [File]; rejected: [string] }>()
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

/**
 * Dropping a folder still lands one zero-byte File in dataTransfer.files (browsers don't refuse
 * it outright), which used to sail past this component and get rejected server-side with a bare
 * "Unprocessable Content" — technically correct, useless to the person who just dragged something in.
 * DataTransferItem.webkitGetAsEntry() is the only way to see "this is a directory" before that
 * happens, so the drop handler checks entries first and never even builds a FormData for one.
 */
function drop(event: DragEvent) {
  dragging.value = false
  const items = event.dataTransfer?.items
  if (items) {
    for (const item of items) {
      const entry = item.webkitGetAsEntry?.()
      if (entry?.isDirectory) {
        emit('rejected', '不支持上传文件夹，请选择单个文件')
        return
      }
    }
  }
  receive(event.dataTransfer?.files ?? null)
}
</script>

<template>
  <div class="file-upload" :class="{ dragging }" @dragover.prevent="dragging = true" @dragleave="dragging = false" @drop.prevent="drop">
    <input ref="input" type="file" hidden @change="selected">
    <button type="button" @click="pick">＋ 添加文件</button>
    <span>也可拖放文件</span>
  </div>
</template>
