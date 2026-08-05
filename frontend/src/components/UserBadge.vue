<script setup lang="ts">
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '../stores/auth'

const auth = useAuthStore()
const router = useRouter()
const displayName = computed(() => auth.currentUser?.nickname || auth.currentUser?.username || 'User')
const roles = computed(() => auth.currentUser?.roles.join(', ') || '')

async function logout() {
  await auth.logout()
  await router.push('/login')
}
</script>

<template>
  <div class="user-badge">
    <span class="user-avatar">{{ displayName.slice(0, 1).toUpperCase() }}</span>
    <span class="user-badge-copy"><b>{{ displayName }}</b><small>{{ roles || 'Signed in' }}</small></span>
    <button class="user-logout" type="button" title="Log out" @click="logout"><span aria-hidden="true">↪</span> Log out</button>
  </div>
</template>
