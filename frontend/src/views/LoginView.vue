<script setup lang="ts">
import { ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { toErrorMessage } from '../api/http'
import { useAuthStore } from '../stores/auth'

// 不预填有效凭证——之前写死 admin/admin123，打开登录页不用输入任何东西点一下就直接登录进去了。
const username = ref('')
const password = ref('')
const error = ref('')
const auth = useAuthStore()
const route = useRoute()
const router = useRouter()

async function submit() {
  error.value = ''
  try {
    await auth.login(username.value.trim(), password.value)
    const redirect = typeof route.query.redirect === 'string' && route.query.redirect.startsWith('/') ? route.query.redirect : '/chat'
    await router.push(redirect)
  } catch (cause) {
    error.value = toErrorMessage(cause) || 'Invalid username or password'
  }
}
</script>

<template>
  <main class="auth-page">
    <section class="auth-card">
      <div class="welcome-mark">A</div>
      <p class="eyebrow">AgentTrail</p>
      <h1>Sign in</h1>
      <p class="auth-subtitle">Use your workspace account to continue.</p>
      <form @submit.prevent="submit">
        <label>Username<input v-model="username" autocomplete="username" required /></label>
        <label>Password<input v-model="password" autocomplete="current-password" type="password" required /></label>
        <p v-if="error" class="error auth-error" role="alert">{{ error }}</p>
        <button class="primary-button" type="submit" :disabled="auth.loading">{{ auth.loading ? 'Signing in…' : 'Sign in' }}</button>
      </form>
    </section>
  </main>
</template>
