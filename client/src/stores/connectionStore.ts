import { create } from 'zustand'
import type { SSHConnection, ServerStatus } from '../types'
import { ConnectionStatus } from '../types'
import * as sshApi from '../api/sshConnection'
import type { SshConnectionDTO, SshConnectionPayload } from '../api/sshConnection'

interface ConnectionStore {
  // connection list
  connections: SSHConnection[]
  // current selected connection
  currentConnectionId: string | null
  // server status
  serverStatus: ServerStatus
  // list loading
  loading: boolean
  // action error info
  error: string | null

  // get connection list
  fetchConnections: (userId?: string) => Promise<void>
  // create connection
  createConnection: (payload: SshConnectionPayload) => Promise<boolean>
  // update connection
  updateConnection: (payload: SshConnectionPayload) => Promise<boolean>
  // delete connection
  removeConnection: (id: string) => Promise<void>
  // selected connection
  selectConnection: (id: string | null) => void
  // get single connection detail info
  getConnectionDetail: (id: string) => SSHConnection | undefined
  // update server status
  setServerStatus: (status: Partial<ServerStatus>) => void
  // clear remove error
  clearError: () => void
  // establish SSH connection
  connect: (id: string) => Promise<boolean>
  // disconnect SSH connection
  disconnect: (id: string) => Promise<boolean>

  // ===== heartbeat detect =====
  /** start heartbeat: set period check connected SSH status */
  startHeartbeat: () => void
  /** stop heartbeat */
  stopHeartbeat: () => void
}

/** backend DTO → frontend template type */
function dtoToConnection(dto: SshConnectionDTO): SSHConnection {
  return {
    id: dto.connectionId,
    name: dto.connectionName,
    host: dto.host,
    port: dto.port,
    username: dto.username,
    authType: dto.authType,
    status: dto.status,
    createdAt: new Date(dto.createdAt).getTime(),
    updatedAt: dto.updatedAt ? new Date(dto.updatedAt).getTime() : Date.now(),
  }
}

let heartbeatTimer: ReturnType<typeof setInterval> | null = null
/** consecutive fail nth number(for point number backoff) */
let heartbeatFailCount = 0
/** next heartbeat timestamp */
let nextHeartbeatAt = 0

export const useConnectionStore = create<ConnectionStore>((set, get) => ({
  connections: [],
  currentConnectionId: null,
  serverStatus: {
    connected: false,
    url: '',
  },
  loading: false,
  error: null,

  fetchConnections: async (userId = 'default') => {
    set({ loading: true, error: null })
    try {
      const res = await sshApi.getConnectionList(userId)
      if (res.code === '0000' && res.data) {
        const connections = res.data.map(dtoToConnection)
        set({ connections, serverStatus: { ...get().serverStatus, connected: true } })
      } else {
        set({ error: res.info || 'Failed to load connections' })
      }
    } catch {
      set({ error: 'Network error; cannot reach the server' })
    } finally {
      set({ loading: false })
    }
  },

  createConnection: async (payload) => {
    set({ error: null })
    try {
      const res = await sshApi.createConnection(payload)
      if (res.code === '0000' && res.data) {
        // create success back refresh list
        await get().fetchConnections()
        // auto selected new create connection
        const newConn = dtoToConnection(res.data)
        set({ currentConnectionId: newConn.id })
        return true
      }
      set({ error: res.info || 'Failed to create connection' })
      return false
    } catch {
      set({ error: 'Network error; cannot reach the server' })
      return false
    }
  },

  updateConnection: async (payload) => {
    set({ error: null })
    try {
      const res = await sshApi.updateConnection(payload)
      if (res.code === '0000' && res.data) {
        // update success back refresh list
        await get().fetchConnections()
        return true
      }
      set({ error: res.info || 'Failed to update connection' })
      return false
    } catch {
      set({ error: 'Network error; cannot reach the server' })
      return false
    }
  },

  removeConnection: async (id) => {
    set({ error: null })
    try {
      const res = await sshApi.deleteConnection(id)
      if (res.code === '0000') {
        set((state) => ({
          connections: state.connections.filter((c) => c.id !== id),
          currentConnectionId: state.currentConnectionId === id ? null : state.currentConnectionId,
        }))
      } else {
        set({ error: res.info || 'Failed to delete connection' })
      }
    } catch {
      set({ error: 'Network error; cannot reach the server' })
    }
  },

  selectConnection: (id) => set({ currentConnectionId: id }),

  getConnectionDetail: (id) => {
    return get().connections.find((c) => c.id === id)
  },

  setServerStatus: (status) =>
    set((state) => ({
      serverStatus: { ...state.serverStatus, ...status },
    })),

  clearError: () => set({ error: null }),

  connect: async (id) => {
    set({ error: null })
    try {
      const res = await sshApi.connect(id)
      if (res.code === '0000') {
        // update local status as connected
        set((state) => ({
          connections: state.connections.map((c) =>
            c.id === id ? { ...c, status: 1 } : c
          ),
        }))
        return true
      }
      set({ error: res.info || 'Connection failed' })
      return false
    } catch {
      set({ error: 'Network error; cannot reach the server' })
      return false
    }
  },

  disconnect: async (id) => {
    set({ error: null })
    try {
      const res = await sshApi.disconnect(id)
      if (res.code === '0000') {
        // update local status as disconnected
        set((state) => ({
          connections: state.connections.map((c) =>
            c.id === id ? { ...c, status: 0 } : c
          ),
        }))
        return true
      }
      set({ error: res.info || 'Failed to disconnect' })
      return false
    } catch {
      set({ error: 'Network error; cannot reach the server' })
      return false
    }
  },

  // ===== heartbeat detect =====
  startHeartbeat: () => {
    if (heartbeatTimer) return // already started

    // use 3s polling middle gap, internally applies backoff before actually sending the request
    heartbeatTimer = setInterval(async () => {
      const now = Date.now()
      // point number backoff: not yet to next heartbeat time then skip
      if (now < nextHeartbeatAt) return

      const state = get()
      const activeConns = state.connections.filter((c) => c.status === ConnectionStatus.CONNECTED)
      if (activeConns.length === 0) return

      let anyFailed = false
      for (const conn of activeConns) {
        try {
          const res = await sshApi.getConnection(conn.id)
          if (res.code === '0000' && res.data) {
            if (res.data.status !== conn.status) {
              set((s) => ({
                connections: s.connections.map((c) =>
                  c.id === conn.id ? { ...c, status: res.data!.status } : c
                ),
              }))
            }
          }
        } catch {
          anyFailed = true
          set((s) => ({
            connections: s.connections.map((c) =>
              c.id === conn.id ? { ...c, status: ConnectionStatus.FAILED } : c
            ),
          }))
        }
      }

      // point number backoff: on failure add large middle gap, success when reset
      if (anyFailed) {
        heartbeatFailCount++
        // backoff middle gap:10s, 20s, 40s, 80s... max 5 min
        const backoffMs = Math.min(10_000 * Math.pow(2, heartbeatFailCount - 1), 300_000)
        nextHeartbeatAt = now + backoffMs
        console.warn(`[Heartbeat] ${heartbeatFailCount} consecutive failures; next heartbeat in ${backoffMs / 1000}s`)
      } else {
        heartbeatFailCount = 0
        nextHeartbeatAt = now + 10_000 // align always middle gap 10s
      }
    }, 3_000) // polling middle gap 3s(actual request rate is controlled by backoff)
  },

  stopHeartbeat: () => {
    if (heartbeatTimer) {
      clearInterval(heartbeatTimer)
      heartbeatTimer = null
    }
    heartbeatFailCount = 0
    nextHeartbeatAt = 0
  },
}))
