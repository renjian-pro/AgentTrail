import { clearToken, readToken } from './auth-token'

export async function request<T>(input: RequestInfo, init?: RequestInit): Promise<T> {
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
  return response.json() as Promise<T>
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
