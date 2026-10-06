import { post } from './request'
import type { SshFileContentResponseDTO } from './sshFile'

/**
 * generic file parse interface(non SSH pipeline)
 * for local file preview use: frontend read byte back up pass, the server parses it into a structured text view.
 */

/** class max bytes allowed for file parse(and server one cause) */
export const MAX_CLASS_PARSE_BYTES = 16 * 1024 * 1024

/** Uint8Array → base64(split chunk avoid stack overflow out) */
export function uint8ToBase64(bytes: Uint8Array): string {
  let binary = ''
  const CHUNK = 0x8000
  for (let i = 0; i < bytes.length; i += CHUNK) {
    binary += String.fromCharCode(...bytes.subarray(i, i + CHUNK))
  }
  return btoa(binary)
}

/** parse.class byte code file as javap style shape view image */
export function parseClassFile(name: string, bytes: Uint8Array) {
  return post<SshFileContentResponseDTO>('/api/v1/file/parse-class', {
    name,
    contentBase64: uint8ToBase64(bytes),
  })
}
