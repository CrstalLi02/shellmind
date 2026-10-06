import { memo, useMemo, useState, useRef, useEffect, useCallback } from 'react'
import { useThemeStore } from '../stores/themeStore'

/**
 * CommandMenu - `/` command + `@` mention menu
 * at input box center input `/` or `@` when popup menu, support keyboard lead navigate and mouse select.
 *
 * / command: shortcut action(clear, reset context, export etc)
 * @ mention: insert context tab(file, connection, terminal etc)
 */

export interface MenuItem {
  id: string
  label: string
  description?: string
  icon: string
  insertText: string
  category?: string
}

interface CommandMenuProps {
  /** trigger type */
  trigger: '/' | '@'
  /** trigger position place(at input text center index) */
  triggerIndex: number
  /** check query string(trigger token back side text) */
  query: string
  /** select callback */
  onSelect: (item: MenuItem) => void
  /** close callback */
  onClose: () => void
  /** available @ mention item */
  mentions?: MenuItem[]
}

// ===== predefined / command =====
const SLASH_COMMANDS: MenuItem[] = [
  { id: 'connect', label: 'Connect to server', description: 'Open SSH connection settings', icon: '🔌', insertText: '' },
  { id: 'disconnect', label: 'Disconnect', description: 'Disconnect the current SSH session', icon: '⚡', insertText: '' },
  { id: 'clear', label: 'Clear chat', description: 'Clear all messages in this session', icon: '🗑️', insertText: '' },
  { id: 'reset', label: 'Reset context', description: 'Clear context but keep messages', icon: '🔄', insertText: '' },
  { id: 'export', label: 'Export chat', description: 'Export as Markdown', icon: '📥', insertText: '' },
  { id: 'summary', label: 'Generate summary', description: 'Summarize this conversation', icon: '📋', insertText: 'Summarize this conversation: key decisions and remaining todos.' },
  { id: 'debug', label: 'Debug mode', description: 'Show the detailed ReAct execution trace', icon: '🐛', insertText: '' },
  { id: 'help', label: 'Help', description: 'See available commands and shortcuts', icon: '❓', insertText: '' },
  { id: 'ssh', label: 'SSH commands', description: 'Run an SSH command quickly', icon: '💻', insertText: 'Run the following SSH command:' },
  { id: 'edit', label: 'Edit file', description: 'Edit a remote file', icon: '✏️', insertText: 'Edit file ' },
  { id: 'read', label: 'Read file', description: 'Read remote file contents', icon: '📄', insertText: 'Read file ' },
  { id: 'search', label: 'Search', description: 'Search on the remote server', icon: '🔍', insertText: 'Search on the server: ' },
]

export const CommandMenu = memo(function CommandMenu({ trigger, query, onSelect, onClose, mentions }: Omit<CommandMenuProps, 'triggerIndex'>) {
  const { colors } = useThemeStore()
  const [selectedIndex, setSelectedIndex] = useState(0)
  const menuRef = useRef<HTMLDivElement>(null)

  // based on trigger token select data source
  const items: MenuItem[] = trigger === '/'
    ? SLASH_COMMANDS.filter(cmd =>
        cmd.label.toLowerCase().includes(query.toLowerCase()) ||
        cmd.id.toLowerCase().includes(query.toLowerCase())
      )
    : (mentions || []).filter(m =>
        m.label.toLowerCase().includes(query.toLowerCase())
      )

  const groupedItems = useMemo(() => {
    if (trigger !== '@') {
      return [{ title: '', items }]
    }

    const groups = new Map<string, MenuItem[]>()
    items.forEach((item) => {
      const title = item.category || 'Other'
      groups.set(title, [...(groups.get(title) || []), item])
    })
    return Array.from(groups.entries()).map(([title, groupItems]) => ({ title, items: groupItems }))
  }, [items, trigger])

  // reset selected index
  useEffect(() => {
    setSelectedIndex(0)
  }, [query, trigger])

  // keyboard lead navigate
  const handleKeyDown = useCallback((e: KeyboardEvent) => {
    if (e.key === 'ArrowDown') {
      e.preventDefault()
      setSelectedIndex(i => Math.min(i + 1, items.length - 1))
    } else if (e.key === 'ArrowUp') {
      e.preventDefault()
      setSelectedIndex(i => Math.max(i - 1, 0))
    } else if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault()
      if (items[selectedIndex]) {
        onSelect(items[selectedIndex])
      }
    } else if (e.key === 'Escape') {
      e.preventDefault()
      onClose()
    }
  }, [items, selectedIndex, onSelect, onClose])

  useEffect(() => {
    document.addEventListener('keydown', handleKeyDown, true)
    return () => document.removeEventListener('keydown', handleKeyDown, true)
  }, [handleKeyDown])

  if (items.length === 0) {
    return (
      <div
        ref={menuRef}
        className="absolute z-50 rounded-lg shadow-xl pointer-events-none"
        style={{
          backgroundColor: colors.bgSecondary,
          border: `1px solid ${colors.border}`,
          padding: '8px 12px',
          bottom: '100%',
          left: '50%',
          transform: 'translateX(-50%)',
          marginBottom: '4px',
        }}
      >
        <span className="text-[11px]" style={{ color: colors.textDim }}>
          {trigger === '/' ? 'No matching commands' : 'No matches'}
        </span>
      </div>
    )
  }

  return (
    <div
      ref={menuRef}
      className="absolute z-50 rounded-lg shadow-xl overflow-hidden"
      style={{
        backgroundColor: colors.bgSecondary,
        border: `1px solid ${colors.border}`,
        bottom: '100%',
        left: '50%',
        transform: 'translateX(-50%)',
        marginBottom: '4px',
        maxHeight: '280px',
        overflowY: 'auto',
        minWidth: '240px',
        maxWidth: '320px',
      }}
    >
      {/* header tab */}
      <div
        className="px-3 py-1.5 text-[10px] font-medium uppercase tracking-wider"
        style={{ backgroundColor: colors.bgTertiary, color: colors.textDim }}
      >
        {trigger === '/' ? 'Quick commands' : 'Mention'}
      </div>

      {/* menu item */}
      {(() => {
        let itemIndex = 0
        return groupedItems.map(({ title, items: groupItems }, groupIdx) => (
          <div
            key={title || 'default'}
            style={
              groupIdx > 0
                ? { borderTop: `1px solid ${colors.border}` }
                : undefined
            }
          >
            {trigger === '@' && title && (
              <div
                className="sticky top-0 z-10 px-3 py-1 text-[10px] font-medium"
                style={{ backgroundColor: colors.bgSecondary, color: colors.textDim }}
              >
                {title}
              </div>
            )}
            {groupItems.map((item) => {
              const index = itemIndex++
              return (
                <button
                  key={item.id}
                  onClick={() => onSelect(item)}
                  onMouseEnter={() => setSelectedIndex(index)}
                  className="w-full flex items-center gap-2.5 px-3 py-2 text-left transition-colors"
                  style={{
                    backgroundColor: index === selectedIndex ? `${colors.accent}15` : 'transparent',
                  }}
                >
                  <span className="text-base flex-shrink-0">{item.icon}</span>
                  <div className="flex-1 min-w-0">
                    <div className="text-[12px] font-medium truncate" style={{ color: colors.text }}>
                      {item.label}
                    </div>
                    {item.description && (
                      <div className="text-[10px] truncate" style={{ color: colors.textDim }}>
                        {item.description}
                      </div>
                    )}
                  </div>
                  {index === selectedIndex && (
                    <span className="text-[10px] flex-shrink-0 px-1 rounded" style={{ backgroundColor: `${colors.accent}20`, color: colors.accent }}>
                      ↵
                    </span>
                  )}
                </button>
              )
            })}
          </div>
        ))
      })()}

      {/* bottom hint */}
      <div
        className="px-3 py-1 text-[9px] flex items-center gap-2"
        style={{ backgroundColor: colors.bgTertiary, color: colors.textDim, borderTop: `1px solid ${colors.border}30` }}
      >
        <span>↑↓ Navigate</span>
        <span>↵ Select</span>
        <span>ESC Cancel</span>
      </div>
    </div>
  )
})

// ===== Hook: useCommandMenu =====
// at input box used in, detect / or @ trigger
export function useCommandMenu(
  inputValue: string,
  cursorPosition: number,
  mentions: MenuItem[],
): { trigger: '/' | '@' | null; triggerIndex: number; query: string; mentionsList: MenuItem[] } {
  // from caret position place to front find latest / or @
  const beforeCursor = inputValue.slice(0, cursorPosition)

  // find most back a not yet close merge / or @
  let trigger: '/' | '@' | null = null
  let triggerIndex = -1
  let query = ''

  for (let i = beforeCursor.length - 1; i >= 0; i--) {
    const char = beforeCursor[i]
    if (char === '/' || char === '@') {
      // ensure front side is space or ok first
      if (i === 0 || /\s/.test(beforeCursor[i - 1])) {
        trigger = char
        triggerIndex = i
        query = beforeCursor.slice(i + 1)
        // if query center has space, notes not is trigger menu
        if (query.includes(' ') || query.includes('\n')) {
          trigger = null
          triggerIndex = -1
          query = ''
        }
        break
      }
    }
    // meet to space then stop to front find
    if (/\s/.test(char) && i < beforeCursor.length - 1) {
      break
    }
  }

  return {
    trigger,
    triggerIndex,
    query,
    mentionsList: trigger === '@' ? mentions : [],
  }
}
