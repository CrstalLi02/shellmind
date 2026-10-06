import React, { useMemo, useState } from 'react'
import { useThemeStore } from '../stores/themeStore'
import type { ChangeFile, ChangeSummary } from '../api/agent'

const fileMeta: Record<ChangeFile['kind'], { icon: string; label: string; color: string }> = {
  create: { icon: '+', label: 'Added', color: '#22c55e' },
  modify: { icon: '~', label: 'Modified', color: '#f59e0b' },
  delete: { icon: '-', label: 'Delete', color: '#ef4444' },
}

function SummaryFileRow({ file, colors }: {
  file: ChangeFile
  colors: ReturnType<typeof useThemeStore.getState>['colors']
}) {
  const sep = file.path.lastIndexOf('/')
  const dir = sep >= 0 ? file.path.substring(0, sep + 1) : ''
  const name = sep >= 0 ? file.path.substring(sep + 1) : file.path
  const meta = fileMeta[file.kind]

  return (
    <div className="flex min-w-0 items-center gap-1.5 py-0.5 text-[11px] font-mono">
      <span className="flex-shrink-0 text-[10px]" style={{ color: meta.color }}>{meta.icon}</span>
      <span className="flex-shrink-0 text-[10px]" style={{ color: meta.color }}>{meta.label}</span>
      <span className="truncate" style={{ color: colors.textDim }}>{dir}</span>
      <span className="flex-shrink-0" style={{ color: colors.text }}>{name}</span>
      <span className="ml-auto flex flex-shrink-0 items-center gap-1">
        {!!file.addedLines && <span style={{ color: '#22c55e' }}>+{file.addedLines}</span>}
        {!!file.removedLines && <span style={{ color: '#ef4444' }}>-{file.removedLines}</span>}
      </span>
    </div>
  )
}

export const SessionSummaryCard = React.memo(function SessionSummaryCard({ summary }: {
  summary: ChangeSummary
}) {
  const { colors } = useThemeStore()
  const [expanded, setExpanded] = useState(false)

  const allFiles = useMemo(() => ([
    ...(summary.created || []).map(file => ({ ...file, kind: 'create' as const })),
    ...(summary.modified || []).map(file => ({ ...file, kind: 'modify' as const })),
    ...(summary.deleted || []).map(file => ({ ...file, kind: 'delete' as const })),
  ]), [summary.created, summary.modified, summary.deleted])

  const totalAdded = allFiles.reduce((sum, file) => sum + (file.addedLines || 0), 0)
  const totalRemoved = allFiles.reduce((sum, file) => sum + (file.removedLines || 0), 0)

  return (
    <div
      className="w-full overflow-hidden rounded-xl"
      style={{ backgroundColor: colors.bgSecondary, border: `1px solid ${colors.border}80` }}
    >
      <button
        onClick={() => setExpanded(!expanded)}
        className="flex w-full items-center gap-2 px-3 py-2 text-left transition-colors hover:bg-black/[0.03]"
      >
        <span className="text-[12px] font-semibold" style={{ color: colors.text }}>Change summary</span>
        <span
          className="rounded-full px-1.5 py-0.5 text-[10px] font-medium"
          style={{ backgroundColor: `${colors.accent}18`, color: colors.accent }}
        >
          {allFiles.length} files
        </span>
        {totalAdded > 0 && <span className="text-[10px] font-mono" style={{ color: '#22c55e' }}>+{totalAdded}</span>}
        {totalRemoved > 0 && <span className="text-[10px] font-mono" style={{ color: '#ef4444' }}>-{totalRemoved}</span>}
        <span className="ml-auto text-[10px]" style={{ color: colors.textDim }}>
          {expanded ? 'Collapse' : 'Expand'}
        </span>
        <svg
          className={`h-3 w-3 flex-shrink-0 transition-transform ${expanded ? 'rotate-180' : ''}`}
          style={{ color: colors.textDim }}
          viewBox="0 0 24 24"
          fill="none"
          stroke="currentColor"
          strokeWidth="2"
        >
          <polyline points="6 9 12 15 18 9" />
        </svg>
      </button>

      {expanded && (
        <div className="space-y-0.5 border-t px-3 py-2" style={{ borderColor: `${colors.border}40` }}>
          {allFiles.map((file, index) => (
            <SummaryFileRow key={`${file.path}-${index}`} file={file} colors={colors} />
          ))}
          {summary.description && (
            <div className="pt-1 text-[11px] leading-relaxed" style={{ color: colors.textDim }}>
              {summary.description}
            </div>
          )}
        </div>
      )}
    </div>
  )
})
