/**
 * HTTP client
 * Wraps fetch and normalizes the response format and errors.
 *
 * Dev default: baseUrl = '' (Vite proxy)
 * Production (Tauri): set the direct URL after the local Agent Runtime starts
 */

/** Unified backend response envelope */
export interface ApiResponse<T = unknown> {
  code: string
  info: string
  data: T | null
}

let authToken: string | null = null

/**
 * Server base URL
 * - Dev: empty string (Vite proxy)
 * - Production: overwritten after the local Agent Runtime starts
 */
let baseUrl = ''

/** Current server URL (empty until Runtime starts) */
export function getBaseUrl(): string {
  return baseUrl
}

/** build original fetch use recognize verify head */
export function getAuthHeaders(extraHeaders: Record<string, string> = {}): Record<string, string> {
  return authToken
    ? { ...extraHeaders, Authorization: `Bearer ${authToken}` }
    : extraHeaders
}

/** settings local Agent action state address and one nth Token */
export function setRequestBaseUrl(url: string, token: string | null): void {
  const trimmed = url.trim().replace(/\/+$/, '')
  baseUrl = trimmed
  authToken = token
  localStorage.removeItem('shellmind_server_url')
  if (token) {
    localStorage.setItem('shellmind_runtime_token', token)
  } else {
    localStorage.removeItem('shellmind_runtime_token')
  }
}

/** request timeout(ms) */
const TIMEOUT_MS = 15000

/**
 * generic request method
 */
async function request<T>(
  method: string,
  path: string,
  body?: unknown,
  params?: Record<string, string>,
): Promise<ApiResponse<T>> {
  // concat query string
  let url = `${baseUrl}${path}`
  if (params) {
    const qs = Object.entries(params)
      .filter(([, v]) => v !== undefined && v !== null && v !== '')
      .map(([k, v]) => `${encodeURIComponent(k)}=${encodeURIComponent(v)}`)
      .join('&')
    if (qs) url += `?${qs}`
  }

  const controller = new AbortController()
  const timer = setTimeout(() => controller.abort(), TIMEOUT_MS)

  try {
    const res = await fetch(url, {
      method,
      headers: getAuthHeaders(body ? { 'Content-Type': 'application/json' } : {}),
      body: body ? JSON.stringify(body) : undefined,
      signal: controller.signal,
    })

    if (!res.ok) {
      return { code: String(res.status), info: res.statusText, data: null }
    }

    return (await res.json()) as ApiResponse<T>
  } catch (err: any) {
    if (err?.name === 'AbortError') {
      return { code: 'TIMEOUT', info: 'Request timed out', data: null }
    }
    return { code: 'NETWORK_ERROR', info: err?.message || 'Network error', data: null }
  } finally {
    clearTimeout(timer)
  }
}

/** GET request */
export function get<T>(path: string, params?: Record<string, string>) {
  return request<T>('GET', path, undefined, params)
}

/** POST request(JSON body + optional query params) */
export function post<T>(path: string, body?: unknown, params?: Record<string, string>) {
  return request<T>('POST', path, body, params)
}

/** POST FormData request(for up pass file), no default timeout, support external pass in signal cancel */
export async function postFormData<T>(path: string, formData: FormData, signal?: AbortSignal): Promise<ApiResponse<T>> {
  let url = `${baseUrl}${path}`
  
  try {
    const res = await fetch(url, {
      method: 'POST',
      headers: getAuthHeaders(),
      body: formData,
      signal,
    })

    if (!res.ok) {
      return { code: String(res.status), info: res.statusText, data: null }
    }
    return (await res.json()) as ApiResponse<T>
  } catch (err: any) {
    if (err?.name === 'AbortError') {
      return { code: 'CANCELLED', info: 'Upload cancelled', data: null }
    }
    return { code: 'NETWORK_ERROR', info: err?.message || 'Network error', data: null }
  }
}

/** PUT request */
export function put<T>(path: string, body?: unknown, params?: Record<string, string>) {
  return request<T>('PUT', path, body, params)
}

/** DELETE request */
export function del<T>(path: string, params?: Record<string, string>) {
  return request<T>('DELETE', path, undefined, params)
}
