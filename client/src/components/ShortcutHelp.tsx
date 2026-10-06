import { useThemeStore } from '../stores/themeStore'

interface ShortcutHelpProps {
  open: boolean
  onClose: () => void
}

interface ShortcutGroup {
  title: string
  shortcuts: Array<{ keys: string; description: string }>
}

const SHORTCUT_GROUPS: ShortcutGroup[] = [
  {
    title: 'Chat',
    shortcuts: [
      { keys: 'Enter', description: 'Send message' },
      { keys: '⇧ Enter', description: 'Newline' },
      { keys: '⌘ Enter', description: 'Send (when Enter inserts a newline)' },
      { keys: '↑ / ↓', description: 'Browse input history' },
      { keys: '⌘ N', description: 'New session' },
    ],
  },
  {
    title: 'Input helpers',
    shortcuts: [
      { keys: '/', description: 'Open command menu' },
      { keys: '@', description: 'Mention a file, folder, or context' },
    ],
  },
  {
    title: 'Navigation',
    shortcuts: [
      { keys: '⌘ B', description: 'Toggle sidebar' },
      { keys: '⌘ ⇧ E', description: 'File explorer' },
      { keys: '⌘ ⇧ X', description: 'Extensions' },
      { keys: '⌘ `', description: 'Toggle terminal' },
    ],
  },
  {
    title: 'Tool',
    shortcuts: [
      { keys: '?', description: 'Open keyboard shortcuts' },
      { keys: 'Esc', description: 'Close dialog / cancel' },
      { keys: '⌘ C', description: 'Copy selection' },
      { keys: '⌘ S', description: 'Save current file' },
    ],
  },
]

/**
 * shortcut speed check table panel.
 * by? key popup,Esc close.
 */
export function ShortcutHelp({ open, onClose }: ShortcutHelpProps) {
  const { colors } = useThemeStore()

  if (!open) return null

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center"
      onClick={onClose}
    >
      {/* back scene overlay overlay */}
      <div
        className="absolute inset-0"
        style={{ backgroundColor: 'rgba(0,0,0,0.5)', backdropFilter: 'blur(4px)' }}
      />
      {/* panel */}
      <div
        className="relative w-[480px] max-h-[70vh] overflow-y-auto rounded-xl shadow-2xl"
        style={{
          backgroundColor: colors.bgPrimary,
          border: `1px solid ${colors.border}`,
        }}
        onClick={(e) => e.stopPropagation()}
      >
        {/* title */}
        <div className="flex items-center justify-between px-5 py-4" style={{ borderBottom: `1px solid ${colors.border}` }}>
          <div className="flex items-center gap-2">
            <span className="text-base">⌨️</span>
            <h2 className="text-[14px] font-semibold" style={{ color: colors.text }}>Keyboard shortcuts</h2>
          </div>
          <button
            onClick={onClose}
            className="w-6 h-6 rounded-md flex items-center justify-center transition-colors hover:opacity-70"
            style={{ backgroundColor: colors.bgSecondary, color: colors.textDim }}
          >
            ✕
          </button>
        </div>
        {/* shortcut list */}
        <div className="px-5 py-3 space-y-4">
          {SHORTCUT_GROUPS.map((group) => (
            <div key={group.title}>
              <h3 className="text-[11px] font-semibold uppercase tracking-wider mb-2" style={{ color: colors.textDim }}>
                {group.title}
              </h3>
              <div className="space-y-1.5">
                {group.shortcuts.map((s) => (
                  <div key={s.keys} className="flex items-center justify-between">
                    <span className="text-[12px]" style={{ color: colors.textSecondary }}>{s.description}</span>
                    <kbd
                      className="px-2 py-0.5 rounded text-[10px] font-mono"
                      style={{
                        backgroundColor: colors.bgSecondary,
                        color: colors.text,
                        border: `1px solid ${colors.border}`,
                        boxShadow: `0 1px 0 ${colors.border}`,
                      }}
                    >
                      {s.keys}
                    </kbd>
                  </div>
                ))}
              </div>
            </div>
          ))}
        </div>
        {/* bottom */}
        <div className="px-5 py-3 text-center" style={{ borderTop: `1px solid ${colors.border}` }}>
          <span className="text-[10px]" style={{ color: colors.textDim }}>Press Esc or click outside to close</span>
        </div>
      </div>
    </div>
  )
}
