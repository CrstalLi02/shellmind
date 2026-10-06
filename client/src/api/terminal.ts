/**
 * SSH terminal API
 */
import { get, post } from './request'

const BASE = '/api/v1/ssh/terminal'

// ===== types =====

/** Open a terminal session */
export interface TerminalOpenPayload {
  connectionId: string
  cols?: number
  rows?: number
}

/** Open a terminal session */
export interface TerminalOpenResponse {
  sessionId: string
  connectionId: string
  initialOutput: string
}

/** Run a command (full-line mode, legacy behavior) */
export interface TerminalExecPayload {
  sessionId: string
  command: string
}

/** Command result */
export interface TerminalExecResponse {
  output: string
}

/** Write raw input to the terminal (byte-by-byte) */
export interface TerminalWritePayload {
  sessionId: string
  input: string
}

/** Buffered terminal output */
export interface TerminalReadResponse {
  output: string
}

/** Resize the terminal */
export interface TerminalResizePayload {
  sessionId: string
  cols: number
  rows: number
}

// ===== API methods =====

/** Open a terminal session */
export function openTerminal(payload: TerminalOpenPayload) {
  return post<TerminalOpenResponse>(`${BASE}/open`, payload)
}

/** Run a command (full-line mode) */
export function execCommand(payload: TerminalExecPayload) {
  return post<TerminalExecResponse>(`${BASE}/exec`, payload)
}

/** Write raw input (byte-by-byte; the shell handles echo) */
export function writeInput(payload: TerminalWritePayload) {
  return post<void>(`${BASE}/write`, payload)
}

/** Read buffered terminal output (polling) */
export function readOutput(sessionId: string) {
  return get<TerminalReadResponse>(`${BASE}/read`, { sessionId })
}

/** Resize the terminal */
export function resizeTerminal(payload: TerminalResizePayload) {
  return post<void>(`${BASE}/resize`, payload)
}

/** Close a terminal session */
export function closeTerminal(sessionId: string) {
  return post<void>(`${BASE}/close`, undefined, { sessionId })
}
