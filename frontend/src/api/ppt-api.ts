import { jsonInit, request, requestFile } from './http'
export type PptState = string
export type PptRunStatus = string
export type PptFailure = {
  code: string
  failedStage: PptState
  retryable: boolean
  retryClass: string
  attempt: number
  userMessage: string
  occurredAtMillis: number
}
export type PptWarning = { code: string; message: string; stage: PptState | null }
export type PptArtifact = {
  artifactId: string
  objectKey: string
  checksum: string
  sizeBytes: number
  contentType: string
}
export type PptTaskCapabilities = {
  canCancel: boolean
  canResume: boolean
  canAnswer: boolean
  canDownload: boolean
  canModify: boolean
}
export type PptProgressEvent = {
  sequence: number
  stage: PptState
  level: 'STAGE' | 'DETAIL' | string
  status: 'STARTED' | 'COMPLETED' | 'WARNING' | 'FAILED' | 'CANCELLED' | string
  message: string
  current: number | null
  total: number | null
  occurredAtMillis: number
}
export type PptTaskView = {
  taskId: number
  conversationId: string
  operation: 'CREATE' | 'MODIFY' | 'RESUME' | string
  pipelineState: PptState
  runStatus: PptRunStatus
  revision: number
  currentStageLabel: string
  completedStages: PptState[]
  progressPercent: number
  progressEvents: PptProgressEvent[]
  clarification: string | null
  failure: PptFailure | null
  warnings: PptWarning[]
  artifact: PptArtifact | null
  baseTaskId: number | null
  baseArtifactId: string | null
  createdAtMillis: number
  updatedAtMillis: number
  capabilities: PptTaskCapabilities
}
export type PptTask = {
  taskId: number
  status: string
  errorMsg: string | null
  outputPath: string | null
  // 只在 status === 'AWAITING_INPUT' 时非空：状态机判定需求不够清晰，停下来等用户补充。
  // 和 errorMsg 互斥——追问不是错误，卡片要渲染成一个回答框而不是一条报错。
  clarifyingQuestion?: string | null
  /** 新统一视图；旧历史消息没有该字段时，卡片继续使用上面的兼容字段。 */
  taskView?: PptTaskView | null
}
export type PptConversationResponse = {
  kind: 'MESSAGE' | 'TASK'
  assistantMessage: string | null
  task: PptTask | null
}
export const pptApi = {
  /**
   * PPT 模式的会话入口。需求未确认时只返回普通助手消息；确认完成或操作已有任务时才返回 task。
   */
  converse: (conversationId: string, message: string, idempotencyKey?: string) =>
    request<PptConversationResponse>('/agent/v1/ppt/converse',
      jsonInit({ conversationId, message, idempotencyKey })),
  /** PPT 的唯一会话写入口；后端根据活跃任务状态识别新建、回答、修改、继续和取消。 */
  message: (conversationId: string, message: string, idempotencyKey?: string) =>
    request<PptTask>('/agent/v1/ppt/message', jsonInit({ conversationId, message, idempotencyKey })),
  // /create 和 /resume 现在都是异步的：请求一返回就代表"任务已提交/已继续"，不代表跑完了——
  // status 字段是当时的 checkpoint（八成还是 INIT 或者上一次失败时停留的那个状态），
  // 要看到真正的进度得配合下面的 status() 轮询。
  create: (conversationId: string, message: string, idempotencyKey?: string) =>
    request<PptTask>('/agent/v1/ppt/create', jsonInit({ conversationId, message, idempotencyKey })),
  resume: (taskId: number) => request<PptTask>(`/agent/v1/ppt/resume/${taskId}`, { method: 'POST' }),
  // 回答澄清追问：后端把回答并回需求、checkpoint 推到 REQUIREMENT 再继续跑，和 /resume 一样是
  // 提交即返回。只追问一轮——CLARIFY 被整个跳过，所以不会出现答完又被问一遍。
  clarify: (taskId: number, answer: string) =>
    request<PptTask>(`/agent/v1/ppt/clarify/${taskId}`, jsonInit({ answer })),
  cancel: (taskId: number) => request<PptTask>(`/agent/v1/ppt/${taskId}/cancel`, { method: 'POST' }),
  modify: (taskId: number, message: string, idempotencyKey?: string) =>
    request<PptTask>(`/agent/v1/ppt/${taskId}/modify`, jsonInit({ message, idempotencyKey })),
  status: (taskId: number) => request<PptTask>(`/agent/v1/ppt/${taskId}`),
  conversation: (conversationId: string) => request<PptTask[]>(`/agent/v1/ppt/conversation/${encodeURIComponent(conversationId)}`),
  batchStatus: (taskIds: number[]) => request<PptTask[]>(`/agent/v1/ppt/tasks?ids=${taskIds.join(',')}`),
  // 下载必须走 requestFile 而不是 <a href>：鉴权是 Authorization 请求头，浏览器导航带不上，
  // 裸链接会稳定地拿到 401（见 http.ts 的 authorizedFetch 注释）。
  download: (taskId: number) => requestFile(`/agent/v1/ppt/${taskId}/download`, `ppt-${taskId}.pptx`)
}
