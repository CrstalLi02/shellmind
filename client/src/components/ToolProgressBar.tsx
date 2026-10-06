import React, { memo, useState, useEffect, useRef } from 'react'
import { useThemeStore } from '../stores/themeStore'

/**
 * ToolProgressBar - tool execute progress bar
 * show a progress bar while the tool runs, support indeterminate(uncertain) and determinate(determine) two kind mode.
 *
 * backend progress char segment format(via SSE tool_progress event pass pass):
 * { toolCallId: string, percent: number, label?: string, detail?: string }
 *
 * current percent > 0 when determinate mode, otherwise indeterminate mode.
 */

export interface ToolProgressData {
  toolCallId: string
  toolName?: string
  percent?: number   // 0-100, not yet extract for when indeterminate
  label?: string     // current step tab(like " align at read file...")
  detail?: string    // detail thin info(like " already handled 1200/3000 ok")
  status?: 'running' | 'success' | 'failure'
}

interface ToolProgressBarProps {
  toolName?: string
  toolCallId?: string
}

export const ToolProgressBar = memo(function ToolProgressBar({ toolName, toolCallId }: ToolProgressBarProps) {
  const { colors } = useThemeStore()
  const [elapsed, setElapsed] = useState(0)
  const startTimeRef = useRef<number>(0)
  const progressState = useToolProgress()

  // get current tool progress data
  const allProgress = Object.values(progressState)
  const progress = toolCallId ? progressState[toolCallId] : allProgress.find(p => p.status === 'running')

  // timer
  useEffect(() => {
    if (progress?.status === 'running') {
      startTimeRef.current = Date.now()
      const timer = setInterval(() => {
        setElapsed(Math.floor((Date.now() - startTimeRef.current) / 1000))
      }, 1000)
      return () => clearInterval(timer)
    }
  }, [progress?.status])

  if (!progress || progress.status === 'success' || progress.status === 'failure') {
    return null
  }

  const percent = progress.percent ?? 0
  const isDeterminate = percent > 0 && percent < 100

  return (
    <div
      className="flex items-center gap-2 px-3 py-1.5 rounded-md my-1"
      style={{
        backgroundColor: `${colors.bgPrimary}60`,
        border: `1px solid ${colors.border}40`,
      }}
    >
      {/* tool icon + name */}
      <div className="flex items-center gap-1.5 flex-shrink-0">
        <svg className="w-3.5 h-3.5 animate-spin" style={{ color: colors.accent }} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
          <path d="M21 12a9 9 0 1 1-6.219-8.56" />
        </svg>
        <span className="text-[11px] font-mono font-medium" style={{ color: colors.accent }}>
          {toolName || progress.toolName || progress.label || 'Running'}
        </span>
      </div>

      {/* progress bar main */}
      <div className="flex-1 min-w-0">
        {isDeterminate ? (
          <>
            <div className="h-1.5 rounded-full overflow-hidden" style={{ backgroundColor: `${colors.border}30` }}>
              <div
                className="h-full rounded-full transition-all duration-500 ease-out"
                style={{
                  width: `${percent}%`,
                  backgroundColor: colors.accent,
                }}
              />
            </div>
            <div className="flex items-center justify-between mt-0.5">
              <span className="text-[9px] truncate" style={{ color: colors.textDim }}>
                {progress.detail || progress.label || ''}
              </span>
              <span className="text-[9px] flex-shrink-0 tabular-nums" style={{ color: colors.textDim }}>
                {percent}%
              </span>
            </div>
          </>
        ) : (
          <div className="h-1.5 rounded-full overflow-hidden relative" style={{ backgroundColor: `${colors.border}30` }}>
            {/* Indeterminate animation */}
            <div
              className="absolute h-full rounded-full"
              style={{
                width: '40%',
                backgroundColor: colors.accent,
                animation: 'indeterminate-slide 1.5s ease-in-out infinite',
              }}
            />
          </div>
        )}
      </div>

      {/* timing */}
      <span className="text-[9px] flex-shrink-0 tabular-nums" style={{ color: colors.textDim }}>
        {elapsed}s
      </span>

      {/* CSS animation */}
      <style>{`
        @keyframes indeterminate-slide {
          0% { left: -40%; }
          50% { left: 100%; }
          100% { left: -40%; }
        }
      `}</style>
    </div>
  )
})

// ===== Store: tool progress status manage =====
// at RightSidebar or agent.ts used in

interface ToolProgressState {
  [toolCallId: string]: ToolProgressData
}

// simple event send emit widget, used for non- React ring env update progress
const progressListeners: Set<(state: ToolProgressState) => void> = new Set()
let progressState: ToolProgressState = {}

export const toolProgressStore = {
  set(data: ToolProgressData) {
    progressState = { ...progressState, [data.toolCallId]: data }
    progressListeners.forEach(fn => fn(progressState))
  },
  update(toolCallId: string, patch: Partial<ToolProgressData>) {
    if (progressState[toolCallId]) {
      progressState = { ...progressState, [toolCallId]: { ...progressState[toolCallId], ...patch } }
      progressListeners.forEach(fn => fn(progressState))
    }
  },
  remove(toolCallId: string) {
    const next = { ...progressState }
    delete next[toolCallId]
    progressState = next
    progressListeners.forEach(fn => fn(progressState))
  },
  clear() {
    progressState = {}
    progressListeners.forEach(fn => fn(progressState))
  },
  subscribe(fn: (state: ToolProgressState) => void) {
    progressListeners.add(fn)
    return () => { progressListeners.delete(fn) }
  },
  getState() {
    return progressState
  },
}

// ===== Hook =====
export function useToolProgress() {
  const [state, setState] = React.useState<ToolProgressState>(progressState)
  React.useEffect(() => {
    return toolProgressStore.subscribe(setState)
  }, [])
  return state
}
