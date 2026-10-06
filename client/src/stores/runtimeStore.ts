import { create } from 'zustand'
import { invoke } from '@tauri-apps/api/core'
import { setRequestBaseUrl } from '../api/request'

export interface AgentRuntimeStatus {
  running: boolean
  port: number
  pid: number | null
  token: string | null
  jarPath: string | null
  javaPath: string | null
  javaVersion: string | null
  lastError: string | null
  workspace: string | null
  /** start stage:init / resolve / prepare / spawn / booting / ready / error */
  stage: string
  /** current stage ok read notes */
  stageMessage: string
}

interface RuntimeStore {
  status: AgentRuntimeStatus
  starting: boolean
  error: string | null
  start: (workspace?: string) => Promise<void>
  stop: () => Promise<void>
  refresh: () => Promise<void>
}

function normalizeWorkspace(workspace?: string): string | null {
  const value = workspace?.trim()
  return value ? value : null
}

export const useRuntimeStore = create<RuntimeStore>((set, get) => ({
  status: {
    running: false,
    port: 0,
    pid: null,
    token: null,
    jarPath: null,
    javaPath: null,
    javaVersion: null,
    lastError: null,
    workspace: null,
    stage: 'init',
    stageMessage: 'Waiting to start',
  },
  starting: false,
  error: null,

  start: async (workspace) => {
    const nextWorkspace = normalizeWorkspace(workspace)
    const current = get()
    if (current.starting) return
    if (current.status.running && current.status.workspace === nextWorkspace) return
    if (current.status.running) await current.stop()
    set({ starting: true, error: null })
    try {
      const initial = await invoke<AgentRuntimeStatus>('start_agent_runtime', {
        workspace: workspace ?? null,
      })
      set({ status: initial })
      await get().refresh()
    } catch (error: any) {
      set({ error: error?.message || 'Failed to start local Agent' })
    } finally {
      set({ starting: false })
    }
  },

  stop: async () => {
    try {
      const status = await invoke<AgentRuntimeStatus>('stop_agent_runtime')
      set({ status, error: null })
      setRequestBaseUrl('', null)
    } catch (error: any) {
      set({ error: error?.message || 'Failed to stop local Agent' })
    }
  },

  refresh: async () => {
    // Java cold start + first initial start ok can than slow(especially Windows), wait max drop wide to 60s
    const deadline = Date.now() + 60000
    while (Date.now() < deadline) {
      const status = await invoke<AgentRuntimeStatus>('get_agent_runtime_status')
      set({ status })
      if (status.running && status.port > 0 && status.token) {
        setRequestBaseUrl(`http://127.0.0.1:${status.port}`, status.token)
        return
      }
      if (!status.running) {
        if (status.lastError) set({ error: status.lastError })
        return
      }
      await new Promise(resolve => setTimeout(resolve, 120))
    }
    set({ error: 'Local Agent startup timed out (not ready after 60s). First launch can be slow - click Retry. If it keeps failing, check agent.log.' })
  },
}))

export async function bootstrapLocalRuntime(workspace?: string): Promise<void> {
  await useRuntimeStore.getState().start(workspace)
  const state = useRuntimeStore.getState()
  if (state.status.workspace !== normalizeWorkspace(workspace)) {
    await state.start(workspace)
  }
}
