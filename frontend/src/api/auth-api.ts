import { jsonInit, request } from './http'

export type CurrentUser = { userId: number; username: string; nickname: string; roles: string[] }
export type LoginResponse = { token: string; user: CurrentUser }

export const authApi = {
  login: (username: string, password: string) => request<LoginResponse>('/api/auth/login', jsonInit({ username, password })),
  logout: () => request<void>('/api/auth/logout', { method: 'POST' }),
  info: () => request<CurrentUser>('/api/auth/info')
}
