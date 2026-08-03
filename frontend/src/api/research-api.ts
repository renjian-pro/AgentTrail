import { jsonInit, request } from './http'

export type ResearchTaskResult = {
  taskId: string
  instruction: string
  order: number
  output: string | null
  success: boolean
  errorMessage: string | null
}

export type DeepResearchReport = {
  needsClarification: boolean
  clarifyingQuestion: string | null
  researchTopic: string | null
  taskResults: ResearchTaskResult[]
  report: string | null
}

export const researchApi = {
  run: (conversationId: string, question: string) =>
    request<DeepResearchReport>('/agent/v1/deepresearch', jsonInit({ conversationId, question }))
}
