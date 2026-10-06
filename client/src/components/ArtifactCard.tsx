import { memo, useState, useMemo, useCallback } from 'react'
import { useThemeStore } from '../stores/themeStore'
import { useAiPatchStore, type AiPatchPreview } from '../stores/aiPatchStore'
import { useLocalFileStore } from '../stores/localFileStore'
import { useFileExplorerStore } from '../stores/fileExplorerStore'
import { InlineDiff } from './InlineDiff'

// ===== ArtifactCard action status =====
type ArtifactStatus = 'pending' | 'accepted' | 'reverted'

// ===== Diff line calc(lightweight, only stats use) =====
function countDiffStats(before: string, after: string): { added: number; removed: number } {
  const beforeLines = before.split('\n')
  const afterLines = after.split('\n')
  const beforeSet = new Set(beforeLines)
  const afterSet = new Set(afterLines)
  let added = 0
  let removed = 0
  for (const line of afterLines) {
    if (!beforeSet.has(line)) added++
  }
  for (const line of beforeLines) {
    if (!afterSet.has(line)) removed++
  }
  return { added, removed }
}

// ===== file language icon map =====
function getFileIcon(path: string): string {
  const ext = path.split('.').pop()?.toLowerCase() || ''
  const iconMap: Record<string, string> = {
    java: '☕', kt: '🟣', py: '🐍', go: '🔵', rs: '🦀', rb: '💎', php: '🐘',
    ts: '🔷', tsx: '🔷', js: '🟨', jsx: '🟨', vue: '💚', html: '🟧', css: '🎨', scss: '🎨',
    json: '📋', yml: '📋', yaml: '📋', toml: '📋', xml: '📋',
    sh: '🖥️', bash: '🖥️', zsh: '🖥️',
    sql: '🗃️', md: '📝', txt: '📄', properties: '⚙️', conf: '⚙️', env: '🔒',
    gradle: '🐘', dockerfile: '🐳',
  }
  return iconMap[ext] || '📄'
}

// ===== change type tab =====
function getChangeKindLabel(added: number, removed: number): { text: string; color: string } {
  if (removed === 0 && added > 0) return { text: 'Added', color: '#22c55e' }
  if (added === 0 && removed > 0) return { text: 'Delete', color: '#ef4444' }
  return { text: 'Modified', color: '#f59e0b' }
}

// ===== ArtifactCard main component =====
export const ArtifactCard = memo(function ArtifactCard({
  preview,
  compact = false,
  onStatusChange,
}: {
  preview: AiPatchPreview
  /** tight assemble mode: embed ProcessTimeline compact inline */
  compact?: boolean
  /** status change callback */
  onStatusChange?: (status: ArtifactStatus) => void
}) {
  const { colors } = useThemeStore()
  const removePreview = useAiPatchStore(s => s.removePreview)
  const [expanded, setExpanded] = useState(false)
  const [status, setStatus] = useState<ArtifactStatus>('pending')
  const [reverting, setReverting] = useState(false)

  const sep = preview.path.lastIndexOf('/')
  const fileName = sep >= 0 ? preview.path.substring(sep + 1) : preview.path
  const dirPath = sep >= 0 ? preview.path.substring(0, sep + 1) : ''

  const { added, removed } = useMemo(
    () => countDiffStats(preview.beforeContent, preview.afterContent),
    [preview.beforeContent, preview.afterContent]
  )
  const changeKind = getChangeKindLabel(added, removed)
  const fileIcon = getFileIcon(preview.path)
  const isRemote = preview.target === 'remote'

  // Accept: keep current content, clear remove preview
  const handleAccept = useCallback(() => {
    removePreview(preview.id)
    setStatus('accepted')
    onStatusChange?.('accepted')
  }, [preview.id, removePreview, onStatusChange])

  // Revert: restore original content
  const handleRevert = useCallback(async () => {
    setReverting(true)
    try {
      const localStore = useLocalFileStore.getState()
      const remoteStore = useFileExplorerStore.getState()

      if (preview.target === 'local') {
        await localStore.restoreFileContent(preview.path, preview.beforeContent)
      } else if (preview.target === 'remote' && preview.connectionId) {
        await remoteStore.restoreFileContent(preview.connectionId, preview.path, preview.beforeContent)
      }

      removePreview(preview.id)
      setStatus('reverted')
      onStatusChange?.('reverted')
    } catch (e) {
      console.error('[ArtifactCard] Revert failed:', e)
    } finally {
      setReverting(false)
    }
  }, [preview, removePreview, onStatusChange])

  // ===== already accept state =====
  if (status === 'accepted') {
    return (
      <div
        className="flex items-center gap-2 px-3 py-1.5 rounded-md transition-all"
        style={{
          backgroundColor: 'rgba(34,197,94,0.06)',
          border: `1px solid rgba(34,197,94,0.15)`,
          opacity: 0.7,
        }}
      >
        <svg className="w-3.5 h-3.5 flex-shrink-0" viewBox="0 0 24 24" fill="#22c55e">
          <circle cx="12" cy="12" r="10" />
          <polyline points="8 12 11 15 16 10" fill="none" stroke="#fff" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" />
        </svg>
        <span className="text-[11px] font-mono truncate" style={{ color: colors.textDim }}>
          {fileName}
        </span>
        <span className="text-[10px]" style={{ color: '#22c55e' }}>Accepted</span>
      </div>
    )
  }

  // ===== already round back state =====
  if (status === 'reverted') {
    return (
      <div
        className="flex items-center gap-2 px-3 py-1.5 rounded-md transition-all"
        style={{
          backgroundColor: 'rgba(239,68,68,0.06)',
          border: `1px solid rgba(239,68,68,0.15)`,
          opacity: 0.7,
        }}
      >
        <svg className="w-3.5 h-3.5 flex-shrink-0" viewBox="0 0 24 24" fill="none" stroke="#ef4444" strokeWidth="2">
          <polyline points="1 4 1 10 7 10" />
          <path d="M3.51 15a9 9 0 1 0 2.13-9.36L1 10" />
        </svg>
        <span className="text-[11px] font-mono truncate" style={{ color: colors.textDim }}>
          {fileName}
        </span>
        <span className="text-[10px]" style={{ color: '#ef4444' }}>Reverted</span>
      </div>
    )
  }

  // ===== tight assemble mode =====
  if (compact) {
    return (
      <div
        className="flex items-center gap-1.5 px-2 py-1 rounded group/artifact hover:bg-black/5 transition-colors min-w-0"
      >
        <span className="text-xs flex-shrink-0">{fileIcon}</span>
        <span className="text-[11px] font-mono font-medium truncate flex-shrink-0" style={{ color: colors.text }}>
          {fileName}
        </span>
        <span className="text-[10px] flex-shrink-0" style={{ color: '#22c55e' }}>+{added}</span>
        <span className="text-[10px] flex-shrink-0" style={{ color: '#ef4444' }}>-{removed}</span>
        {isRemote && (
          <span className="text-[9px] flex-shrink-0 px-1 rounded" style={{
            backgroundColor: `${colors.accent}15`, color: colors.accent,
          }}>SSH</span>
        )}
      </div>
    )
  }

  // ===== align always mode: collapse/ expand =====
  return (
    <div
      className="rounded-lg overflow-hidden transition-all"
      style={{
        border: `1px solid ${expanded ? `${colors.accent}30` : `${colors.border}60`}`,
        backgroundColor: colors.bgSecondary,
      }}
    >
      {/* header: file info + stats */}
      <button
        onClick={() => setExpanded(!expanded)}
        className="w-full flex items-center gap-2 px-3 py-2 text-left transition-colors hover:bg-black/5"
      >
        <span className="text-sm flex-shrink-0">{fileIcon}</span>
        <div className="flex flex-col min-w-0 flex-1">
          <div className="flex items-center gap-1.5 min-w-0">
            <span className="text-[12px] font-mono font-semibold truncate" style={{ color: colors.text }}>
              {fileName}
            </span>
            <span
              className="text-[9px] px-1.5 py-0.5 rounded-full flex-shrink-0 font-medium"
              style={{
                backgroundColor: `${changeKind.color}15`,
                color: changeKind.color,
              }}
            >
              {changeKind.text}
            </span>
            {isRemote && (
              <span
                className="text-[9px] px-1.5 py-0.5 rounded-full flex-shrink-0 font-medium"
                style={{ backgroundColor: `${colors.accent}15`, color: colors.accent }}
              >
                SSH
              </span>
            )}
          </div>
          <span className="text-[10px] font-mono truncate" style={{ color: colors.textDim }}>
            {dirPath}
          </span>
        </div>

        {/* ok number stats */}
        <div className="flex items-center gap-1.5 flex-shrink-0">
          <span className="text-[10px] font-mono px-1.5 py-0.5 rounded" style={{
            backgroundColor: 'rgba(34,197,94,0.12)', color: '#22c55e',
          }}>
            +{added}
          </span>
          <span className="text-[10px] font-mono px-1.5 py-0.5 rounded" style={{
            backgroundColor: 'rgba(239,68,68,0.12)', color: '#ef4444',
          }}>
            -{removed}
          </span>
        </div>

        {/* expand arrow head */}
        <svg
          className={`w-3.5 h-3.5 transition-transform flex-shrink-0 ${expanded ? 'rotate-180' : ''}`}
          style={{ color: colors.textDim }}
          viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2"
        >
          <polyline points="6 9 12 15 18 9" />
        </svg>
      </button>

      {/* expanded:Diff preview + action button */}
      {expanded && (
        <div className="animate-in slide-in-from-top-1 duration-200">
          {/* Diff divider */}
          <div style={{ borderTop: `1px solid ${colors.border}40` }} />

          {/* Diff content */}
          <div className="px-2 py-2">
            <InlineDiff
              beforeContent={preview.beforeContent}
              afterContent={preview.afterContent}
              maxHeight={200}
            />
          </div>

          {/* action bar */}
          <div
            className="flex items-center justify-between px-3 py-2"
            style={{ borderTop: `1px solid ${colors.border}30` }}
          >
            <span className="text-[10px]" style={{ color: colors.textDim }}>
              {preview.toolName}
            </span>
            <div className="flex items-center gap-2">
              <button
                onClick={(e) => { e.stopPropagation(); handleRevert() }}
                disabled={reverting}
                className="flex items-center gap-1 px-2.5 py-1 rounded-md text-[11px] font-medium transition-all hover:opacity-80 disabled:opacity-50"
                style={{
                  backgroundColor: 'rgba(239,68,68,0.10)',
                  color: '#ef4444',
                  border: '1px solid rgba(239,68,68,0.25)',
                }}
              >
                {reverting ? (
                  <svg className="w-3 h-3 animate-spin" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                    <path d="M21 12a9 9 0 1 1-6.219-8.56" />
                  </svg>
                ) : (
                  <svg className="w-3 h-3" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                    <polyline points="1 4 1 10 7 10" />
                    <path d="M3.51 15a9 9 0 1 0 2.13-9.36L1 10" />
                  </svg>
                )}
                Revert
              </button>
              <button
                onClick={(e) => { e.stopPropagation(); handleAccept() }}
                className="flex items-center gap-1 px-2.5 py-1 rounded-md text-[11px] font-medium transition-all hover:opacity-80"
                style={{
                  backgroundColor: 'rgba(34,197,94,0.10)',
                  color: '#22c55e',
                  border: '1px solid rgba(34,197,94,0.25)',
                }}
              >
                <svg className="w-3 h-3" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                  <polyline points="20 6 9 17 4 12" />
                </svg>
                Accept
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  )
}, (prev, next) => {
  return prev.preview.id === next.preview.id
    && prev.preview.beforeContent === next.preview.beforeContent
    && prev.preview.afterContent === next.preview.afterContent
    && prev.compact === next.compact
})

// ===== ArtifactSummaryBar: change stats badge chapter =====
export const ArtifactSummaryBar = memo(function ArtifactSummaryBar({
  previews,
  colors,
}: {
  previews: AiPatchPreview[]
  colors: ReturnType<typeof useThemeStore.getState>['colors']
}) {
  if (previews.length === 0) return null

  const totalAdded = previews.reduce((sum, p) => sum + p.addedLines, 0)
  const totalRemoved = previews.reduce((_sum, p) => p.removedLines, 0)
  const fileCount = previews.length

  return (
    <span
      className="inline-flex items-center gap-1 px-1.5 py-0.5 rounded-full flex-shrink-0 text-[10px] font-medium"
      style={{
        backgroundColor: `${colors.accent}15`,
        color: colors.accent,
        border: `1px solid ${colors.accent}25`,
      }}
    >
      <svg className="w-3 h-3" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
        <path d="M11 4H4a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7" />
        <path d="M18.5 2.5a2.121 2.121 0 0 1 3 3L12 15l-4 1 1-4 9.5-9.5z" />
      </svg>
      {fileCount}  file changes
      <span style={{ color: '#22c55e' }}>+{totalAdded}</span>
      <span style={{ color: '#ef4444' }}>-{totalRemoved}</span>
    </span>
  )
}, (prev, next) => {
  return prev.previews.length === next.previews.length
    && prev.previews.every((p, i) => p.id === next.previews[i]?.id)
    && prev.colors === next.colors
})
