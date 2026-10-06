/** Shell streaming execute event(Rust side StreamEvent corresponds to) */
export interface StreamEvent {
  session_id: string
  kind: 'stdout' | 'stderr' | 'done' | 'error'
  data: string
  exit_code: number | null
  duration_ms: number | null
}
