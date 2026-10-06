/**
 * task split unbind card component
 *
 * when backend send task_breakdown event when, at AI panel center display split unbind square case.
 * each subtask show title, description, pre- period tool and status(pending/executing/completed/failed).
 *
 * corresponds to backend ReActEventDTO.event = "task_breakdown"
 */

import { useState } from 'react'
import type { TaskBreakdownDTO, TaskSubTask } from '../api/agent'

interface TaskBreakdownCardProps {
  breakdown: TaskBreakdownDTO
  onSubTaskClick?: (subTask: TaskSubTask) => void
}

const STATUS_CONFIG: Record<string, { icon: string; color: string; label: string }> = {
  pending:    { icon: '⏳', color: '#9ca3af', label: 'Pending' },
  executing:  { icon: '⚡', color: '#3b82f6', label: 'Running' },
  completed:  { icon: '✅', color: '#22c55e', label: 'Done' },
  failed:     { icon: '❌', color: '#ef4444', label: 'Failed' },
  skipped:    { icon: '⏭️', color: '#9ca3af', label: 'Skip' },
}

export function TaskBreakdownCard({ breakdown, onSubTaskClick }: TaskBreakdownCardProps) {
  const [collapsed, setCollapsed] = useState(false)

  if (!breakdown.subTasks || breakdown.subTasks.length === 0) return null

  const completedCount = breakdown.subTasks.filter(st => st.status === 'completed').length
  const totalCount = breakdown.subTasks.length
  const progressPercent = Math.round((completedCount / totalCount) * 100)

  return (
    <div
      style={{
        margin: '8px 0',
        borderRadius: '8px',
        border: '1px solid rgba(139, 92, 246, 0.3)',
        background: 'rgba(139, 92, 246, 0.05)',
        overflow: 'hidden',
      }}
    >
      {/* header */}
      <div
        onClick={() => setCollapsed(!collapsed)}
        style={{
          display: 'flex',
          alignItems: 'center',
          gap: '8px',
          padding: '10px 12px',
          cursor: 'pointer',
          userSelect: 'none',
        }}
      >
        <span style={{ fontSize: '14px' }}>📋</span>
        <span style={{ fontSize: '13px', fontWeight: 600, flex: 1, color: '#e5e7eb' }}>
          {breakdown.summary || 'Task breakdown'}
        </span>
        <span
          style={{
            fontSize: '11px',
            padding: '2px 8px',
            borderRadius: '10px',
            background: 'rgba(139, 92, 246, 0.2)',
            color: '#a78bfa',
          }}
        >
          {completedCount}/{totalCount}
        </span>
        <span style={{ fontSize: '12px', color: '#9ca3af' }}>
          {collapsed ? '▶' : '▼'}
        </span>
      </div>

      {/* progress bar */}
      {!collapsed && (
        <div style={{ padding: '0 12px 4px' }}>
          <div
            style={{
              height: '3px',
              borderRadius: '2px',
              background: 'rgba(55, 65, 81, 0.5)',
              overflow: 'hidden',
            }}
          >
            <div
              style={{
                height: '100%',
                width: `${progressPercent}%`,
                borderRadius: '2px',
                background: 'linear-gradient(90deg, #8b5cf6, #6366f1)',
                transition: 'width 0.3s ease',
              }}
            />
          </div>
        </div>
      )}

      {/* subtask list */}
      {!collapsed && (
        <div style={{ padding: '4px 12px 10px' }}>
          {breakdown.subTasks.map((subTask) => {
            const config = STATUS_CONFIG[subTask.status] || STATUS_CONFIG.pending
            return (
              <div
                key={subTask.index}
                onClick={() => onSubTaskClick?.(subTask)}
                style={{
                  display: 'flex',
                  alignItems: 'flex-start',
                  gap: '8px',
                  padding: '6px 8px',
                  margin: '2px 0',
                  borderRadius: '6px',
                  background: 'rgba(31, 41, 55, 0.3)',
                  cursor: onSubTaskClick ? 'pointer' : 'default',
                  transition: 'background 0.15s',
                }}
                onMouseEnter={(e) => {
                  if (onSubTaskClick) e.currentTarget.style.background = 'rgba(31, 41, 55, 0.6)'
                }}
                onMouseLeave={(e) => {
                  if (onSubTaskClick) e.currentTarget.style.background = 'rgba(31, 41, 55, 0.3)'
                }}
              >
                {/* status icon */}
                <span style={{ fontSize: '12px', lineHeight: '20px', flexShrink: 0 }}>
                  {config.icon}
                </span>

                {/* content */}
                <div style={{ flex: 1, minWidth: 0 }}>
                  <div style={{ display: 'flex', alignItems: 'center', gap: '6px' }}>
                    <span
                      style={{
                        fontSize: '11px',
                        color: '#6b7280',
                        flexShrink: 0,
                      }}
                    >
                      #{subTask.index}
                    </span>
                    <span
                      style={{
                        fontSize: '12px',
                        fontWeight: 500,
                        color: '#e5e7eb',
                        textDecoration: subTask.status === 'completed' ? 'line-through' : 'none',
                        opacity: subTask.status === 'completed' ? 0.7 : 1,
                      }}
                    >
                      {subTask.title}
                    </span>
                  </div>
                  {subTask.description && (
                    <div
                      style={{
                        fontSize: '11px',
                        color: '#9ca3af',
                        marginTop: '2px',
                        lineHeight: '1.4',
                      }}
                    >
                      {subTask.description}
                    </div>
                  )}
                  {subTask.expectedTools && (
                    <div
                      style={{
                        display: 'inline-block',
                        fontSize: '10px',
                        padding: '1px 6px',
                        marginTop: '3px',
                        borderRadius: '4px',
                        background: 'rgba(59, 130, 246, 0.15)',
                        color: '#60a5fa',
                      }}
                    >
                      🔧 {subTask.expectedTools}
                    </div>
                  )}
                  {subTask.result && subTask.status === 'completed' && (
                    <div
                      style={{
                        fontSize: '10px',
                        color: '#22c55e',
                        marginTop: '3px',
                        lineHeight: '1.3',
                      }}
                    >
                      {subTask.result}
                    </div>
                  )}
                </div>

                {/* status tab */}
                <span
                  style={{
                    fontSize: '10px',
                    padding: '1px 6px',
                    borderRadius: '4px',
                    background: `${config.color}20`,
                    color: config.color,
                    flexShrink: 0,
                  }}
                >
                  {config.label}
                </span>
              </div>
            )
          })}
        </div>
      )}
    </div>
  )
}
