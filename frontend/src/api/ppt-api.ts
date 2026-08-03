import { jsonInit, request } from './http'
export type PptTask = { taskId: number; status: string; errorMsg: string | null; outputPath: string | null }
export const pptApi = {
  create: (conversationId: string, message: string) => request<PptTask>('/agent/v1/ppt/create', jsonInit({ conversationId, message })),
  resume: (taskId: number) => request<PptTask>(`/agent/v1/ppt/resume/${taskId}`, { method: 'POST' })
}
