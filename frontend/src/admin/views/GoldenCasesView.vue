<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { toErrorMessage } from '../../api/http'
import { evaluationApi, type GoldenCaseRequest, type GoldenCaseView } from '../api/evaluation-api'
import GoldenCaseFormDialog from '../components/GoldenCaseFormDialog.vue'

const cases = ref<GoldenCaseView[]>([])
const error = ref('')
const loading = ref(false)
const dialogCase = ref<GoldenCaseView | null | undefined>(undefined)

async function load() {
  error.value = ''
  loading.value = true
  try { cases.value = await evaluationApi.cases() } catch (cause) { error.value = toErrorMessage(cause) } finally { loading.value = false }
}
function openCreate() { dialogCase.value = null }
function openEdit(item: GoldenCaseView) { dialogCase.value = item }
async function save(body: GoldenCaseRequest) {
  try {
    if (dialogCase.value) await evaluationApi.updateCase(dialogCase.value.id, body)
    else await evaluationApi.createCase(body)
    dialogCase.value = undefined
    await load()
  } catch (cause) { error.value = toErrorMessage(cause) }
}
async function remove(item: GoldenCaseView) {
  if (!window.confirm(`Delete ${item.id}?`)) return
  try { await evaluationApi.deleteCase(item.id); await load() } catch (cause) { error.value = toErrorMessage(cause) }
}
onMounted(() => void load())
</script>

<template>
  <main class="admin-page">
    <section class="feature-hero">
      <p class="eyebrow">Quality</p>
      <h1>Golden Cases</h1>
      <p>Built-in YAML cases are read-only baseline; cases added here run alongside them without a redeploy.</p>
    </section>

    <p v-if="error" class="error" role="alert">{{ error }}</p>

    <section class="admin-card">
      <div class="toolbar"><span /><button class="primary-button" @click="openCreate">New case</button></div>
      <table class="data-table">
        <thead><tr><th>Id</th><th>Dimension</th><th>Question</th><th>As user</th><th>Source</th><th>Actions</th></tr></thead>
        <tbody>
          <tr v-for="item in cases" :key="item.id">
            <td><code>{{ item.id }}</code></td>
            <td>{{ item.dimension }}</td>
            <td>{{ item.question }}</td>
            <td>{{ item.asUser }}</td>
            <td><span class="tag">{{ item.source }}</span></td>
            <td class="row-actions">
              <template v-if="item.editable">
                <button class="secondary-button" @click="openEdit(item)">Edit</button>
                <button class="danger-button" @click="remove(item)">Delete</button>
              </template>
              <span v-else class="hint">read-only</span>
            </td>
          </tr>
        </tbody>
      </table>
      <p v-if="!cases.length && !loading" class="empty-state">No cases found.</p>
    </section>

    <GoldenCaseFormDialog v-if="dialogCase !== undefined" :existing="dialogCase" @close="dialogCase = undefined" @save="save" />
  </main>
</template>
