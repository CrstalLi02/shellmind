/**
 * SSH connection manage API
 */
import { get, post } from './request'

// ===== types =====

/** SSH connection response DTO(with backend SshConnectionResponseDTO align) */
export interface SshConnectionDTO {
  connectionId: string
  connectionName: string
  host: string
  port: number
  username: string
  authType: number     // 1- password, 2- private key
  status: number       // 0- disconnected, 1- connected, 2- connecting, 3- connection fail
  encrypted: number
  userId: string
  createdAt: string
  updatedAt: string
}

/** create/ update connection request body */
export interface SshConnectionPayload {
  connectionId?: string
  connectionName: string
  host: string
  port: number
  username: string
  authType: number
  password?: string
  privateKey?: string
  userId?: string
  connectTimeout?: number
  keepaliveInterval?: number
  startupCommand?: string
  compression?: boolean
  strictHostKeyCheck?: boolean
}

// ===== API method =====

const BASE = '/api/v1/ssh'

/** create SSH connection */
export function createConnection(payload: SshConnectionPayload) {
  return post<SshConnectionDTO>(`${BASE}/create_connection`, payload)
}

/** update SSH connection */
export function updateConnection(payload: SshConnectionPayload) {
  return post<SshConnectionDTO>(`${BASE}/update_connection`, payload)
}

/** delete SSH connection(POST + @RequestParam connectionId) */
export function deleteConnection(connectionId: string) {
  return post<void>(`${BASE}/delete_connection`, undefined, { connectionId })
}

/** check query single connection */
export function getConnection(connectionId: string) {
  return get<SshConnectionDTO>(`${BASE}/get_connection`, { connectionId })
}

/** check query connection list */
export function getConnectionList(userId = 'default') {
  return get<SshConnectionDTO[]>(`${BASE}/connection_list`, { userId })
}

/** establish SSH connection */
export function connect(connectionId: string) {
  return post<void>(`${BASE}/connect`, undefined, { connectionId })
}

/** disconnect SSH connection */
export function disconnect(connectionId: string) {
  return post<void>(`${BASE}/disconnect`, undefined, { connectionId })
}
