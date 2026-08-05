import { request } from './http'
export type AttachedFile = { fileId: number; fileName: string; kind: string; sizeBytes: number; parsedTextLength: number; routedToRag: boolean }
export const fileApi = {
  upload: async (conversationId: string, file: File) => { const data = new FormData(); data.append('conversationId', conversationId); data.append('file', file); return request<AttachedFile>('/agent/v1/files', { method: 'POST', body: data }) },
  content: (fileId: number, question?: string) => request<{ content: string }>(`/agent/v1/files/${fileId}/content${question ? '?question=' + encodeURIComponent(question) : ''}`),
  remove: (fileId: number) => request<void>(`/agent/v1/files/${fileId}`, { method: 'DELETE' })
}
