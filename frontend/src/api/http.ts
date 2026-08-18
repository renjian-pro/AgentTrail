import { clearToken, readToken } from './auth-token'

/**
 * 带鉴权的 fetch：附上 Bearer token、把 401 统一处理成"清 token + 回登录页"。
 *
 * <p>**所有访问 `/agent`、`/api` 的请求都必须走这里**，包括下载文件。token 存在 localStorage、
 * 靠请求头送出，而浏览器导航（`<a href>`、`window.open`、表单提交）带不上任何自定义请求头——
 * 一个看起来完全正常的下载链接会稳定地拿到 401 AUTH_REQUIRED，而且是在新标签页里报错，
 * 页面上的错误提示一条都看不到。解法不是把 token 拼进 query（那会让它进浏览器历史、
 * 服务端访问日志和 Referer），而是用这条通道把文件取回来再存盘，见 requestFile。
 */
async function authorizedFetch(input: RequestInfo, init?: RequestInit): Promise<Response> {
  const headers = new Headers(init?.headers)
  const token = readToken()
  if (token && !headers.has('Authorization')) headers.set('Authorization', `Bearer ${token}`)
  const response = await fetch(input, { ...init, headers })
  if (response.status === 401) {
    clearToken()
    if (typeof window !== 'undefined' && window.location.pathname !== '/login') {
      try { window.location.href = '/login' } catch { /* jsdom and embedded hosts may reject navigation */ }
    }
  }
  if (!response.ok) throw new Error(await responseErrorMessage(response) || `Request failed (${response.status})`)
  return response
}

export async function request<T>(input: RequestInfo, init?: RequestInit): Promise<T> {
  const response = await authorizedFetch(input, init)
  if (response.status === 204) return undefined as T
  const body = await response.text()
  return (body ? JSON.parse(body) : undefined) as T
}

/**
 * 取一个二进制产物（PPT/导出文件）。文件名优先用服务端 `Content-Disposition` 里的那个——
 * 后端已经按 RFC 5987 编码好了中文名，前端自己拼一个只会和它漂移。
 */
export async function requestFile(input: RequestInfo, fallbackName: string, init?: RequestInit)
    : Promise<{ blob: Blob; filename: string }> {
  const response = await authorizedFetch(input, init)
  return {
    blob: await response.blob(),
    filename: filenameFromDisposition(response.headers.get('Content-Disposition')) ?? fallbackName
  }
}

/** `filename*=UTF-8''xxx` 优先于 `filename="xxx"`：前者才带得了中文，后者是给老客户端的降级副本。 */
function filenameFromDisposition(disposition: string | null): string | undefined {
  if (!disposition) return undefined
  const encoded = /filename\*=UTF-8''([^;]+)/i.exec(disposition)
  if (encoded) {
    try { return decodeURIComponent(encoded[1]) } catch { /* 编码坏了就退回下面的普通 filename */ }
  }
  return /filename="?([^";]+)"?/i.exec(disposition)?.[1]
}

/**
 * 把取回来的文件交给浏览器保存。object URL 用完必须 revoke——不 revoke 的话这份 blob
 * 会一直挂在文档上，几十 MB 的 PPT 连下几次就是几百 MB 常驻内存。
 */
export function saveBlob(blob: Blob, filename: string): void {
  const url = URL.createObjectURL(blob)
  try {
    const link = document.createElement('a')
    link.href = url
    link.download = filename
    document.body.appendChild(link)
    link.click()
    link.remove()
  } finally {
    URL.revokeObjectURL(url)
  }
}

export function jsonInit(body: unknown): RequestInit {
  return { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) }
}

export function toErrorMessage(error: unknown): string {
  return error instanceof Error ? error.message : String(error)
}

async function responseErrorMessage(response: Response): Promise<string> {
  const text = await response.text()
  if (!text) return ''
  try {
    const body = JSON.parse(text) as { message?: string; error?: string }
    return body.message ?? body.error ?? text
  } catch {
    return text
  }
}
