/**
 * SSH agent API
 * extract for agent session and SSH terminal bind can force
 */
import { get, post } from './request'

const BASE = '/api/v1'

// ===== types =====

/** bind terminal request */
export interface BindTerminalPayload {
  chatSessionId: string
  terminalSessionId: string
}

/** bind terminal response */
export interface BindTerminalResponse {
  chatSessionId: string
  terminalSessionId: string
  bound: boolean
}

/** check query bind response */
export interface QueryBindingResponse {
  chatSessionId: string
  terminalSessionId?: string
  bound: boolean
  message?: string
}

// ===== API method =====

/**
 * bind SSH terminal to agent session
 */
export function bindTerminal(payload: BindTerminalPayload) {
  return post<BindTerminalResponse>(`${BASE}/bind_terminal`, payload)
}

/**
 * unbind SSH terminal
 */
export function unbindTerminal(chatSessionId: string) {
  return post<void>(`${BASE}/unbind_terminal`, undefined, { chatSessionId })
}

/**
 * check query session bind terminal
 */
export function queryBinding(chatSessionId: string) {
  return get<QueryBindingResponse>(`${BASE}/query_binding`, { chatSessionId })
}
