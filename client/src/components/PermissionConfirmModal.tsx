/**
 * PermissionConfirmModal - permission confirm popup window
 *
 * P0-2: when backend PermissionGuard hit CONFIRM level,
 * SSE push permission_confirm event → permissionStore queue → this component render
 *
 * feature:
 * - show tool name name, params, wind risk equal level, wind risk raw because
 * - countdown timeout(like config timeoutMs)
 * - user can" confirm execute"" reject"" edit params back confirm"
 * - wind risk equal level color distinguish:DENY= red / CONFIRM= orange / ALLOW= green
 * - keyboard shortcut:Enter= confirm / Esc= reject
 */

import { useEffect, useState, useRef } from 'react'
import { usePermissionStore } from '../stores/permissionStore'
import { useThemeStore } from '../stores/themeStore'

const RISK_COLORS: Record<string, { bg: string; border: string; text: string; label: string }> = {
  DENY: { bg: 'rgba(239, 68, 68, 0.12)', border: '#ef4444', text: '#fca5a5', label: 'Danger' },
  CONFIRM: { bg: 'rgba(249, 115, 22, 0.12)', border: '#f97316', text: '#fdba74', label: 'Needs approval' },
  ALLOW: { bg: 'rgba(34, 197, 94, 0.12)', border: '#22c55e', text: '#86efac', label: 'Safe' },
}

export function PermissionConfirmModal() {
  const { colors } = useThemeStore()
  const { current, resolveConfirmation } = usePermissionStore()
  const [countdown, setCountdown] = useState<number | null>(null)
  const [editMode, setEditMode] = useState(false)
  const [editedArgs, setEditedArgs] = useState('')
  const countdownTimerRef = useRef<number | null>(null)

  // countdown
  useEffect(() => {
    if (!current) {
      setCountdown(null)
      setEditMode(false)
      return
    }

    setEditedArgs(current.toolArgs)

    if (current.timeoutMs > 0) {
      const seconds = Math.ceil(current.timeoutMs / 1000)
      setCountdown(seconds)

      countdownTimerRef.current = window.setInterval(() => {
        setCountdown((prev) => {
          if (prev === null) return null
          if (prev <= 1) {
            // timeout auto reject
            resolveConfirmation(current.confirmId, false)
            return null
          }
          return prev - 1
        })
      }, 1000)
    }

    return () => {
      if (countdownTimerRef.current) clearInterval(countdownTimerRef.current)
    }
  }, [current, resolveConfirmation])

  // keyboard shortcut
  useEffect(() => {
    if (!current) return

    const handler = (e: KeyboardEvent) => {
      if (e.key === 'Enter' && !editMode) {
        e.preventDefault()
        handleApprove()
      } else if (e.key === 'Escape') {
        e.preventDefault()
        handleDeny()
      }
    }
    window.addEventListener('keydown', handler)
    return () => window.removeEventListener('keydown', handler)
  }, [current, editMode, editedArgs])

  if (!current) return null

  const risk = RISK_COLORS[current.riskLevel] || RISK_COLORS.CONFIRM

  const handleApprove = () => {
    resolveConfirmation(current.confirmId, true, editMode ? editedArgs : undefined)
  }

  const handleDeny = () => {
    resolveConfirmation(current.confirmId, false)
  }

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center"
      style={{ backgroundColor: 'rgba(0, 0, 0, 0.5)' }}
    >
      <div
        className="w-full max-w-lg rounded-lg shadow-xl border"
        style={{
          backgroundColor: colors.bgSecondary || '#1e1e2e',
          borderColor: risk.border,
          color: colors.text,
        }}
      >
        {/* Header */}
        <div
          className="flex items-center gap-2 px-4 py-3 border-b"
          style={{ borderColor: colors.border || 'rgba(255,255,255,0.08)' }}
        >
          <span
            className="px-2 py-0.5 rounded text-xs font-medium"
            style={{ backgroundColor: risk.bg, color: risk.text, border: `1px solid ${risk.border}` }}
          >
            {risk.label}
          </span>
          <span className="text-sm font-medium" style={{ color: colors.text }}>
            Permission
          </span>
          {countdown !== null && (
            <span className="ml-auto text-xs" style={{ color: colors.textSecondary || '#999' }}>
              {countdown}s until auto-reject
            </span>
          )}
        </div>

        {/* Body */}
        <div className="px-4 py-3 space-y-3">
          {/* tool info */}
          <div className="space-y-1.5">
            <div className="flex items-center gap-2 text-xs" style={{ color: colors.textSecondary || '#999' }}>
              <span>Tool</span>
              <code className="px-1.5 py-0.5 rounded" style={{ backgroundColor: colors.bgInput || 'rgba(255,255,255,0.05)' }}>
                {current.toolName}
              </code>
            </div>
          </div>

          {/* wind risk raw because */}
          <div
            className="p-2.5 rounded text-xs"
            style={{ backgroundColor: risk.bg, color: risk.text }}
          >
            ⚠️ {current.reason}
          </div>

          {/* params / editor area */}
          <div>
            <div className="flex items-center justify-between mb-1">
              <span className="text-xs" style={{ color: colors.textSecondary || '#999' }}>Arguments</span>
              <button
                className="text-xs hover:underline"
                style={{ color: colors.accent }}
                onClick={() => setEditMode(!editMode)}
              >
                {editMode ? 'Cancel edit' : 'Edit arguments'}
              </button>
            </div>
            {editMode ? (
              <textarea
                className="w-full rounded p-2 text-xs font-mono resize-y"
                style={{
                  backgroundColor: colors.bgInput || 'rgba(255,255,255,0.05)',
                  color: colors.text,
                  border: `1px solid ${colors.border || 'rgba(255,255,255,0.1)'}`,
                  minHeight: '80px',
                }}
                value={editedArgs}
                onChange={(e) => setEditedArgs(e.target.value)}
                autoFocus
              />
            ) : (
              <pre
                className="p-2 rounded text-xs font-mono overflow-x-auto"
                style={{
                  backgroundColor: colors.bgInput || 'rgba(255,255,255,0.05)',
                  color: colors.text,
                  maxHeight: '160px',
                  whiteSpace: 'pre-wrap',
                  wordBreak: 'break-all',
                }}
              >
                {current.toolArgs}
              </pre>
            )}
          </div>
        </div>

        {/* Footer */}
        <div
          className="flex items-center justify-end gap-2 px-4 py-3 border-t"
          style={{ borderColor: colors.border || 'rgba(255,255,255,0.08)' }}
        >
          <button
            className="px-3 py-1.5 rounded text-xs font-medium transition-colors"
            style={{
              backgroundColor: 'transparent',
              color: colors.textSecondary || '#999',
              border: `1px solid ${colors.border || 'rgba(255,255,255,0.15)'}`,
            }}
            onClick={handleDeny}
          >
            Reject (Esc)
          </button>
          <button
            className="px-3 py-1.5 rounded text-xs font-medium transition-colors"
            style={{
              backgroundColor: risk.border,
              color: '#fff',
            }}
            onClick={handleApprove}
          >
            Approve (Enter)
          </button>
        </div>
      </div>
    </div>
  )
}
