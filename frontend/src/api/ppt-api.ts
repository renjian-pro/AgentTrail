import { jsonInit, request } from './http'
export type PptTask = {
  taskId: number
  status: string
  errorMsg: string | null
  outputPath: string | null
  // 只在 status === 'AWAITING_INPUT' 时非空：状态机判定需求不够清晰，停下来等用户补充。
  // 和 errorMsg 互斥——追问不是错误，卡片要渲染成一个回答框而不是一条报错。
  clarifyingQuestion?: string | null
}
export const pptApi = {
  // /create 和 /resume 现在都是异步的：请求一返回就代表"任务已提交/已继续"，不代表跑完了——
  // status 字段是当时的 checkpoint（八成还是 INIT 或者上一次失败时停留的那个状态），
  // 要看到真正的进度得配合下面的 status() 轮询。
  create: (conversationId: string, message: string) => request<PptTask>('/agent/v1/ppt/create', jsonInit({ conversationId, message })),
  resume: (taskId: number) => request<PptTask>(`/agent/v1/ppt/resume/${taskId}`, { method: 'POST' }),
  // 回答澄清追问：后端把回答并回需求、checkpoint 推到 REQUIREMENT 再继续跑，和 /resume 一样是
  // 提交即返回。只追问一轮——CLARIFY 被整个跳过，所以不会出现答完又被问一遍。
  clarify: (taskId: number, answer: string) =>
    request<PptTask>(`/agent/v1/ppt/clarify/${taskId}`, jsonInit({ answer })),
  status: (taskId: number) => request<PptTask>(`/agent/v1/ppt/${taskId}`)
}
