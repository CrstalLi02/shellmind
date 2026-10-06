/**
 * SSE streaming chat config
 *
 * all time form position: ms
 * no rebuild needed after editing this file, hot update create effect
 */
export const chatConfig = {
  /** HTTP request timeout time(once fetch) */
  requestTimeout: 60_000,

  /** HTTP 5xx / network error - max retry nth number */
  maxRetries: 3,

  /** HTTP 5xx / network error - retry base middle gap(n nth = baseDelay × n) */
  retryBaseDelay: 1_000,

  /** SSE stream center path disconnect - max reconnect nth number */
  maxStreamReconnects: 3,

  /** SSE stream center path disconnect - reconnect base middle gap(n nth = baseDelay × n) */
  streamReconnectBaseDelay: 2_000,

  /** heartbeat timeout time(exceed at this point middle not yet receive heartbeat → mark disconnect) */
  heartbeatTimeout: 30_000,
} as const
