import { ref } from 'vue'
import { defineStore } from 'pinia'
import { sysApi, type DeptTreeNode, type UserCreate, type UserPage, type UserUpdate, type UserVO } from '../api/sys-api'

export const useUserManagementStore = defineStore('userManagement', () => {
  const users = ref<UserVO[]>([])
  const total = ref(0)
  const page = ref(0)
  const size = ref(20)
  const keyword = ref('')
  const departments = ref<DeptTreeNode[]>([])
  const loading = ref(false)

  async function loadUsers() {
    loading.value = true
    try {
      const result: UserPage = await sysApi.users(keyword.value, page.value, size.value)
      users.value = result.items; total.value = result.total; page.value = result.page; size.value = result.size
    } finally { loading.value = false }
  }
  async function loadDepartments() { departments.value = await sysApi.depts() }
  async function create(body: UserCreate) { await sysApi.createUser(body); await loadUsers() }
  async function update(id: number, body: UserUpdate) { await sysApi.updateUser(id, body); await loadUsers() }
  async function updateStatus(id: number, status: string) { await sysApi.updateUserStatus(id, status); await loadUsers() }
  async function remove(id: number) { await sysApi.deleteUser(id); await loadUsers() }
  function setPage(next: number) { page.value = next; return loadUsers() }
  function setKeyword(next: string) { keyword.value = next; page.value = 0; return loadUsers() }

  return { users, total, page, size, keyword, departments, loading, loadUsers, loadDepartments, create, update, updateStatus, remove, setPage, setKeyword }
})
