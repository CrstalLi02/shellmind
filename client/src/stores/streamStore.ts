/**
 * SSE stream connection status Store
 *
 * manage SSE connection health health status, break line reconnect, heartbeat timeout detect.
 * corresponds to UI: StreamStatusBar component(show connection status horizontal width)
 */

import { create } from 'zustand'
import { chatConfig } from '../config/chat'

export type StreamStatus = 'idle' | 'connecting' | 'streaming' | 'reconnecting' | 'disconnected' | 'error'

export interface StreamState {
  /** current connection status */
  status: StreamStatus
  /** retry nth number */
  retryCount: number
  /** max retry nth number */
  maxRetries: number
  /** most back one nth error message */
  lastError: string | null
  /** most back one nth receive heartbeat/ data timestamp */
  lastActivityAt: number | null
  /** heartbeat timeout threshold(ms), default 60s */
  heartbeatTimeoutMs: number
  /** status update message(from backend status event) */
  statusMessage: string | null

  // Actions
  setStatus: (status: StreamStatus) => void
  setRetrying: (count: number) => void
  setError: (err: string | null) => void
  touchActivity: () => void
  setStatusMessage: (msg: string | null) => void
  reset: () => void

  /** check heartbeat whether timeout */
  isHeartbeatStale: () => boolean
}

export const useStreamStore = create<StreamState>((set, get) => ({
  status: 'idle',
  retryCount: 0,
  maxRetries: chatConfig.maxRetries,
  lastError: null,
  lastActivityAt: null,
  heartbeatTimeoutMs: chatConfig.heartbeatTimeout,
  statusMessage: null,

  setStatus: (status) => set({ status }),
  setRetrying: (count) => set({ retryCount: count, status: count > 0 ? 'reconnecting' : 'streaming' }),
  setError: (err) => set({ lastError: err, status: err ? 'error' : 'idle' }),
  touchActivity: () => set({ lastActivityAt: Date.now() }),
  setStatusMessage: (msg) => set({ statusMessage: msg }),
  reset: () => set({
    status: 'idle',
    retryCount: 0,
    lastError: null,
    lastActivityAt: null,
    statusMessage: null,
  }),

  isHeartbeatStale: () => {
    const { lastActivityAt, heartbeatTimeoutMs } = get()
    if (!lastActivityAt) return false
    return Date.now() - lastActivityAt > heartbeatTimeoutMs
  },
}))
