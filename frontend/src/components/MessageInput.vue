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

/**
 * 取走当前输入并清空，取不到（空白/忙碌）返回 undefined。
 *
 * <p>任务按钮（生成 PPT / 深度研究）走这条路而不是 `send` 事件：它们是**动作**不是模式，
 * 点一下就该带着当前输入直接发起任务，没有"先选中、再发送"这一步（issue #93）。
 */
function take(): string | undefined {
  const value = message.value.trim()
  if (!value || props.busy) return undefined
  message.value = ''
  return value
}

defineExpose({ take })
</script>

<template>
  <form class="composer" @submit.prevent="submit">
    <textarea v-model="message" rows="2" placeholder="今天想完成什么？输入你的问题或任务…" aria-label="消息" @keydown.enter.exact.prevent="submit" />
    <button :disabled="busy" :aria-label="busy ? '正在生成' : '发送消息'">{{ busy ? '…' : '↑' }}</button>
  </form>
</template>
