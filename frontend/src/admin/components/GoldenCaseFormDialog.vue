<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import type { GoldenCaseRequest, GoldenCaseSource, GoldenCaseView } from '../api/evaluation-api'

const ASSERTION_TYPES = ['result_matches_reference', 'row_count_equals', 'row_count_between', 'scalar_equals',
  'scalar_greater_than', 'sql_contains_scope_filter', 'output_contains_any', 'output_not_contains', 'tool_called',
  'tool_not_called', 'rounds_at_most']

const props = defineProps<{
  existing?: GoldenCaseView | null
  prefill?: { question: string; actualOutput?: string; source: GoldenCaseSource; sourceConversationId: string } | null
}>()
const emit = defineEmits<{ close: []; save: [body: GoldenCaseRequest] }>()

const editing = computed(() => !!props.existing)
const id = ref('')
const dimension = ref('')
const question = ref('')
const asUser = ref('admin')
const referenceSql = ref('')
const assertionsText = ref('[]')
const toolCallsText = ref('')
const error = ref('')

watch([() => props.existing, () => props.prefill], ([existing, prefill]) => {
  id.value = existing?.id ?? ''
  dimension.value = existing?.dimension ?? ''
  question.value = existing?.question ?? prefill?.question ?? ''
  asUser.value = existing?.asUser ?? 'admin'
  referenceSql.value = existing?.referenceSql ?? ''
  assertionsText.value = JSON.stringify(existing?.assertions ?? [], null, 2)
  toolCallsText.value = (existing?.expectedToolCalls ?? []).join(', ')
  error.value = ''
}, { immediate: true })

function submit() {
  error.value = ''
  if (!editing.value && !dimension.value.trim()) { error.value = 'Dimension is required'; return }
  if (!question.value.trim()) { error.value = 'Question is required'; return }
  let assertions: Record<string, unknown>[]
  try {
    const parsed = JSON.parse(assertionsText.value || '[]')
    if (!Array.isArray(parsed)) throw new Error('not an array')
    assertions = parsed
  } catch {
    error.value = 'Assertions must be a JSON array, e.g. [{"type": "tool_called", "name": "execute_sql"}]'
    return
  }
  const expectedToolCalls = toolCallsText.value.split(',').map(value => value.trim()).filter(Boolean)
  emit('save', {
    id: editing.value ? id.value : (id.value.trim() || null),
    dimension: dimension.value.trim(),
    question: question.value.trim(),
    asUser: asUser.value.trim(),
    referenceSql: referenceSql.value.trim() || null,
    assertions,
    expectedToolCalls,
    source: props.prefill?.source ?? null,
    sourceConversationId: props.prefill?.sourceConversationId ?? null
  })
}
</script>

<template>
  <div class="modal-backdrop" role="presentation" @click.self="emit('close')">
    <section class="modal-card wide-modal" role="dialog" aria-modal="true" aria-label="Golden case form">
      <header class="modal-header">
        <div><p class="eyebrow">Golden Set</p><h2>{{ editing ? 'Edit case' : 'New case' }}</h2></div>
        <button class="icon-button" aria-label="Close" @click="emit('close')">×</button>
      </header>
      <p v-if="prefill?.actualOutput" class="candidate-output">
        <strong>Observed output ({{ prefill.sourceConversationId }}):</strong>
        <span>{{ prefill.actualOutput }}</span>
      </p>
      <form class="user-form" @submit.prevent="submit">
        <label>Id<input v-model="id" :disabled="editing" placeholder="auto-generated if left blank" /></label>
        <label>Dimension<input v-model="dimension" :disabled="editing" list="golden-dimensions" placeholder="sql_correctness" /></label>
        <datalist id="golden-dimensions">
          <option value="sql_correctness" /><option value="permission" /><option value="masking" />
          <option value="empty_result" /><option value="reproducibility" /><option value="cost" />
        </datalist>
        <label>Question<textarea v-model="question" rows="2" /></label>
        <label>As user<input v-model="asUser" placeholder="admin" /></label>
        <label>Reference SQL<textarea v-model="referenceSql" rows="2" /></label>
        <label>Expected tool calls (comma separated)<input v-model="toolCallsText" placeholder="execute_sql, lookup_glossary" /></label>
        <label>Assertions (JSON array)<textarea v-model="assertionsText" rows="6" class="mono" /></label>
        <p class="hint">Supported assertion types: <code>{{ ASSERTION_TYPES.join(', ') }}</code></p>
        <p v-if="error" class="error" role="alert">{{ error }}</p>
        <footer class="modal-actions">
          <button type="button" class="secondary-button" @click="emit('close')">Cancel</button>
          <button type="submit" class="primary-button">Save</button>
        </footer>
      </form>
    </section>
  </div>
</template>
