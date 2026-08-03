<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { sysApi, type SysPermission } from '../api/sys-api'
import { toErrorMessage } from '../api/http'

const props = defineProps<{ roleId: number }>()
const emit = defineEmits<{ close: []; saved: [] }>()
const grouped = ref<Record<string, SysPermission[]>>({})
const selected = ref<Set<number>>(new Set())
const loading = ref(true)
const saving = ref(false)
const error = ref('')
const modules = computed(() => Object.entries(grouped.value))

async function load() {
  loading.value = true
  error.value = ''
  try {
    const [all, assigned] = await Promise.all([sysApi.permissions(), sysApi.rolePermissions(props.roleId)])
    grouped.value = all
    selected.value = new Set(assigned.map(permission => permission.id))
  } catch (cause) { error.value = toErrorMessage(cause) } finally { loading.value = false }
}

function toggle(id: number, checked: boolean) {
  const next = new Set(selected.value)
  if (checked) next.add(id); else next.delete(id)
  selected.value = next
}

async function save() {
  saving.value = true
  error.value = ''
  try {
    await sysApi.replaceRolePermissions(props.roleId, [...selected.value])
    emit('saved')
  } catch (cause) { error.value = toErrorMessage(cause) } finally { saving.value = false }
}

onMounted(() => void load())
</script>

<template>
  <div class="modal-backdrop" role="presentation" @click.self="emit('close')">
    <section class="modal-card" role="dialog" aria-modal="true" aria-label="Role permissions">
      <header class="modal-header"><div><p class="eyebrow">Permission assignment</p><h2>Role permissions</h2></div><button class="icon-button" aria-label="Close" @click="emit('close')">×</button></header>
      <p v-if="error" class="error">{{ error }}</p>
      <p v-if="loading">Loading permissions…</p>
      <div v-else class="permission-groups"><fieldset v-for="[module, permissions] in modules" :key="module"><legend>{{ module }}</legend><label v-for="permission in permissions" :key="permission.id" class="checkbox-row"><input type="checkbox" :checked="selected.has(permission.id)" @change="toggle(permission.id, ($event.target as HTMLInputElement).checked)" /><span><b>{{ permission.name }}</b><code>{{ permission.code }}</code></span></label></fieldset></div>
      <footer class="modal-actions"><button class="secondary-button" @click="emit('close')">Cancel</button><button class="primary-button" :disabled="loading || saving" @click="save">{{ saving ? 'Saving…' : 'Save permissions' }}</button></footer>
    </section>
  </div>
</template>
