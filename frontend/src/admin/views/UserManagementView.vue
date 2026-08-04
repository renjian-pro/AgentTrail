<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { toErrorMessage } from '../../api/http'
import { sysApi, type SysRole, type UserCreate, type UserUpdate, type UserVO } from '../api/sys-api'
import DeptTree from '../components/DeptTree.vue'
import UserFormDialog from '../components/UserFormDialog.vue'
import { useUserManagementStore } from '../stores/userManagement'

const store = useUserManagementStore()
const roles = ref<SysRole[]>([])
const dialogUser = ref<UserVO | null | undefined>(undefined)
const error = ref('')
const totalPages = computed(() => Math.max(1, Math.ceil(store.total / store.size)))

async function load() {
  error.value = ''
  try { await Promise.all([store.loadUsers(), store.loadDepartments(), roles.value.length ? Promise.resolve() : sysApi.roles().then(value => { roles.value = value })]) } catch (cause) { error.value = toErrorMessage(cause) }
}
function openCreate() { dialogUser.value = null }
function openEdit(user: UserVO) { dialogUser.value = user }
async function save(body: UserCreate | UserUpdate) {
  try {
    if (dialogUser.value) await store.update(dialogUser.value.id, body as UserUpdate); else await store.create(body as UserCreate)
    dialogUser.value = undefined
  } catch (cause) { error.value = toErrorMessage(cause) }
}
async function toggleStatus(user: UserVO) {
  try { await store.updateStatus(user.id, user.status === 'ACTIVE' ? 'DISABLED' : 'ACTIVE') } catch (cause) { error.value = toErrorMessage(cause) }
}
async function remove(user: UserVO) {
  if (!window.confirm(`Delete ${user.username}?`)) return
  try { await store.remove(user.id) } catch (cause) { error.value = toErrorMessage(cause) }
}
function search(event: Event) { void store.setKeyword((event.target as HTMLInputElement).value) }
onMounted(() => void load())
</script>

<template>
  <main class="admin-page">
    <section class="feature-hero"><p class="eyebrow">Administration</p><h1>Users</h1><p>Manage workspace accounts, roles, and department scope.</p></section>
    <p v-if="error" class="error" role="alert">{{ error }}</p>
    <section class="admin-card">
      <div class="toolbar"><input placeholder="Search username or nickname" :value="store.keyword" @change="search" /><button class="primary-button" @click="openCreate">New user</button></div>
      <table class="data-table"><thead><tr><th>Username</th><th>Nickname</th><th>Roles</th><th>Status</th><th>Actions</th></tr></thead><tbody>
        <tr v-for="user in store.users" :key="user.id"><td>{{ user.username }}</td><td>{{ user.nickname }}</td><td><span v-for="role in user.roleCodes" :key="role" class="tag">{{ role }}</span></td><td><span :class="['status-tag', user.status.toLowerCase()]">{{ user.status }}</span></td><td class="row-actions"><button class="secondary-button" @click="openEdit(user)">Edit</button><button class="secondary-button" @click="toggleStatus(user)">{{ user.status === 'ACTIVE' ? 'Disable' : 'Enable' }}</button><button class="danger-button" @click="remove(user)">Delete</button></td></tr>
      </tbody></table>
      <p v-if="!store.users.length && !store.loading" class="empty-state">No users found.</p>
      <div class="pagination"><button class="secondary-button" :disabled="store.page <= 0" @click="store.setPage(store.page - 1)">Previous</button><span>Page {{ store.page + 1 }} / {{ totalPages }}</span><button class="secondary-button" :disabled="store.page + 1 >= totalPages" @click="store.setPage(store.page + 1)">Next</button></div>
    </section>
    <UserFormDialog v-if="dialogUser !== undefined" :user="dialogUser" :roles="roles" :departments="store.departments" @close="dialogUser = undefined" @save="save" />
  </main>
</template>
