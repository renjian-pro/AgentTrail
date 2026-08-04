<script setup lang="ts">
import { computed } from 'vue'
import { RouterView, useRoute } from 'vue-router'
import AppShellLayout from './layouts/AppShellLayout.vue'

const route = useRoute()
// /login is its own full-page layout (see LoginView) — everyone else gets the
// authenticated sidebar shell. Two distinct pages, picked here by route, not
// two responsibilities bolted onto one component.
const isAuthPage = computed(() => route.path === '/login')
</script>

<template>
  <RouterView v-if="isAuthPage" />
  <AppShellLayout v-else />
</template>
