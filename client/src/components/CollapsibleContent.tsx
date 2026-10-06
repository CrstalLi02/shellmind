import React, { useState, useRef, useEffect } from 'react'
import { useThemeStore } from '../stores/themeStore'

const COLLAPSE_THRESHOLD = 2000  // exceed 2000 char auto collapse
const COLLAPSED_HEIGHT = 300     // collapse when max height 300px

interface CollapsibleContentProps {
  children: React.ReactNode
  contentLength: number
  /** external strong make collapse/ expand(like streaming when start end expand) */
  forceExpanded?: boolean
}

/**
 * auto-collapse container for long content.
 * - content exceed COLLAPSE_THRESHOLD char when, default collapse to COLLAPSED_HEIGHT
 * - streaming output when (forceExpanded) start end expand
 * - gradually change overlay overlay + " expand all" button
 */
export function CollapsibleContent({ children, contentLength, forceExpanded }: CollapsibleContentProps) {
  const { colors } = useThemeStore()
  const [expanded, setExpanded] = useState(false)
  const [needsCollapse, setNeedsCollapse] = useState(false)
  const containerRef = useRef<HTMLDivElement>(null)

  // detect content whether exceed threshold
  useEffect(() => {
    if (contentLength > COLLAPSE_THRESHOLD && !forceExpanded) {
      setNeedsCollapse(true)
    } else {
      setNeedsCollapse(false)
      setExpanded(false)
    }
  }, [contentLength, forceExpanded])

  // streaming when strong make expand
  useEffect(() => {
    if (forceExpanded) setExpanded(true)
  }, [forceExpanded])

  const isCollapsed = needsCollapse && !expanded

  return (
    <div className="relative min-w-0">
      <div
        ref={containerRef}
        className="transition-all duration-300 ease-in-out"
        style={{
          maxHeight: isCollapsed ? COLLAPSED_HEIGHT : 'none',
          overflow: isCollapsed ? 'hidden' : 'visible',
        }}
      >
        {children}
      </div>
      {/* collapse overlay overlay + expand button */}
      {isCollapsed && (
        <div
          className="absolute bottom-0 left-0 right-0 flex flex-col items-center pt-12"
          style={{
            background: `linear-gradient(to bottom, transparent, ${colors.bgTertiary} 70%)`,
            height: '80px',
          }}
        >
          <button
            onClick={() => setExpanded(true)}
            className="mt-2 px-4 py-1.5 rounded-full text-[11px] font-medium transition-all hover:opacity-80"
            style={{
              backgroundColor: `${colors.accent}15`,
              color: colors.accent,
              border: `1px solid ${colors.accent}30`,
            }}
          >
            ▼ Expand all ({Math.round(contentLength / 1000)}k chars)
          </button>
        </div>
      )}
      {/* collapse button */}
      {needsCollapse && expanded && !forceExpanded && (
        <button
          onClick={() => setExpanded(false)}
          className="mt-1.5 self-center px-3 py-1 rounded-full text-[10px] font-medium transition-all hover:opacity-80"
          style={{
            color: colors.textDim,
            backgroundColor: colors.bgSecondary,
            border: `1px solid ${colors.border}`,
          }}
        >
          ▲ Collapse
        </button>
      )}
    </div>
  )
}
