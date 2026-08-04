import { jsonInit, request } from '../../api/http'

export type SysRole = { id: number; code: string; name: string; dataScope: string; sort: number; status: string; createdAt: number; updatedAt: number }
export type SysPermission = { id: number; code: string; name: string; module: string; createdAt: number }
export type DeptTreeNode = { id: number; name: string; parentId: number; sort: number; children: DeptTreeNode[] }
export type UserVO = { id: number; username: string; nickname: string; status: string; roleIds: number[]; roleCodes: string[]; deptIds: number[]; createdAt: number; updatedAt: number }
export type UserPage = { items: UserVO[]; total: number; page: number; size: number }
export type UserCreate = { username: string; password: string; nickname: string; roleIds: number[]; deptIds: number[] }
export type UserUpdate = { nickname: string; roleIds: number[]; deptIds: number[] }

export const sysApi = {
  roles: () => request<SysRole[]>('/api/sys/roles'),
  permissions: () => request<Record<string, SysPermission[]>>('/api/sys/permissions'),
  rolePermissions: (roleId: number) => request<SysPermission[]>(`/api/sys/roles/${roleId}/permissions`),
  replaceRolePermissions: (roleId: number, permissionIds: number[]) => request<SysPermission[]>(`/api/sys/roles/${roleId}/permissions`, { ...jsonInit({ permissionIds }), method: 'PUT' }),
  users: (keyword = '', page = 0, size = 20) => request<UserPage>(`/api/sys/users?keyword=${encodeURIComponent(keyword)}&page=${page}&size=${size}`),
  createUser: (body: UserCreate) => request<UserVO>('/api/sys/users', jsonInit(body)),
  updateUser: (id: number, body: UserUpdate) => request<UserVO>(`/api/sys/users/${id}`, { ...jsonInit(body), method: 'PUT' }),
  updateUserStatus: (id: number, status: string) => request<void>(`/api/sys/users/${id}/status`, { ...jsonInit({ status }), method: 'PATCH' }),
  deleteUser: (id: number) => request<void>(`/api/sys/users/${id}`, { method: 'DELETE' }),
  depts: () => request<DeptTreeNode[]>('/api/sys/depts')
}
