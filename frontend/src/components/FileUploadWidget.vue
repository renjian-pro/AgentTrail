<script setup lang="ts">
import { ref } from 'vue'

const emit = defineEmits<{ upload: [File] }>()
const input = ref<HTMLInputElement>()

function pick() {
  input.value?.click()
}

function selected(event: Event) {
  const file = (event.target as HTMLInputElement).files?.[0]
  if (file) emit('upload', file)
}
</script>

<template>
  <!-- 拖放本身由外层整个输入区（见 ChatView 的 .chat-bottom）接住，不只是这一小条——之前
       拖放监听只挂在这一个小组件上，实际可拖放的区域只有这颗按钮那么大，用户很自然地会往
       下面那个大输入框拖，那里毫无反应，体感上就是"拖放根本不能用"。这里只保留点击选择文件。 -->
  <!-- "也可拖放文件"那句提示不在这里，在 ChatView 的按钮排末尾：夹在按钮中间会把一排按钮
       从视觉上切成两段，而那一排的意义恰恰是"这些是同一类东西"。 -->
  <!-- 不再包一层 .file-upload：那层曾经是为了排住按钮和"也可拖放文件"那句提示，提示搬走之后
       它只剩一个 hidden input（display:none，不占布局）和一颗按钮。去掉之后按钮就是
       .capability-bar 的直接 flex 子元素，和同排另外三颗按同一套规则对齐。 -->
  <input ref="input" type="file" hidden @change="selected">
  <button type="button" @click="pick">＋ 添加文件</button>
</template>
