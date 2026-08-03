import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import { authApi, type CurrentUser } from '../api/auth-api'
import { clearToken, readToken, saveToken } from '../api/auth-token'

export const useAuthStore = defineStore('auth', () => {
  const token = ref<string | null>(readToken())
  const currentUser = ref<CurrentUser | null>(null)
  const loading = ref(false)
  const isAuthenticated = computed(() => !!token.value)

  async function login(username: string, password: string) {
    loading.value = true
    try {
      const response = await authApi.login(username, password)
      token.value = response.token
      currentUser.value = response.user
      saveToken(response.token)
      return response.user
    } finally {
      loading.value = false
    }
  }

  async function fetchCurrentUser() {
    if (!token.value) return null
    currentUser.value = await authApi.info()
    return currentUser.value
  }

  async function logout() {
    try {
      if (token.value) await authApi.logout()
    } finally {
      token.value = null
      currentUser.value = null
      clearToken()
    }
  }

  function clearLocalSession() {
    token.value = null
    currentUser.value = null
    clearToken()
  }

  return { token, currentUser, loading, isAuthenticated, login, logout, fetchCurrentUser, clearLocalSession }
})
