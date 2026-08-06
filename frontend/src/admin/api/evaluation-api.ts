import { jsonInit, request } from '../../api/http'
import type { ConversationPage, ConversationSummary } from '../../api/chat-api'

export type GoldenObservation = {
  id: string
  dimension: string
  passed: boolean
  reason: string
  rounds: number
  elapsedMs: number
  actualSql: string
  actualResult: string
  toolCalls: string[]
  metrics: Record<string, unknown>
  question: string
}

export type GoldenTaskReport = { observations: GoldenObservation[] }

export type EvaluationStatus = 'RUNNING' | 'SUCCESS' | 'FAILED'

export type EvaluationTask = {
  taskId: string
  status: EvaluationStatus
  completedCases: number
  totalCases: number
  report: GoldenTaskReport | null
  error: string | null
}

export type EvaluationHistoryItem = {
  taskId: string
  status: EvaluationStatus
  startedAtMillis: number
  completedCases: number
  totalCases: number
  passRate: number
  dimensionPassRates: Record<string, number>
}

export type GoldenCaseSource = 'BUILTIN' | 'MANUAL' | 'PROMOTED'

export type GoldenCaseView = {
  id: string
  dimension: string
  question: string
  asUser: string
  referenceSql: string | null
  assertions: Record<string, unknown>[]
  expectedToolCalls: string[]
  source: GoldenCaseSource
  sourceConversationId: string | null
  editable: boolean
  createdAtMillis: number
  updatedAtMillis: number
}

export type GoldenCaseRequest = {
  id: string | null
  dimension: string
  question: string
  asUser: string
  referenceSql: string | null
  assertions: Record<string, unknown>[]
  expectedToolCalls: string[]
  source: GoldenCaseSource | null
  sourceConversationId: string | null
}

export type GoldenCaseCandidate = {
  conversationId: string
  round: number
  question: string
  actualOutput: string
  recordedAtMillis: number
  reviewStatus: string
}

export const evaluationApi = {
  run: () => request<EvaluationTask>('/agent/v1/evaluation/run', { method: 'POST' }),
  status: (taskId: string) => request<EvaluationTask>(`/agent/v1/evaluation/${taskId}`),
  history: () => request<EvaluationHistoryItem[]>('/agent/v1/evaluation/history'),
  cases: () => request<GoldenCaseView[]>('/agent/v1/evaluation/cases'),
  createCase: (body: GoldenCaseRequest) => request<GoldenCaseView>('/agent/v1/evaluation/cases', jsonInit(body)),
  updateCase: (id: string, body: GoldenCaseRequest) =>
    request<GoldenCaseView>(`/agent/v1/evaluation/cases/${encodeURIComponent(id)}`, { ...jsonInit(body), method: 'PUT' }),
  deleteCase: (id: string) => request<void>(`/agent/v1/evaluation/cases/${encodeURIComponent(id)}`, { method: 'DELETE' }),
  conversations: (page = 0, size = 20) =>
    request<ConversationPage>(`/agent/v1/evaluation/conversations?page=${page}&size=${size}`),
  candidates: (conversationId: string) =>
    request<GoldenCaseCandidate[]>(`/agent/v1/evaluation/conversations/${encodeURIComponent(conversationId)}/candidates`)
}
