import { create } from 'zustand'

export type AiPatchTarget = 'local' | 'remote'

export interface AiPatchPreview {
  id: string
  target: AiPatchTarget
  path: string
  connectionId?: string
  toolName: string
  beforeContent: string
  afterContent: string
  addedLines: number
  removedLines: number
  createdAt: number
}

interface AiPatchStore {
  previews: AiPatchPreview[]
  upsertPreview: (preview: Omit<AiPatchPreview, 'id' | 'addedLines' | 'removedLines' | 'createdAt'>) => void
  removePreview: (id: string) => void
  clearPreviewByPath: (target: AiPatchTarget, path: string, connectionId?: string) => void
  getPreviewForFile: (target: AiPatchTarget, path: string, connectionId?: string) => AiPatchPreview | null
}

function countLineDiff(beforeContent: string, afterContent: string) {
  const beforeLines = beforeContent.split('\n')
  const afterLines = afterContent.split('\n')
  const beforeSet = new Set(beforeLines)
  const afterSet = new Set(afterLines)

  let addedLines = 0
  let removedLines = 0

  for (const line of afterLines) {
    if (!beforeSet.has(line)) addedLines++
  }

  for (const line of beforeLines) {
    if (!afterSet.has(line)) removedLines++
  }

  return { addedLines, removedLines }
}

export const useAiPatchStore = create<AiPatchStore>((set, get) => ({
  previews: [],

  upsertPreview: (preview) => {
    const { addedLines, removedLines } = countLineDiff(preview.beforeContent, preview.afterContent)
    set((state) => {
      const nextPreview: AiPatchPreview = {
        ...preview,
        id: `${preview.target}:${preview.connectionId || 'local'}:${preview.path}`,
        addedLines,
        removedLines,
        createdAt: Date.now(),
      }

      const others = state.previews.filter((item) => item.id !== nextPreview.id)
      return { previews: [nextPreview, ...others] }
    })
  },

  removePreview: (id) => {
    set((state) => ({
      previews: state.previews.filter((item) => item.id !== id),
    }))
  },

  clearPreviewByPath: (target, path, connectionId) => {
    set((state) => ({
      previews: state.previews.filter((item) => {
        if (item.target !== target || item.path !== path) return true
        if (target === 'remote') {
          return item.connectionId !== connectionId
        }
        return false
      }),
    }))
  },

  getPreviewForFile: (target, path, connectionId) => {
    return get().previews.find((item) => {
      if (item.target !== target || item.path !== path) return false
      if (target === 'remote') return item.connectionId === connectionId
      return true
    }) || null
  },
}))
