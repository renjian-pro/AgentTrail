import { jsonInit, request } from './http'
export type PptTask = { taskId: number; status: string; errorMsg: string | null; outputPath: string | null }
export const pptApi = {
  // /create 和 /resume 现在都是异步的：请求一返回就代表"任务已提交/已继续"，不代表跑完了——
  // status 字段是当时的 checkpoint（八成还是 INIT 或者上一次失败时停留的那个状态），
  // 要看到真正的进度得配合下面的 status() 轮询。
  create: (conversationId: string, message: string) => request<PptTask>('/agent/v1/ppt/create', jsonInit({ conversationId, message })),
  resume: (taskId: number) => request<PptTask>(`/agent/v1/ppt/resume/${taskId}`, { method: 'POST' }),
  status: (taskId: number) => request<PptTask>(`/agent/v1/ppt/${taskId}`)
}
