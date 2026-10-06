import { useThemeStore } from '../stores/themeStore'
import { useConnectionStore } from '../stores/connectionStore'
import { RuntimeStatusIndicator } from './RuntimeStatusIndicator'
import { useLocalFileStore } from '../stores/localFileStore'
import { openPath } from '@tauri-apps/plugin-opener'

interface HeaderProps {
  onToggleChat: () => void
  chatVisible: boolean
  onOpenLocalFolder: () => void
}

export function Header({ onToggleChat, chatVisible }: HeaderProps) {
  const { colors } = useThemeStore()
  const { connections, currentConnectionId } = useConnectionStore()
  const currentConn = connections.find(c => c.id === currentConnectionId)
  const activeProjectPath = useLocalFileStore(state =>
    state.projects.find(project => project.id === state.activeProjectId)?.path ?? state.rootPath
  )

  const openProjectFolder = async () => {
    if (!activeProjectPath) return
    try {
      await openPath(activeProjectPath)
    } catch (error) {
      console.error('[Header] failed to open project folder:', error)
    }
  }

  return (
    <div
      data-tauri-drag-region
      className="h-12 flex items-center pl-[80px] pr-3 flex-shrink-0 select-none"
      style={{ backgroundColor: colors.bgSecondary, borderBottom: `1px solid ${colors.border}` }}
    >
      {/* left: connection info */}
      <div className="flex items-center gap-2">
        {/* connection info */}
        {currentConn && (
          <div className="flex items-center gap-2">
            <span className="w-2 h-2 rounded-full" style={{ backgroundColor: colors.green }} />
            <span className="text-xs font-medium" style={{ color: colors.text }}>{currentConn.name}</span>
            <span className="text-[11px] font-mono" style={{ color: colors.textDim }}>
              {currentConn.username}@{currentConn.host}:{currentConn.port}
            </span>
          </div>
        )}
      </div>

      {activeProjectPath && (
        <div data-tauri-drag-region className="flex-1 h-full flex items-center justify-center px-4 min-w-0">
          <button
            onClick={() => void openProjectFolder()}
            className="flex h-7 max-w-[52vw] items-center gap-1.5 rounded-md px-2 text-[11px] font-mono transition-colors hover:bg-black/5"
            style={{ color: colors.textDim }}
            title={`Open project folder: ${activeProjectPath}`}
          >
            <svg className="w-3.5 h-3.5 shrink-0" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
              <path d="M3 7a2 2 0 0 1 2-2h4l2 2h8a2 2 0 0 1 2 2v9a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z" />
            </svg>
            <span className="truncate">{activeProjectPath}</span>
          </button>
        </div>
      )}
      {!activeProjectPath && <div data-tauri-drag-region className="flex-1 h-full" />}

      {/* right:AI chat switch */}
      <div className="flex items-center gap-3">
        <RuntimeStatusIndicator />

        {/* AI chat switch */}
        <button
          onClick={onToggleChat}
            className="flex items-center gap-1.5 h-8 px-3 rounded-lg text-[13px] font-medium transition-all hover:brightness-105 active:scale-[0.98]"
          style={{
            backgroundColor: chatVisible ? colors.accentSoft : 'transparent',
            color: chatVisible ? colors.accent : colors.textSecondary,
            border: `1px solid ${chatVisible ? `${colors.accent}26` : 'transparent'}`,
          }}
          title="Show/hide AI assistant"
        >
          <svg className="w-4 h-4" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
            <path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z"></path>
          </svg>
          <span>AI assistant</span>
        </button>
      </div>
    </div>
  )
}
