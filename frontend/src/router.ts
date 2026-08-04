import { createRouter, createWebHistory } from 'vue-router'
import ChatView from './views/ChatView.vue'
import LoginView from './views/LoginView.vue'
import AdminLayout from './admin/layouts/AdminLayout.vue'
import RoleListView from './admin/views/RoleListView.vue'
import UserManagementView from './admin/views/UserManagementView.vue'
import AnalyticsSchemaView from './views/AnalyticsSchemaView.vue'
import AnalyticsGlossaryView from './views/AnalyticsGlossaryView.vue'
import { TOKEN_KEY } from './api/auth-token'
import { useAuthStore } from './stores/auth'

const router = createRouter({ history: createWebHistory(), routes: [
  { path: '/', redirect: '/chat' },
  { path: '/login', component: LoginView },
  { path: '/chat', component: ChatView },
  { path: '/analytics/schema', component: AnalyticsSchemaView },
  { path: '/analytics/glossary', component: AnalyticsGlossaryView },
  // The whole admin area (Users + Roles) sits behind one gate now — the sidebar only shows a
  // single "Admin" entry for admins, so both children inherit requiresAdmin from the parent
  // (Vue Router merges meta across matched route records).
  {
    path: '/admin',
    component: AdminLayout,
    redirect: '/admin/users',
    meta: { requiresAdmin: true },
    children: [
      { path: 'users', component: UserManagementView },
      { path: 'roles', component: RoleListView }
    ]
  }
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
