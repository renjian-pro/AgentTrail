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

export type ResearchTaskStatus = 'RUNNING' | 'SUCCESS' | 'FAILED'
export type ResearchTask = { taskId: number; status: ResearchTaskStatus; report: DeepResearchReport | null; errorMsg: string | null }

export const researchApi = {
  // 请求一返回就代表"任务已提交"，status 永远是 RUNNING——真正的报告要靠下面的 status() 轮询。
  run: (conversationId: string, question: string) =>
    request<ResearchTask>('/agent/v1/deepresearch', jsonInit({ conversationId, question })),
  status: (taskId: number) => request<ResearchTask>(`/agent/v1/deepresearch/${taskId}`)
}
