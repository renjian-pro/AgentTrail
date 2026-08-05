import { request } from '../../api/http'

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

export const evaluationApi = {
  run: () => request<EvaluationTask>('/agent/v1/evaluation/run', { method: 'POST' }),
  status: (taskId: string) => request<EvaluationTask>(`/agent/v1/evaluation/${taskId}`),
  history: () => request<EvaluationHistoryItem[]>('/agent/v1/evaluation/history')
}
