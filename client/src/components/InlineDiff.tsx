import React, { useMemo } from 'react'
import { useThemeStore } from '../stores/themeStore'
import type { AiPatchPreview } from '../stores/aiPatchStore'

// ===== Diff line calc =====
interface DiffLine {
  type: 'add' | 'remove' | 'context'
  content: string
  oldLineNum?: number
  newLineNum?: number
}

function computeDiff(before: string, after: string): DiffLine[] {
  const beforeLines = before.split('\n')
  const afterLines = after.split('\n')

  // simple LCS diff
  const m = beforeLines.length
  const n = afterLines.length
  const dp: number[][] = Array.from({ length: m + 1 }, () => new Array(n + 1).fill(0))

  for (let i = 1; i <= m; i++) {
    for (let j = 1; j <= n; j++) {
      if (beforeLines[i - 1] === afterLines[j - 1]) {
        dp[i][j] = dp[i - 1][j - 1] + 1
      } else {
        dp[i][j] = Math.max(dp[i - 1][j], dp[i][j - 1])
      }
    }
  }

  // rewind
  let i = m, j = n
  const tmp: DiffLine[] = []
  while (i > 0 || j > 0) {
    if (i > 0 && j > 0 && beforeLines[i - 1] === afterLines[j - 1]) {
      tmp.unshift({ type: 'context', content: beforeLines[i - 1], oldLineNum: i, newLineNum: j })
      i--; j--
    } else if (j > 0 && (i === 0 || dp[i][j - 1] >= dp[i - 1][j])) {
      tmp.unshift({ type: 'add', content: afterLines[j - 1], newLineNum: j })
      j--
    } else {
      tmp.unshift({ type: 'remove', content: beforeLines[i - 1], oldLineNum: i })
      i--
    }
  }

  // collapse consecutive context ok(most extra keep 3 ok context)
  const CONTEXT_PADDING = 3
  let inContext = false
  let contextStart = 0

  for (let k = 0; k < tmp.length; k++) {
    if (tmp[k].type === 'context') {
      if (!inContext) {
        inContext = true
        contextStart = k
      }
    } else {
      if (inContext) {
        const contextLen = k - contextStart
        if (contextLen > CONTEXT_PADDING * 2) {
          // keep previous CONTEXT_PADDING and after CONTEXT_PADDING
          for (let c = contextStart + CONTEXT_PADDING; c < k - CONTEXT_PADDING; c++) {
            tmp[c] = { ...tmp[c], content: '⋯' }
          }
        }
        inContext = false
      }
    }
  }

  return tmp
}

// ===== InlineDiff component =====
export const InlineDiff = React.memo(function InlineDiff({
  beforeContent,
  afterContent,
  maxHeight = 300,
}: {
  beforeContent: string
  afterContent: string
  maxHeight?: number
}) {
  const { colors } = useThemeStore()
  const diffLines = useMemo(() => computeDiff(beforeContent, afterContent), [beforeContent, afterContent])

  const addedCount = diffLines.filter(l => l.type === 'add').length
  const removedCount = diffLines.filter(l => l.type === 'remove').length

  return (
    <div className="rounded-md overflow-hidden" style={{
      border: `1px solid ${colors.border}60`,
      backgroundColor: colors.bgPrimary,
    }}>
      {/* stats bar */}
      <div className="flex items-center gap-2 px-2 py-1" style={{
        backgroundColor: colors.bgSecondary,
        borderBottom: `1px solid ${colors.border}40`,
      }}>
        <span className="text-[10px] font-mono" style={{ color: '#22c55e' }}>+{addedCount}</span>
        <span className="text-[10px] font-mono" style={{ color: '#ef4444' }}>-{removedCount}</span>
        <div className="flex-1" />
      </div>
      {/* Diff content */}
      <div className="overflow-auto text-[11px] font-mono leading-relaxed" style={{ maxHeight }}>
        {diffLines.map((line, idx) => {
          if (line.content === '⋯') {
            return (
              <div key={idx} className="px-2 py-0.5 text-center" style={{ color: colors.textDim }}>
                ⋯
              </div>
            )
          }
          const bg = line.type === 'add' ? 'rgba(34,197,94,0.10)'
            : line.type === 'remove' ? 'rgba(239,68,68,0.10)'
            : 'transparent'
          const fg = line.type === 'add' ? '#22c55e'
            : line.type === 'remove' ? '#ef4444'
            : colors.text
          const prefix = line.type === 'add' ? '+' : line.type === 'remove' ? '-' : ' '

          return (
            <div key={idx} className="flex items-start px-1" style={{ backgroundColor: bg }}>
              <span className="flex-shrink-0 w-6 text-right pr-1 select-none" style={{ color: colors.textDim, fontSize: '10px' }}>
                {line.oldLineNum || ''}
              </span>
              <span className="flex-shrink-0 w-6 text-right pr-1 select-none" style={{ color: colors.textDim, fontSize: '10px' }}>
                {line.newLineNum || ''}
              </span>
              <span className="flex-shrink-0 w-4 select-none" style={{ color: fg }}>{prefix}</span>
              <span className="flex-1 whitespace-pre-wrap break-all" style={{ color: fg }}>{line.content || ' '}</span>
            </div>
          )
        })}
      </div>
    </div>
  )
}, (prev, next) => prev.beforeContent === next.beforeContent && prev.afterContent === next.afterContent)

// ===== DiffModal fullscreen diff =====
export const DiffModal = React.memo(function DiffModal({
  preview,
  onClose,
  onAccept,
  onRevert,
}: {
  preview: AiPatchPreview
  onClose: () => void
  onAccept?: () => void
  onRevert?: () => void
}) {
  const { colors } = useThemeStore()
  const sep = preview.path.lastIndexOf('/')
  const fileName = sep >= 0 ? preview.path.substring(sep + 1) : preview.path

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center" style={{ backgroundColor: 'rgba(0,0,0,0.5)' }} onClick={onClose}>
      <div className="w-[90%] h-[80%] rounded-xl overflow-hidden flex flex-col" style={{
        backgroundColor: colors.bgTertiary,
        border: `1px solid ${colors.border}`,
      }} onClick={e => e.stopPropagation()}>
        {/* header */}
        <div className="flex items-center gap-2 px-4 py-3 flex-shrink-0" style={{
          borderBottom: `1px solid ${colors.border}`,
          backgroundColor: colors.bgSecondary,
        }}>
          <span className="text-sm font-mono font-semibold" style={{ color: colors.text }}>{fileName}</span>
          <span className="text-[11px]" style={{ color: colors.textDim }}>{preview.path}</span>
          <div className="flex-1" />
          <span className="text-[10px] px-1.5 py-0.5 rounded" style={{ backgroundColor: 'rgba(34,197,94,0.15)', color: '#22c55e' }}>
            +{preview.addedLines}
          </span>
          <span className="text-[10px] px-1.5 py-0.5 rounded" style={{ backgroundColor: 'rgba(239,68,68,0.15)', color: '#ef4444' }}>
            -{preview.removedLines}
          </span>
          <button onClick={onClose} className="ml-2 p-1 rounded hover:opacity-70" style={{ color: colors.textDim }}>
            <svg className="w-4 h-4" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
              <line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/>
            </svg>
          </button>
        </div>
        {/* Diff content */}
        <div className="flex-1 overflow-auto p-3">
          <InlineDiff beforeContent={preview.beforeContent} afterContent={preview.afterContent} maxHeight={99999} />
        </div>
        {/* action bar */}
        <div className="flex items-center justify-end gap-2 px-4 py-3 flex-shrink-0" style={{
          borderTop: `1px solid ${colors.border}`,
          backgroundColor: colors.bgSecondary,
        }}>
          <button
            onClick={onRevert}
            className="px-3 py-1.5 rounded-md text-[12px] font-medium transition-colors hover:opacity-80"
            style={{
              backgroundColor: 'rgba(239,68,68,0.15)',
              color: '#ef4444',
              border: '1px solid rgba(239,68,68,0.3)',
            }}
          >
            ↩ Revert
          </button>
          <button
            onClick={onAccept}
            className="px-3 py-1.5 rounded-md text-[12px] font-medium transition-colors hover:opacity-80"
            style={{
              backgroundColor: 'rgba(34,197,94,0.15)',
              color: '#22c55e',
              border: '1px solid rgba(34,197,94,0.3)',
            }}
          >
            ✓ Accept
          </button>
        </div>
      </div>
    </div>
  )
})
