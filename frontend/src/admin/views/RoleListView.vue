<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { sysApi, type SysRole } from '../api/sys-api'
import { toErrorMessage } from '../../api/http'
import { useAuthStore } from '../../stores/auth'
import RolePermissionDialog from '../components/RolePermissionDialog.vue'

const auth = useAuthStore()
const roles = ref<SysRole[]>([])
const error = ref('')
const selectedRole = ref<SysRole | null>(null)
const isAdmin = computed(() => auth.currentUser?.roles.includes('admin') === true)

async function load() {
  error.value = ''
  try { roles.value = await sysApi.roles() } catch (cause) { error.value = toErrorMessage(cause) }
}

onMounted(() => void load())
</script>

<template>
  <main class="admin-page">
    <section class="feature-hero"><p class="eyebrow">Access control</p><h1>Roles</h1><p>Review each role and its data scope.</p></section>
    <p v-if="error" class="error">{{ error }}</p>
    <section class="admin-card">
      <table class="data-table"><thead><tr><th>Name</th><th>Code</th><th>Data scope</th><th>Status</th><th /></tr></thead>
        <tbody><tr v-for="role in roles" :key="role.id"><td>{{ role.name }}</td><td><code>{{ role.code }}</code></td><td>{{ role.dataScope }}</td><td>{{ role.status }}</td><td><button v-if="isAdmin" class="secondary-button" @click="selectedRole = role">Edit permissions</button></td></tr></tbody>
      </table>
      <p v-if="!roles.length && !error" class="empty-state">No roles found.</p>
    </section>
    <RolePermissionDialog v-if="selectedRole" :role-id="selectedRole.id" @close="selectedRole = null" @saved="selectedRole = null; load()" />
  </main>
</template>
