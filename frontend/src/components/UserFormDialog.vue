<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import type { DeptTreeNode, SysRole, UserCreate, UserUpdate, UserVO } from '../api/sys-api'
import DeptTree from './DeptTree.vue'

const props = defineProps<{ user?: UserVO | null; roles: SysRole[]; departments: DeptTreeNode[] }>()
const emit = defineEmits<{ close: []; save: [body: UserCreate | UserUpdate] }>()
const nickname = ref('')
const username = ref('')
const password = ref('')
const roleIds = ref<number[]>([])
const deptIds = ref<number[]>([])
const error = ref('')
const editing = computed(() => !!props.user)

watch(() => props.user, user => {
  username.value = user?.username ?? ''
  nickname.value = user?.nickname ?? ''
  password.value = ''
  roleIds.value = [...(user?.roleIds ?? [])]
  deptIds.value = [...(user?.deptIds ?? [])]
  error.value = ''
}, { immediate: true })

function submit() {
  error.value = ''
  if (!editing.value && !username.value.trim()) { error.value = 'Username is required'; return }
  if (!editing.value && !password.value) { error.value = 'Password is required'; return }
  if (editing.value) emit('save', { nickname: nickname.value.trim(), roleIds: roleIds.value, deptIds: deptIds.value })
  else emit('save', { username: username.value.trim(), password: password.value, nickname: nickname.value.trim(), roleIds: roleIds.value, deptIds: deptIds.value })
}
</script>

<template>
  <div class="modal-backdrop" role="presentation" @click.self="emit('close')">
    <section class="modal-card wide-modal" role="dialog" aria-modal="true" aria-label="User form">
      <header class="modal-header"><div><p class="eyebrow">User management</p><h2>{{ editing ? 'Edit user' : 'New user' }}</h2></div><button class="icon-button" aria-label="Close" @click="emit('close')">×</button></header>
      <form class="user-form" @submit.prevent="submit">
        <label>Username<input v-model="username" :disabled="editing" autocomplete="username" /></label>
        <label v-if="!editing">Password<input v-model="password" type="password" autocomplete="new-password" /></label>
        <label>Nickname<input v-model="nickname" /></label>
        <fieldset><legend>Roles</legend><label v-for="role in roles" :key="role.id" class="checkbox-row"><input v-model="roleIds" type="checkbox" :value="role.id" /><span>{{ role.name }} <code>{{ role.code }}</code></span></label></fieldset>
        <fieldset><legend>Departments</legend><DeptTree v-model="deptIds" :nodes="departments" /></fieldset>
        <p v-if="error" class="error" role="alert">{{ error }}</p>
        <footer class="modal-actions"><button type="button" class="secondary-button" @click="emit('close')">Cancel</button><button type="submit" class="primary-button">Save</button></footer>
      </form>
    </section>
  </div>
</template>
