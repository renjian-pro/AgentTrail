<script setup lang="ts">
import type { AttachedFile } from '../api/file-api'

defineProps<{ files: AttachedFile[]; busy: boolean; error: string }>()
const emit = defineEmits<{ remove: [number] }>()
</script>

<template>
  <div v-if="files.length || busy || error" class="attachments">
    <span v-if="busy">正在上传和解析文件…</span>
    <span v-if="error" class="error">{{ error }}</span>
    <div v-for="file in files" :key="file.fileId">
      ▧ {{ file.fileName }} <small>{{ file.routedToRag ? 'RAG 检索' : '已解析' }}</small>
      <button @click="emit('remove', file.fileId)">×</button>
    </div>
  </div>
</template>
