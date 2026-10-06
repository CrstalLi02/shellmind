/**
 * output panel status manage - AI command execute history
 */
import { create } from 'zustand'

export type OutputStatus = 'running' | 'success' | 'failed' | 'backgrounded'

export interface OutputEntry {
  id: string
  sessionId: string
  command: string
  status: OutputStatus
  stdout: string
  stderr: string
  exitCode: number | null
  durationMs: number | null
  timestamp: number
}

interface OutputState {
  entries: OutputEntry[]
  addEntry: (entry: Omit<OutputEntry, 'id' | 'timestamp'>) => string
  updateEntry: (sessionId: string, patch: Partial<OutputEntry>) => void
  removeEntry: (id: string) => void
  clearEntries: () => void
}

export const useOutputStore = create<OutputState>((set) => ({
  entries: [],

  addEntry: (entry) => {
    const id = `out-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`
    const fullEntry: OutputEntry = {
      ...entry,
      id,
      timestamp: Date.now(),
    }
    set((state) => ({
      entries: [...state.entries, fullEntry],
    }))
    return id
  },

  updateEntry: (sessionId, patch) => {
    set((state) => ({
      entries: state.entries.map((e) =>
        e.sessionId === sessionId ? { ...e, ...patch } : e
      ),
    }))
  },

  removeEntry: (id) => {
    set((state) => ({
      entries: state.entries.filter((e) => e.id !== id),
    }))
  },

  clearEntries: () => set({ entries: [] }),
}))
