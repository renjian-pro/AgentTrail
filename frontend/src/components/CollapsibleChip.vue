<script setup lang="ts">
import { ref } from 'vue'

defineProps<{ label: string; content: string }>()
const open = ref(false)
</script>

<template>
  <div class="chip">
    <button @click="open = !open">{{ open ? '⌄' : '›' }} {{ label }}</button>
    <pre v-if="open">{{ content }}</pre>
  </div>
</template>
