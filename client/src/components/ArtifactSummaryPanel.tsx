/**
 * ArtifactSummaryPanel - produce object collect total panel
 * see Android side Chat.tsx Artifact Summary design
 * drop at input box up square, shown when there are unconfirmed file changes
 * collapsed: form ok summary bar(produce object collect total + N pending confirmation + all same meaning/ all undo)
 * expanded: file list + each file diff stats + Accept/Revert action
 */
import { memo, useState, useMemo, useCallback } from 'react'
import { useThemeStore } from '../stores/themeStore'
import { useAiPatchStore, type AiPatchPreview } from '../stores/aiPatchStore'
import { useLocalFileStore } from '../stores/localFileStore'
import { useFileExplorerStore } from '../stores/fileExplorerStore'
import { InlineDiff } from './InlineDiff'

// ===== form file change ok =====
const ArtifactFileRow = memo(function ArtifactFileRow({
  preview,
  colors,
}: {
  preview: AiPatchPreview
  colors: ReturnType<typeof useThemeStore.getState>['colors']
}) {
  const [expanded, setExpanded] = useState(false)
  const [status, setStatus] = useState<'pending' | 'accepted' | 'reverted'>('pending')
  const [reverting, setReverting] = useState(false)
  const removePreview = useAiPatchStore(s => s.removePreview)
  const openDiffTabRemote = useFileExplorerStore(s => s.openDiffTab)
  const openDiffTabLocal = useLocalFileStore(s => s.openDiffTab)

  const sep = preview.path.lastIndexOf('/')
  const fileName = sep >= 0 ? preview.path.substring(sep + 1) : preview.path
  const dirPath = sep >= 0 ? preview.path.substring(0, sep + 1) : ''
  const isRemote = preview.target === 'remote'
  // change type
  const changeKind = useMemo(() => {
    if (preview.removedLines === 0 && preview.addedLines > 0) return { text: 'Added', color: '#22c55e' }
    if (preview.addedLines === 0 && preview.removedLines > 0) return { text: 'Delete', color: '#ef4444' }
    return { text: 'Modified', color: '#f59e0b' }
  }, [preview.addedLines, preview.removedLines])

  // language icon
  const fileIcon = useMemo(() => {
    const ext = preview.path.split('.').pop()?.toLowerCase() || ''
    const iconMap: Record<string, string> = {
      java: '☕', kt: '🟣', py: '🐍', go: '🔵', rs: '🦀', ts: '🔷', tsx: '🔷',
      js: '🟨', jsx: '🟨', vue: '💚', html: '🟧', css: '🎨', json: '📋',
      yml: '📋', xml: '📋', sh: '🖥️', md: '📝', sql: '🗃️', properties: '⚙️',
    }
    return iconMap[ext] || '📄'
  }, [preview.path])

  const handleAccept = useCallback(() => {
    removePreview(preview.id)
    setStatus('accepted')
  }, [preview.id, removePreview])

  const handleRevert = useCallback(async () => {
    setReverting(true)
    try {
      if (preview.target === 'local') {
        await useLocalFileStore.getState().restoreFileContent(preview.path, preview.beforeContent)
      } else if (preview.target === 'remote' && preview.connectionId) {
        await useFileExplorerStore.getState().restoreFileContent(preview.connectionId, preview.path, preview.beforeContent)
      }
      removePreview(preview.id)
      setStatus('reverted')
    } catch (e) {
      console.error('[ArtifactSummaryPanel] Revert failed:', e)
    } finally {
      setReverting(false)
    }
  }, [preview, removePreview])

  // already handled state
  if (status !== 'pending') {
    return (
      <div
        className="flex items-center gap-2 px-3 py-1.5 rounded-md"
        style={{
          backgroundColor: status === 'accepted' ? 'rgba(34,197,94,0.06)' : 'rgba(239,68,68,0.06)',
          opacity: 0.6,
        }}
      >
        <span className="text-xs flex-shrink-0">{status === 'accepted' ? '✅' : '↩️'}</span>
        <span className="text-[11px] font-mono truncate flex-1 min-w-0" style={{ color: colors.textDim }}>
          {fileName}
        </span>
        <span className="text-[10px] flex-shrink-0" style={{ color: status === 'accepted' ? '#22c55e' : '#ef4444' }}>
          {status === 'accepted' ? 'Accepted' : 'Reverted'}
        </span>
      </div>
    )
  }

  return (
    <div
      className="rounded-md overflow-hidden transition-all"
      style={{
        border: `1px solid ${expanded ? `${colors.accent}30` : `${colors.border}60`}`,
        backgroundColor: expanded ? colors.bgSecondary : 'transparent',
      }}
    >
      {/* file row */}
      <div
        className="flex items-center gap-2 px-3 py-1.5 cursor-pointer transition-colors hover:bg-black/5"
        onClick={() => {
          // based on target select align confirm store:local → localFileStore,remote → fileExplorerStore
          if (preview.target === 'local') {
            openDiffTabLocal(preview.id)
          } else {
            openDiffTabRemote(preview.id)
          }
        }}
        title="View diff"
      >
        <span className="text-xs flex-shrink-0">{fileIcon}</span>
        <div className="flex flex-col min-w-0 flex-1">
          <div className="flex items-center gap-1.5 min-w-0">
            <span className="text-[11px] font-mono font-medium truncate" style={{ color: colors.text }}>
              {fileName}
            </span>
            <span
              className="text-[9px] px-1 py-0 rounded-full flex-shrink-0 font-medium"
              style={{ backgroundColor: `${changeKind.color}15`, color: changeKind.color }}
            >
              {changeKind.text}
            </span>
            {isRemote && (
              <span className="text-[9px] px-1 py-0 rounded-full flex-shrink-0 font-medium" style={{
                backgroundColor: `${colors.accent}15`, color: colors.accent,
              }}>SSH</span>
            )}
          </div>
          <span className="text-[10px] font-mono truncate" style={{ color: colors.textDim }}>{dirPath}</span>
        </div>
        {/* ok number stats */}
        <span className="text-[10px] font-mono flex-shrink-0" style={{ color: '#22c55e' }}>+{preview.addedLines}</span>
        <span className="text-[10px] font-mono flex-shrink-0" style={{ color: '#ef4444' }}>-{preview.removedLines}</span>
        {/* action button(collapsed also show shortcut action) */}
        <button
          onClick={(e) => { e.stopPropagation(); handleAccept() }}
          className="flex items-center gap-0.5 px-1.5 py-0.5 rounded text-[10px] font-medium transition-all hover:opacity-80 flex-shrink-0"
          style={{
            backgroundColor: 'rgba(34,197,94,0.10)',
            color: '#22c55e',
            border: '1px solid rgba(34,197,94,0.20)',
          }}
          title="Accept change"
        >
          ✓
        </button>
        <button
          onClick={(e) => { e.stopPropagation(); handleRevert() }}
          disabled={reverting}
          className="flex items-center gap-0.5 px-1.5 py-0.5 rounded text-[10px] font-medium transition-all hover:opacity-80 disabled:opacity-50 flex-shrink-0"
          style={{
            backgroundColor: 'rgba(239,68,68,0.10)',
            color: '#ef4444',
            border: '1px solid rgba(239,68,68,0.20)',
          }}
          title="Revert change"
        >
          ✕
        </button>
        {/* Diff compare button */}
        <button
          onClick={(e) => { e.stopPropagation(); if (preview.target === 'local') { openDiffTabLocal(preview.id) } else { openDiffTabRemote(preview.id) } }}
          className="flex items-center gap-0.5 px-1.5 py-0.5 rounded text-[10px] font-medium transition-all hover:opacity-80 flex-shrink-0"
          style={{
            backgroundColor: `${colors.accent}10`,
            color: colors.accent,
            border: `1px solid ${colors.accent}20`,
          }}
          title="View diff"
        >
          🔀
        </button>
        <svg
          className={`w-3 h-3 transition-transform flex-shrink-0 ${expanded ? 'rotate-180' : ''}`}
          style={{ color: colors.textDim }}
          viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2"
          onClick={(e) => { e.stopPropagation(); setExpanded(!expanded) }}
        >
          <polyline points="6 9 12 15 18 9" />
        </svg>
      </div>

      {/* expanded:Diff preview */}
      {expanded && (
        <div className="animate-in slide-in-from-top-1 duration-200">
          <div style={{ borderTop: `1px solid ${colors.border}30` }} />
          <div className="px-2 py-2">
            <InlineDiff
              beforeContent={preview.beforeContent}
              afterContent={preview.afterContent}
              maxHeight={200}
            />
          </div>
          <div className="flex items-center justify-between px-3 py-1.5" style={{ borderTop: `1px solid ${colors.border}20` }}>
            <span className="text-[10px]" style={{ color: colors.textDim }}>{preview.toolName}</span>
            <div className="flex items-center gap-2">
              <button
                onClick={(e) => { e.stopPropagation(); handleRevert() }}
                disabled={reverting}
                className="flex items-center gap-1 px-2 py-1 rounded-md text-[11px] font-medium transition-all hover:opacity-80 disabled:opacity-50"
                style={{ backgroundColor: 'rgba(239,68,68,0.10)', color: '#ef4444', border: '1px solid rgba(239,68,68,0.25)' }}
              >
                {reverting ? '⏳' : '↩️'} Revert
              </button>
              <button
                onClick={(e) => { e.stopPropagation(); handleAccept() }}
                className="flex items-center gap-1 px-2 py-1 rounded-md text-[11px] font-medium transition-all hover:opacity-80"
                style={{ backgroundColor: 'rgba(34,197,94,0.10)', color: '#22c55e', border: '1px solid rgba(34,197,94,0.25)' }}
              >
                ✓ Accept
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  )
}, (prev, next) => {
  return prev.preview.id === next.preview.id
    && prev.preview.addedLines === next.preview.addedLines
    && prev.preview.removedLines === next.preview.removedLines
    && prev.preview.beforeContent === next.preview.beforeContent
    && prev.preview.afterContent === next.preview.afterContent
    && prev.colors === next.colors
})

// ===== ArtifactSummaryPanel main component =====
export const ArtifactSummaryPanel = memo(function ArtifactSummaryPanel() {
  const { colors } = useThemeStore()
  const previews = useAiPatchStore(s => s.previews)
  const removePreview = useAiPatchStore(s => s.removePreview)
  const [expanded, setExpanded] = useState(false)

  // only show pending status preview(already accepted/reverted not at this panel handle, by component from self manage)
  const pendingPreviews = previews

  // ⚠️ Hook rule:useCallback must at condition return front call
  // all accept
  const handleAcceptAll = useCallback(() => {
    if (pendingPreviews.length === 0) return
    for (const p of pendingPreviews) {
      removePreview(p.id)
    }
  }, [pendingPreviews, removePreview])

  // all round back
  const handleRevertAll = useCallback(async () => {
    if (pendingPreviews.length === 0) return
    for (const p of pendingPreviews) {
      try {
        if (p.target === 'local') {
          await useLocalFileStore.getState().restoreFileContent(p.path, p.beforeContent)
        } else if (p.target === 'remote' && p.connectionId) {
          await useFileExplorerStore.getState().restoreFileContent(p.connectionId, p.path, p.beforeContent)
        }
        removePreview(p.id)
      } catch (e) {
        console.error('[ArtifactSummaryPanel] Revert failed:', p.path, e)
      }
    }
  }, [pendingPreviews, removePreview])

  // ⚠️ Hook rule: all Hook must at condition return front call
  // pendingPreviews is empty when return null, but Hook count must match the previous render
  if (pendingPreviews.length === 0) return null

  const totalAdded = pendingPreviews.reduce((s, p) => s + p.addedLines, 0)
  const totalRemoved = pendingPreviews.reduce((s, p) => s + p.removedLines, 0)

  return (
    <div
      className="border-t flex-shrink-0"
      style={{ borderColor: colors.border, backgroundColor: colors.bgSecondary }}
    >
      {/* summary bar */}
      <div
        onClick={() => setExpanded(!expanded)}
        role="button"
        tabIndex={0}
        onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ' ') setExpanded(!expanded) }}
        className="w-full flex items-center justify-between px-4 py-2 hover:bg-black/5 transition-colors cursor-pointer select-none"
      >
        <div className="flex items-center gap-2">
          {/* file icon */}
          <svg className="w-4 h-4 text-amber-400 flex-shrink-0" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
            <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z" />
            <polyline points="14 2 14 8 20 8" />
            <line x1="16" y1="13" x2="8" y2="13" />
            <line x1="16" y1="17" x2="8" y2="17" />
          </svg>
          <span className="text-[12px] font-medium" style={{ color: colors.text }}>Artifacts</span>
          <span
            className="px-1.5 py-0.5 text-[10px] rounded-full font-medium flex-shrink-0"
            style={{
              backgroundColor: 'rgba(245,158,11,0.15)',
              color: '#d97706',
            }}
          >
            {pendingPreviews.length} pending
          </span>
          {/* ok number stats */}
          <span className="text-[10px] font-mono flex-shrink-0" style={{ color: '#22c55e' }}>+{totalAdded}</span>
          <span className="text-[10px] font-mono flex-shrink-0" style={{ color: '#ef4444' }}>-{totalRemoved}</span>
        </div>
        <div className="flex items-center gap-3">
          {/* all accept */}
          <button
            onClick={(e) => { e.stopPropagation(); handleAcceptAll() }}
            className="flex items-center gap-1 px-2.5 py-1 text-[11px] font-medium rounded-md transition-all hover:opacity-80"
            style={{
              backgroundColor: 'rgba(34,197,94,0.10)',
              color: '#22c55e',
              border: '1px solid rgba(34,197,94,0.25)',
            }}
            title="Accept all changes"
          >
            ✓ Accept all
          </button>
          {/* all round back */}
          <button
            onClick={(e) => { e.stopPropagation(); handleRevertAll() }}
            className="flex items-center gap-1 px-2.5 py-1 text-[11px] font-medium rounded-md transition-all hover:opacity-80"
            style={{
              backgroundColor: 'rgba(239,68,68,0.10)',
              color: '#ef4444',
              border: '1px solid rgba(239,68,68,0.25)',
            }}
            title="Revert all changes"
          >
            ✕ Revert all
          </button>
          {/* expand arrow head */}
          <svg
            className={`w-3.5 h-3.5 transition-transform duration-200 flex-shrink-0 ${expanded ? 'rotate-180' : ''}`}
            style={{ color: colors.textSecondary }}
            viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2"
          >
            <polyline points="6 9 12 15 18 9" />
          </svg>
        </div>
      </div>

      {/* expanded: file list */}
      {expanded && (
        <div
          className="px-3 pb-3 space-y-1 overflow-y-auto"
          style={{ maxHeight: '280px' }}
        >
          {pendingPreviews.map((preview) => (
            <ArtifactFileRow
              key={preview.id}
              preview={preview}
              colors={colors}
            />
          ))}
        </div>
      )}
    </div>
  )
})
