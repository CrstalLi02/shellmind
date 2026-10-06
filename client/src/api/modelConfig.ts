import { get, post } from './request'

export interface ModelConfigDTO {
  id: number
  name: string
  baseUrl: string
  modelName: string
  completionsPath: string
  hasApiKey: boolean
  apiKeyMasked: string
  createdAt?: string
  updatedAt?: string
}

export interface ModelConfigPayload {
  id?: number
  name: string
  baseUrl: string
  apiKey?: string
  modelName: string
  completionsPath?: string
}

export function getModelList() {
  return get<ModelConfigDTO[]>('/api/v1/model/list')
}

export function saveModel(payload: ModelConfigPayload) {
  return post<ModelConfigDTO>('/api/v1/model/save', payload)
}

export function deleteModel(id: number) {
  return post<void>('/api/v1/model/delete', undefined, { id: String(id) })
}

export function revealModelKey(id: number) {
  return post<string>('/api/v1/model/reveal', undefined, { id: String(id) })
}

export function normalizeBaseUrl(value: string) {
  const trimmed = value.trim()
  if (!trimmed) return ''
  return trimmed.endsWith('/') ? trimmed : `${trimmed}/`
}

export function testModel(payload: ModelConfigPayload) {
  return post<string>('/api/v1/model/test', payload)
}
