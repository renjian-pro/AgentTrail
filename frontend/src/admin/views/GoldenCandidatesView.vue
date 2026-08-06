<script setup lang="ts">
import { ref } from 'vue'
import { toErrorMessage } from '../../api/http'
import { evaluationApi, type GoldenCaseCandidate, type GoldenCaseRequest, type GoldenCaseSource } from '../api/evaluation-api'
import type { ConversationSummary } from '../../api/chat-api'
import GoldenCaseFormDialog from '../components/GoldenCaseFormDialog.vue'

const conversations = ref<ConversationSummary[]>([])
const page = ref(0)
const hasMore = ref(false)
const selected = ref<string | null>(null)
const candidates = ref<GoldenCaseCandidate[]>([])
const error = ref('')
const loadingConversations = ref(false)
const loadingCandidates = ref(false)
const promoteDraft = ref<{ question: string; actualOutput: string; source: GoldenCaseSource; sourceConversationId: string } | null>(null)

async function loadConversations() {
  error.value = ''
  loadingConversations.value = true
  try {
    const result = await evaluationApi.conversations(page.value, 20)
    conversations.value = result.sessions
    hasMore.value = result.hasMore
  } catch (cause) { error.value = toErrorMessage(cause) } finally { loadingConversations.value = false }
}
async function select(conversationId: string) {
  selected.value = conversationId
  candidates.value = []
  error.value = ''
  loadingCandidates.value = true
  try { candidates.value = await evaluationApi.candidates(conversationId) }
  catch (cause) { error.value = toErrorMessage(cause) }
  finally { loadingCandidates.value = false }
}
function promote(candidate: GoldenCaseCandidate) {
  promoteDraft.value = { question: candidate.question, actualOutput: candidate.actualOutput,
    source: 'PROMOTED', sourceConversationId: candidate.conversationId }
}
async function save(body: GoldenCaseRequest) {
  try {
    await evaluationApi.createCase(body)
    promoteDraft.value = null
  } catch (cause) { error.value = toErrorMessage(cause) }
}
function changePage(delta: number) { page.value = Math.max(0, page.value + delta); void loadConversations() }
void loadConversations()
</script>

<template>
  <main class="admin-page">
    <section class="feature-hero">
      <p class="eyebrow">Quality</p>
      <h1>Candidates</h1>
      <p>Pick a conversation to review its turns, then promote a good — or a bad — one into the Golden Set.</p>
    </section>

    <p v-if="error" class="error" role="alert">{{ error }}</p>

    <section class="admin-card candidate-layout">
      <div class="candidate-conversations">
        <h2>Conversations</h2>
        <p v-if="!conversations.length && !loadingConversations" class="empty-state">No conversations yet.</p>
        <ul class="candidate-list">
          <li v-for="item in conversations" :key="item.conversationId">
            <button :class="['candidate-list-item', { selected: item.conversationId === selected }]" @click="select(item.conversationId)">
              <strong>{{ item.title }}</strong>
              <code>{{ item.conversationId }}</code>
            </button>
          </li>
        </ul>
        <div class="pagination">
          <button class="secondary-button" :disabled="page <= 0" @click="changePage(-1)">Previous</button>
          <span>Page {{ page + 1 }}</span>
          <button class="secondary-button" :disabled="!hasMore" @click="changePage(1)">Next</button>
        </div>
      </div>

      <div class="candidate-detail">
        <h2>Candidates</h2>
        <p v-if="!selected" class="empty-state">Select a conversation on the left.</p>
        <p v-else-if="!candidates.length && !loadingCandidates" class="empty-state">No candidates in this conversation.</p>
        <details v-for="candidate in candidates" :key="candidate.round" class="candidate-card">
          <summary>Round {{ candidate.round }} — {{ candidate.question }}</summary>
          <p class="candidate-output"><span>{{ candidate.actualOutput }}</span></p>
          <button class="primary-button" @click="promote(candidate)">Promote to Golden Case</button>
        </details>
      </div>
    </section>

    <GoldenCaseFormDialog v-if="promoteDraft" :existing="null" :prefill="promoteDraft" @close="promoteDraft = null" @save="save" />
  </main>
</template>
