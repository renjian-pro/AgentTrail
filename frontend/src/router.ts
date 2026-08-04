import { createRouter, createWebHistory } from 'vue-router'
import ChatView from './views/ChatView.vue'
import LoginView from './views/LoginView.vue'
import AnalyticsSchemaView from './views/AnalyticsSchemaView.vue'
import AnalyticsGlossaryView from './views/AnalyticsGlossaryView.vue'
import RoleListView from './views/RoleListView.vue'
import UserManagementView from './views/UserManagementView.vue'
import { TOKEN_KEY } from './api/auth-token'
import { useAuthStore } from './stores/auth'

const router = createRouter({ history: createWebHistory(), routes: [
  { path: '/', redirect: '/chat' },
  { path: '/login', component: LoginView },
  { path: '/chat', component: ChatView },
  { path: '/analytics/schema', component: AnalyticsSchemaView },
  { path: '/analytics/glossary', component: AnalyticsGlossaryView },
  { path: '/roles', component: RoleListView },
  { path: '/admin/users', component: UserManagementView, meta: { requiresAdmin: true } }
] })

router.beforeEach(async to => {
  if (to.path === '/login') return true
  if (!localStorage.getItem(TOKEN_KEY)) return { path: '/login', query: { redirect: to.fullPath } }
  const auth = useAuthStore()
  if (!auth.currentUser) {
    try {
      await auth.fetchCurrentUser()
    } catch {
      auth.clearLocalSession()
      return { path: '/login', query: { redirect: to.fullPath } }
    }
  }
  if (to.meta.requiresAdmin && !auth.currentUser?.roles.includes('admin')) return { path: '/chat' }
  return true
})

export default router
