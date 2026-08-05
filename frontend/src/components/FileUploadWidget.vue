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
  <div class="file-upload">
    <input ref="input" type="file" hidden @change="selected">
    <button type="button" @click="pick">＋ 添加文件</button>
    <span>也可拖放文件</span>
  </div>
</template>
