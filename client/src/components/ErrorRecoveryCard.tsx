/**
 * ErrorRecoveryCard - chat center break restore card
 *
 * current SSE connection disconnect, network different always, service report wrong when, show a prominent interrupt banner in the chat.
 * default expand, core action(retry/ continue) directly ok see.
 */

import { useState } from 'react'
import { useThemeStore } from '../stores/themeStore'

export type ErrorType = 'network' | 'tool_execution' | 'permission_denied' | 'context_limit' | 'unknown'

export interface ErrorRecovery {
  type: ErrorType
  title: string
  message: string
  details?: string
  toolName?: string
  toolCallId?: string
  suggestions?: string[]
}

interface ErrorRecoveryCardProps {
  error: ErrorRecovery
  onRetry?: () => void
  onSkip?: () => void
  onResetContext?: () => void
  onFeedback?: (feedback: string) => void
  canRetry?: boolean
}

const ERROR_ICONS: Record<ErrorType, string> = {
  network: '🔌',
  tool_execution: '⚙️',
  permission_denied: '⛔',
  context_limit: '📦',
  unknown: '❌',
}

const ERROR_TITLES: Record<ErrorType, string> = {
  network: 'Network connection error',
  tool_execution: 'Tool execution failed',
  permission_denied: 'Permission denied',
  context_limit: 'Context length exceeded',
  unknown: 'An error occurred',
}

/** build a user-friendly interrupt reason from the error type */
function getInterruptReason(error: ErrorRecovery): string {
  switch (error.type) {
    case 'network':
      if (error.message.includes('Failed to fetch') || error.message.includes('NetworkError')) {
        return 'Network disconnected; cannot reach the server'
      }
      if (/timeout|超时/i.test(error.message)) {
        return 'Request timed out; the server was too slow or unreachable'
      }
      return `Network issue: ${error.message}`
    case 'tool_execution':
      return `Tool error: ${error.toolName || error.message}`
    case 'context_limit':
      return 'Conversation context exceeded the limit'
    default:
      return error.message || 'An unknown error occurred'
  }
}

export function ErrorRecoveryCard({
  error,
  onRetry,
  onSkip,
  onResetContext,
  onFeedback,
  canRetry = true,
}: ErrorRecoveryCardProps) {
  const { colors } = useThemeStore()
  const [showDetails, setShowDetails] = useState(false)
  const [showFeedback, setShowFeedback] = useState(false)
  const [feedbackText, setFeedbackText] = useState('')

  const title = error.title || ERROR_TITLES[error.type] || 'Chat interrupted'
  const reason = getInterruptReason(error)

  const handleFeedback = () => {
    if (feedbackText.trim()) {
      onFeedback?.(feedbackText.trim())
      setFeedbackText('')
      setShowFeedback(false)
    }
  }

  return (
    <div
      className="rounded-xl border overflow-hidden my-2"
      style={{
        background: 'linear-gradient(135deg, rgba(239,68,68,0.08), rgba(251,146,60,0.06))',
        borderColor: 'rgba(239, 68, 68, 0.25)',
        boxShadow: '0 2px 12px rgba(239,68,68,0.1)',
      }}
    >
      {/* main hint area - start end ok see */}
      <div className="px-4 py-3">
        {/* heading line */}
        <div className="flex items-center gap-2.5 mb-2">
          <span className="text-lg">{ERROR_ICONS[error.type]}</span>
          <span className="text-sm font-semibold" style={{ color: colors.text }}>
            {title}
          </span>
          {error.toolName && (
            <code
              className="px-1.5 py-0.5 rounded text-xs font-mono"
              style={{ backgroundColor: 'rgba(255,255,255,0.08)', color: colors.textSecondary }}
            >
              {error.toolName}
            </code>
          )}
        </div>

        {/* center break raw because */}
        <p className="text-xs leading-relaxed mb-3" style={{ color: colors.textSecondary || '#999' }}>
          {reason}
        </p>

        {/* action button ok - core action directly burst expose */}
        <div className="flex items-center gap-2">
          {canRetry && onRetry && (
            <button
              className="px-4 py-1.5 rounded-lg text-xs font-medium flex items-center gap-1.5 transition-all hover:scale-[1.02] active:scale-[0.98]"
              style={{
                backgroundColor: colors.accent || '#3b82f6',
                color: '#fff',
                boxShadow: `0 2px 8px ${(colors.accent || '#3b82f6')}33`,
              }}
              onClick={onRetry}
            >
              <span>▶</span> Continue chat
            </button>
          )}
          {onSkip && (
            <button
              className="px-3 py-1.5 rounded-lg text-xs transition-all hover:opacity-80"
              style={{
                color: colors.textSecondary || '#999',
                border: `1px solid ${colors.border || 'rgba(255,255,255,0.12)'}`,
              }}
              onClick={onSkip}
            >
              Dismiss
            </button>
          )}
          {onResetContext && (
            <button
              className="px-3 py-1.5 rounded-lg text-xs transition-all hover:opacity-80"
              style={{
                color: colors.textSecondary || '#999',
                border: `1px solid ${colors.border || 'rgba(255,255,255,0.12)'}`,
              }}
              onClick={onResetContext}
            >
              Reset context
            </button>
          )}
          {/* expand/ collapse detail info */}
          {(error.details || onFeedback) && (
            <button
              className="ml-auto px-2 py-1 rounded text-xs transition-all hover:opacity-70"
              style={{ color: colors.textSecondary || '#888' }}
              onClick={() => setShowDetails(!showDetails)}
            >
              {showDetails ? 'Hide details ▲' : 'Show details ▼'}
            </button>
          )}
        </div>
      </div>

      {/* expand detail thin info area */}
      {showDetails && (
        <div
          className="px-4 pb-3 border-t"
          style={{ borderColor: 'rgba(239, 68, 68, 0.15)' }}
        >
          {/* skill tech thin section */}
          {error.details && (
            <pre
              className="mt-2 p-2.5 rounded-lg text-xs font-mono overflow-x-auto max-h-32 leading-relaxed"
              style={{
                backgroundColor: 'rgba(0,0,0,0.25)',
                color: colors.textSecondary || '#aaa',
                whiteSpace: 'pre-wrap',
                wordBreak: 'break-all',
              }}
            >
              {error.details}
            </pre>
          )}

          {/* feedback area */}
          {onFeedback && (
            <div className="mt-2">
              {!showFeedback ? (
                <button
                  className="text-xs px-2 py-1 rounded transition-all hover:opacity-80"
                  style={{ color: colors.textSecondary || '#888' }}
                  onClick={() => setShowFeedback(true)}
                >
                  📝 Report this issue
                </button>
              ) : (
                <div className="space-y-1.5">
                  <textarea
                    className="w-full rounded-lg p-2.5 text-xs resize-none leading-relaxed"
                    style={{
                      backgroundColor: colors.bgInput || 'rgba(255,255,255,0.05)',
                      color: colors.text,
                      border: `1px solid ${colors.border || 'rgba(255,255,255,0.1)'}`,
                      minHeight: '64px',
                    }}
                    placeholder="Describe the problem..."
                    value={feedbackText}
                    onChange={(e) => setFeedbackText(e.target.value)}
                    autoFocus
                  />
                  <div className="flex justify-end gap-2">
                    <button
                      className="px-2.5 py-1 rounded text-xs"
                      style={{ color: colors.textSecondary || '#999' }}
                      onClick={() => setShowFeedback(false)}
                    >
                      Cancel
                    </button>
                    <button
                      className="px-2.5 py-1 rounded text-xs font-medium"
                      style={{ backgroundColor: colors.accent, color: '#fff' }}
                      onClick={handleFeedback}
                    >
                      Submit feedback
                    </button>
                  </div>
                </div>
              )}
            </div>
          )}
        </div>
      )}
    </div>
  )
}
