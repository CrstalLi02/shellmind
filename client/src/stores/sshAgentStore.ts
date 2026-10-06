import { create } from 'zustand'
import * as sshAgentApi from '../api/sshAgent'

/**
 * SSH agent session bind Store
 * 
 * core feature:
 * 1. manage agent session and SSH terminal session bind off system
 * 2. auto bind current activate SSH connection to agent session
 * 3. allow manually adding server info to chat context
 * 4. allow adding selected terminal text to the chat
 */

interface SshAgentBinding {
  /** agent session ID */
  chatSessionId: string
  /** SSH terminal session ID */
  terminalSessionId: string
  /** connection ID */
  connectionId: string
  /** connection name */
  connectionName: string
  /** server info */
  serverInfo: {
    host: string
    port: number
    username: string
  }
  /** bind time */
  boundAt: number
}

/** input box tab */
export interface InputTag {
  /** tab ID */
  id: string
  /** tab show text(preview) */
  label: string
  /** complete whole content */
  fullContent: string
  /** tab type */
  type: 'terminal-selection' | 'file' | 'directory' | 'custom' | 'connection' | 'project'
  /** add time */
  addedAt: number
  /** connection info(only connection type) */
  connectionInfo?: {
    connectionId: string
    connectionName: string
    host: string
    port: number
    username: string
  }
}

interface SshAgentStore {
  // ===== bind status =====
  /** current activate bind */
  activeBinding: SshAgentBinding | null
  /** all bind note record(by chatSessionId index) */
  bindings: Map<string, SshAgentBinding>
  /** bind loading */
  isBinding: boolean
  /** bind error info */
  bindingError: string | null

  // ===== terminal session map =====
  /** connectionId → terminalSessionId map(by TerminalPanel maintain) */
  connectionSessionMap: Map<string, string>

  // ===== input box tab =====
  /** input box center tab list */
  inputTags: InputTag[]

  // ===== connection select widget =====
  /** whether show connection select widget */
  showConnectionSelector: boolean

  // ===== Actions =====
  /** bind the terminal to the agent session */
  bindTerminal: (
    chatSessionId: string,
    terminalSessionId: string,
    connectionInfo: {
      connectionId: string
      connectionName: string
      host: string
      port: number
      username: string
    }
  ) => Promise<boolean>

  /** unbind terminal */
  unbindTerminal: (chatSessionId: string) => Promise<void>

  /** check query bind status */
  queryBinding: (chatSessionId: string) => Promise<SshAgentBinding | null>

  /** settings current activate bind */
  setActiveBinding: (binding: SshAgentBinding | null) => void

  // ===== terminal session map action =====
  /** register connectionId → terminalSessionId map */
  registerConnectionSession: (connectionId: string, terminalSessionId: string) => void

  /** remove connectionId map */
  unregisterConnectionSession: (connectionId: string) => void

  /** via connectionId find terminalSessionId */
  getTerminalSessionByConnection: (connectionId: string) => string | undefined

  // ===== input box tab action =====
  /** add tab to input box */
  addInputTag: (tag: Omit<InputTag, 'id' | 'addedAt'>) => void

  /** remove point set tab */
  removeInputTag: (tagId: string) => void

  /** clear all tab */
  clearInputTags: () => void

  /** get all tab complete whole content(for send message) */
  getInputTagsContent: () => string

  // ===== connection select widget =====
  /** show connection select widget */
  openConnectionSelector: () => void

  /** close connection select widget */
  closeConnectionSelector: () => void

  /** clear remove bind error */
  clearBindingError: () => void

  /** get current session bind */
  getBindingByChatSession: (chatSessionId: string) => SshAgentBinding | undefined

  /** format server info as chat context */
  formatServerContext: (binding: SshAgentBinding) => string
}

export const useSshAgentStore = create<SshAgentStore>((set, get) => ({
  // ===== State =====
  activeBinding: null,
  bindings: new Map(),
  isBinding: false,
  bindingError: null,
  connectionSessionMap: new Map(),
  inputTags: [],
  showConnectionSelector: false,

  // ===== Actions =====
  bindTerminal: async (chatSessionId, terminalSessionId, connectionInfo) => {
    set({ isBinding: true, bindingError: null })
    try {
      const res = await sshAgentApi.bindTerminal({
        chatSessionId,
        terminalSessionId,
      })

      if (res.code === '0000' && res.data?.bound) {
        const binding: SshAgentBinding = {
          chatSessionId,
          terminalSessionId,
          connectionId: connectionInfo.connectionId,
          connectionName: connectionInfo.connectionName,
          serverInfo: {
            host: connectionInfo.host,
            port: connectionInfo.port,
            username: connectionInfo.username,
          },
          boundAt: Date.now(),
        }

        set((state) => {
          const bindings = new Map(state.bindings)
          bindings.set(chatSessionId, binding)
          return {
            bindings,
            activeBinding: binding,
            isBinding: false,
          }
        })

        return true
      }

      set({
        bindingError: res.info || 'Bind failed',
        isBinding: false,
      })
      return false
    } catch (err) {
      set({
        bindingError: err instanceof Error ? err.message : 'Network error',
        isBinding: false,
      })
      return false
    }
  },

  unbindTerminal: async (chatSessionId) => {
    try {
      await sshAgentApi.unbindTerminal(chatSessionId)
      set((state) => {
        const bindings = new Map(state.bindings)
        bindings.delete(chatSessionId)
        return {
          bindings,
          activeBinding:
            state.activeBinding?.chatSessionId === chatSessionId
              ? null
              : state.activeBinding,
        }
      })
    } catch (err) {
      console.error('[sshAgentStore] unbindTerminal failed:', err)
    }
  },

  queryBinding: async (chatSessionId) => {
    try {
      const res = await sshAgentApi.queryBinding(chatSessionId)
      if (res.code === '0000' && res.data?.bound && res.data.terminalSessionId) {
        // from local cache get complete whole info
        const cached = get().bindings.get(chatSessionId)
        if (cached) {
          return cached
        }
      }
      return null
    } catch (err) {
      console.error('[sshAgentStore] queryBinding failed:', err)
      return null
    }
  },

  setActiveBinding: (binding) => set({ activeBinding: binding }),

  // ===== input box tab action =====
  addInputTag: (tag) => {
    const id = `tag_${Date.now()}_${Math.random().toString(36).slice(2, 7)}`
    const newTag: InputTag = {
      ...tag,
      id,
      addedAt: Date.now(),
    }
    set((state) => {
      const isDuplicate = state.inputTags.some(existing =>
        existing.type === newTag.type
        && existing.label === newTag.label
        && existing.fullContent === newTag.fullContent
      )
      return {
        inputTags: isDuplicate ? state.inputTags : [...state.inputTags, newTag],
      }
    })
  },

  removeInputTag: (tagId) => {
    set((state) => ({
      inputTags: state.inputTags.filter((tag) => tag.id !== tagId),
    }))
  },

  clearInputTags: () => {
    set({ inputTags: [] })
  },

  getInputTagsContent: () => {
    const tags = get().inputTags
    if (tags.length === 0) return ''
    return tags.map((tag) => tag.fullContent).join('\n\n---\n\n')
  },

  openConnectionSelector: () => set({ showConnectionSelector: true }),

  closeConnectionSelector: () => set({ showConnectionSelector: false }),

  registerConnectionSession: (connectionId, terminalSessionId) => {
    set((state) => {
      const map = new Map(state.connectionSessionMap)
      map.set(connectionId, terminalSessionId)
      return { connectionSessionMap: map }
    })
  },

  unregisterConnectionSession: (connectionId) => {
    set((state) => {
      const map = new Map(state.connectionSessionMap)
      map.delete(connectionId)
      return { connectionSessionMap: map }
    })
  },

  getTerminalSessionByConnection: (connectionId) => {
    return get().connectionSessionMap.get(connectionId)
  },

  clearBindingError: () => set({ bindingError: null }),

  getBindingByChatSession: (chatSessionId) => {
    return get().bindings.get(chatSessionId)
  },

  formatServerContext: (binding) => {
    const { serverInfo, connectionName } = binding
    return `Current server: ${connectionName} (${serverInfo.username}@${serverInfo.host}:${serverInfo.port})`
  },
}))
