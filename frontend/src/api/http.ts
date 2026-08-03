export async function request<T>(input: RequestInfo, init?: RequestInit): Promise<T> {
  const response = await fetch(input, init)
  if (!response.ok) throw new Error(await response.text() || `请求失败 (${response.status})`)
  return response.json() as Promise<T>
}

export function jsonInit(body: unknown): RequestInit {
  return { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) }
}

export function toErrorMessage(error: unknown): string {
  return error instanceof Error ? error.message : String(error)
}
