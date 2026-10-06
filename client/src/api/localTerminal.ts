/**
 * local PTY terminal API(Tauri invoke wrap)
 *
 * corresponds to Rust side local_pty.rs Tauri Commands
 */
import { invoke } from '@tauri-apps/api/core'
import { listen, type UnlistenFn } from '@tauri-apps/api/event'

/** PTY output event payload */
export interface LocalPtyOutputEvent {
  session_id: string
  data: string
}

/** PTY exit event payload */
export interface LocalPtyExitEvent {
  session_id: string
  exit_code: number
}

/** active PTY session info */
export interface LocalPtySession {
  session_id: string
}

/** create local PTY terminal session */
export function spawnLocalPty(params: {
  sessionId: string
  cwd?: string
  cols?: number
  rows?: number
}): Promise<void> {
  return invoke('spawn_local_pty', {
    sessionId: params.sessionId,
    cwd: params.cwd,
    cols: params.cols,
    rows: params.rows,
  })
}

/** write data to PTY */
export function writeToPty(sessionId: string, data: string): Promise<void> {
  return invoke('write_to_pty', { sessionId, data })
}

/** resize PTY terminal size */
export function resizeLocalPty(sessionId: string, cols: number, rows: number): Promise<void> {
  return invoke('resize_local_pty', { sessionId, cols, rows })
}

/** close PTY session */
export function killLocalPty(sessionId: string): Promise<void> {
  return invoke('kill_local_pty', { sessionId })
}

/** column out all active PTY session */
export function listLocalPtys(): Promise<LocalPtySession[]> {
  return invoke('list_local_ptys')
}

/** listen PTY output event */
export function onLocalPtyOutput(
  sessionId: string,
  callback: (data: string) => void,
): Promise<UnlistenFn> {
  return listen<LocalPtyOutputEvent>('local-pty-output', (event) => {
    if (event.payload.session_id === sessionId) {
      callback(event.payload.data)
    }
  })
}

/** listen PTY exit event */
export function onLocalPtyExit(
  sessionId: string,
  callback: (exitCode: number) => void,
): Promise<UnlistenFn> {
  return listen<LocalPtyExitEvent>('local-pty-exit', (event) => {
    if (event.payload.session_id === sessionId) {
      callback(event.payload.exit_code)
    }
  })
}
