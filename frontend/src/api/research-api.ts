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

export type ResearchTaskStatus = 'RUNNING' | 'SUCCESS' | 'FAILED' | 'CANCELLED'
export type ResearchStep = 'CLARIFYING' | 'PLANNING' | 'SEARCHING' | 'CRITIQUING' | 'SUMMARIZING'
export type ResearchTask = {
  taskId: number
  status: ResearchTaskStatus
  report: DeepResearchReport | null
  errorMsg: string | null
  currentStep?: ResearchStep | null
}

export const researchApi = {
  // 请求一返回就代表"任务已提交"，status 永远是 RUNNING——真正的报告要靠下面的 status() 轮询。
  run: (conversationId: string, question: string) =>
    request<ResearchTask>('/agent/v1/deepresearch', jsonInit({ conversationId, question })),
  // previousQuestion/previousClarifyingQuestion 原样带回上一轮的追问，让后端跳过再次澄清、
  // 直接把这次回复拼回原始请求继续研究——只追问一轮，不管信息是否依然不足都不再打断。
  reply: (conversationId: string, previousQuestion: string, previousClarifyingQuestion: string, answer: string) =>
    request<ResearchTask>('/agent/v1/deepresearch', jsonInit({
      conversationId, question: answer, previousQuestion, previousClarifyingQuestion
    })),
  status: (taskId: number) => request<ResearchTask>(`/agent/v1/deepresearch/${taskId}`)
}
