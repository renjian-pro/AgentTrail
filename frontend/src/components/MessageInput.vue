<script setup lang="ts">
import { computed, ref, watch } from 'vue'

/**
 * @param placeholder 随当前能力模式变化（见 ChatView 的 PLACEHOLDERS），不传时用通用文案。
 * @param canStop 本次生成可以被中断（ChatView 的 canStop：SSE 流还握着 AbortController）。
 *   为真时右下角那颗按钮换成"停止"，而不是在别处再摆一颗——发送和停止是同一个位置上
 *   互斥的两个状态，不是两个并存的控件。
 */
const props = defineProps<{ busy: boolean; initialValue?: string; placeholder?: string; canStop?: boolean }>()
const emit = defineEmits<{ send: [string]; stop: [] }>()
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
 * 三态，按可操作性排序：能停就是停止键；在忙但停不掉（等审批、后台跑的 PPT/研究）就是
 * 一颗禁用的省略号——不能让它长得像停止键却按不动；其余是发送键。
 */
const action = computed(() => {
  if (props.canStop) return { glyph: '■', label: '停止生成', stop: true, disabled: false }
  if (props.busy) return { glyph: '…', label: '正在生成', stop: false, disabled: true }
  return { glyph: '↑', label: '发送消息', stop: false, disabled: false }
})
</script>

<template>
  <form class="composer" @submit.prevent="submit">
    <textarea v-model="message" rows="2" :placeholder="placeholder ?? '今天想完成什么？输入你的问题或任务…'" aria-label="消息" @keydown.enter.exact.prevent="submit" />
    <button :type="action.stop ? 'button' : 'submit'" :class="{ stopping: action.stop }" :disabled="action.disabled"
        :aria-label="action.label" @click="action.stop && emit('stop')">{{ action.glyph }}</button>
  </form>
</template>
