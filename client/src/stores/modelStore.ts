import { create } from 'zustand'
import {
  deleteModel,
  getModelList,
  saveModel,
  type ModelConfigDTO,
  type ModelConfigPayload,
} from '../api/modelConfig'

const SELECTED_MODEL_KEY = 'shellmind_selected_model_id'

function getInitialSelectedModelId(): number | null {
  const saved = localStorage.getItem(SELECTED_MODEL_KEY)
  return saved ? Number(saved) : null
}

interface ModelStore {
  models: ModelConfigDTO[]
  selectedModelId: number | null
  loading: boolean
  loaded: boolean
  error: string | null
  fetchModels: (options?: { silent?: boolean }) => Promise<boolean>
  saveModelConfig: (payload: ModelConfigPayload) => Promise<ModelConfigDTO | null>
  removeModel: (id: number) => Promise<boolean>
  selectModel: (id: number | null) => void
  clearError: () => void
}

export const useModelStore = create<ModelStore>((set, get) => ({
  models: [],
  selectedModelId: getInitialSelectedModelId(),
  loading: false,
  loaded: false,
  error: null,

  fetchModels: async (options = {}) => {
    if (!options.silent) set({ loading: true, error: null })
    try {
      const res = await getModelList()
      if (res.code !== '0000' || !res.data) {
        set({ error: res.info || 'Failed to load model config', loading: false, loaded: true })
        return false
      }

      const models = res.data
      const current = get().selectedModelId
      const selected = models.some((model) => model.id === current)
        ? current
        : models.length > 0 ? models[0].id : null
      set({ models, selectedModelId: selected, loading: false, loaded: true, error: null })
      if (selected) localStorage.setItem(SELECTED_MODEL_KEY, String(selected))
      else localStorage.removeItem(SELECTED_MODEL_KEY)
      return true
    } catch (error: any) {
      set({ error: error?.message || 'Failed to load model config', loading: false, loaded: true })
      return false
    }
  },

  saveModelConfig: async (payload) => {
    const res = await saveModel(payload)
    if (res.code !== '0000' || !res.data) {
      set({ error: res.info || 'Failed to save model config' })
      return null
    }
    await get().fetchModels({ silent: true })
    return res.data
  },

  removeModel: async (id) => {
    const res = await deleteModel(id)
    if (res.code !== '0000') {
      set({ error: res.info || 'Failed to delete model config' })
      return false
    }
    await get().fetchModels({ silent: true })
    return true
  },

  selectModel: (id) => {
    set({ selectedModelId: id })
    if (id != null) localStorage.setItem(SELECTED_MODEL_KEY, String(id))
    else localStorage.removeItem(SELECTED_MODEL_KEY)
  },

  clearError: () => set({ error: null }),
}))
