<script setup lang="ts">
import { ref, watch } from 'vue'

const props = defineProps<{ busy: boolean; initialValue?: string }>()
const emit = defineEmits<{ send: [string] }>()
const message = ref(props.initialValue ?? '')

watch(() => props.initialValue, value => {
  if (value != null) message.value = value
})

function submit() {
  if (message.value.trim() && !props.busy) {
    emit('send', message.value)
    message.value = ''
  }
}
</script>

<template>
  <form class="composer" @submit.prevent="submit">
    <textarea v-model="message" rows="2" placeholder="今天想完成什么？输入你的问题或任务…" aria-label="消息" @keydown.enter.exact.prevent="submit" />
    <button :disabled="busy" :aria-label="busy ? '正在生成' : '发送消息'">{{ busy ? '…' : '↑' }}</button>
  </form>
</template>
