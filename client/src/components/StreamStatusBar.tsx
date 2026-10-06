/**
 * StreamStatusBar - SSE connection status horizontal width
 *
 * P0-5: display SSE connection health health status, break line when show reconnect progress
 *
 * status map:
 * - idle: hidden
 * - connecting: " align at connection..." (blue)
 * - streaming: hidden(align always status not hit interrupt user)
 * - reconnecting: " align at reconnect... (n nth)" (orange, with animation)
 * - disconnected: " connection already disconnect" (red)
 * - error: " connection error: xxx" (red)
 *
 * heartbeat timeout detect:
 * - streamStore.isHeartbeatStale() → auto trigger reconnect hint
 */

import { useEffect } from 'react'
import { useStreamStore, StreamStatus } from '../stores/streamStore'
import { chatConfig } from '../config/chat'

const STATUS_CONFIG: Record<StreamStatus, { color: string; bg: string; icon: string; text: (n: number, err: string | null) => string } | null> = {
  idle: null,
  connecting: null, // connecting belong than align always status, hidden horizontal width hit interrupt user
  streaming: null, // hidden during normal streaming
  reconnecting: {
    color: '#fbbf24',
    bg: 'rgba(251, 191, 36, 0.1)',
    icon: '🔄',
    text: (n, _) => `Reconnecting... (attempt ${n})`,
  },
  disconnected: {
    color: '#f87171',
    bg: 'rgba(239, 68, 68, 0.1)',
    icon: '❌',
    text: () => 'Disconnected',
  },
  error: {
    color: '#f87171',
    bg: 'rgba(239, 68, 68, 0.1)',
    icon: '⚠️',
    text: (_, err) => `Connection error: ${err || 'Unknown error'}`,
  },
}

export function StreamStatusBar() {
  const { status, retryCount, lastError, touchActivity, isHeartbeatStale, setStatus } = useStreamStore()

  // heartbeat timeout detect - each 5 sec check one nth
  useEffect(() => {
    if (status !== 'streaming') return

    const timer = setInterval(() => {
      if (isHeartbeatStale()) {
        console.warn('[StreamStatusBar] heartbeat timed out; marking disconnected')
        setStatus('disconnected')
      }
    }, chatConfig.heartbeatTimeout / 6)

    return () => clearInterval(timer)
  }, [status, isHeartbeatStale, setStatus])

  const config = STATUS_CONFIG[status]
  if (!config) return null

  return (
    <div
      className="flex items-center gap-2 px-3 py-1.5 text-xs"
      style={{
        backgroundColor: config.bg,
        color: config.color,
        borderBottom: `1px solid ${config.color}33`,
      }}
    >
      <span className={status === 'reconnecting' ? 'animate-spin inline-block' : ''}>
        {config.icon}
      </span>
      <span>{config.text(retryCount, lastError)}</span>
      {status === 'reconnecting' && (
        <span className="flex gap-0.5 ml-1">
          <span className="w-1 h-1 rounded-full animate-bounce" style={{ backgroundColor: config.color, animationDelay: '0ms' }} />
          <span className="w-1 h-1 rounded-full animate-bounce" style={{ backgroundColor: config.color, animationDelay: '150ms' }} />
          <span className="w-1 h-1 rounded-full animate-bounce" style={{ backgroundColor: config.color, animationDelay: '300ms' }} />
        </span>
      )}
      {status === 'disconnected' && (
        <button
          className="ml-auto px-2 py-0.5 rounded text-xs hover:opacity-80"
          style={{
            backgroundColor: config.color + '22',
            color: config.color,
            border: `1px solid ${config.color}44`,
          }}
          onClick={() => {
            touchActivity()
            setStatus('connecting')
          }}
        >
          Reconnect
        </button>
      )}
    </div>
  )
}
