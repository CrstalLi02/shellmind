import { get, post, postFormData } from './request'

const BASE = '/api/v1/ssh/file'

export interface SshFileEntryDTO {
  name: string
  path: string
  directory: boolean
  size: number | null
  modifiedAt: number | null
}

export interface SshFileTreeResponseDTO {
  rootPath: string
  homePath: string
  currentPath: string
  parentPath: string | null
  items: SshFileEntryDTO[]
}

export interface SshFileContentResponseDTO {
  path: string
  name: string
  charset: string
  size: number
  binary: boolean
  truncated: boolean
  /** split piece read up start offset move */
  offset?: number
  /** remaining remaining not yet read byte number */
  remaining?: number
  content: string
  /** parse-type id for binary files(like "java-class"), non empty table show content as parse after shape view image(read-only) */
  parsedType?: string | null
}

export function getFileTree(connectionId: string, path?: string) {
  return get<SshFileTreeResponseDTO>(`${BASE}/tree`, { connectionId, path: path ?? '' })
}

export function getFileContent(connectionId: string, path: string) {
  return get<SshFileContentResponseDTO>(`${BASE}/content`, { connectionId, path })
}

export function getFileContentChunk(connectionId: string, path: string, offset?: number, limit?: number) {
  return get<SshFileContentResponseDTO>(`${BASE}/content-chunk`, { connectionId, path, offset: offset?.toString() ?? '', limit: limit?.toString() ?? '' })
}

export function createFile(connectionId: string, path: string, useSudo?: boolean) {
  return post<void>(`${BASE}/create-file`, undefined, { connectionId, path, sudo: useSudo ? 'true' : 'false' })
}

export function createDirectory(connectionId: string, path: string, useSudo?: boolean) {
  return post<void>(`${BASE}/create-directory`, undefined, { connectionId, path, sudo: useSudo ? 'true' : 'false' })
}

export function renameFile(connectionId: string, oldPath: string, newPath: string, useSudo?: boolean) {
  return post<void>(`${BASE}/rename`, undefined, { connectionId, oldPath, newPath, sudo: useSudo ? 'true' : 'false' })
}

export function deleteFile(connectionId: string, path: string, useSudo?: boolean) {
  return post<void>(`${BASE}/delete`, undefined, { connectionId, path, sudo: useSudo ? 'true' : 'false' })
}

export function saveFileContent(connectionId: string, path: string, content: string, useSudo?: boolean) {
  return post<void>(`${BASE}/save-content`, { content }, { connectionId, path, sudo: useSudo ? 'true' : 'false' })
}

export function uploadFile(connectionId: string, path: string, file: File, signal?: AbortSignal) {
  const formData = new FormData()
  formData.append('file', file)
  return postFormData<void>(`${BASE}/upload?connectionId=${encodeURIComponent(connectionId)}&path=${encodeURIComponent(path)}`, formData, signal)
}

export function downloadFileUrl(connectionId: string, path: string) {
  return `${BASE}/download?connectionId=${encodeURIComponent(connectionId)}&path=${encodeURIComponent(path)}`
}
