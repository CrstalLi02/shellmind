import React, { memo, useState, useRef, useEffect } from 'react'
import { useThemeStore } from '../stores/themeStore'

/**
 * MessageActionMenu - message action floating menu
 * mouse hover to message bubble up when show, extract for shortcut action.
 *
 * action list:
 * - copy(copy message content to clipboard)
 * - edit(user message: edit re- send; assistant message: unsupported)
 * - quote(quote the message content to input box)
 * - re- new generate(only assistant message: re- new generate reply)
 * - star(mark/ cancel mark re- need message)
 */

export interface MessageAction {
  type: 'copy' | 'edit' | 'quote' | 'regenerate' | 'bookmark'
  label: string
  icon: React.ReactNode
  onClick: () => void
  disabled?: boolean
}

interface MessageActionMenuProps {
  /** whether is user message */
  isUser: boolean
  /** whether already star */
  isBookmarked: boolean
  /** action callback */
  onCopy: () => void
  onEdit?: () => void
  onQuote: () => void
  onRegenerate?: () => void
  onToggleBookmark: () => void
}

// SVG icon component
const IconCopy = () => (
  <svg className="w-3.5 h-3.5" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
    <rect x="9" y="9" width="13" height="13" rx="2" ry="2" />
    <path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1" />
  </svg>
)

const IconEdit = () => (
  <svg className="w-3.5 h-3.5" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
    <path d="M12 20h9" />
    <path d="M16.5 3.5a2.121 2.121 0 0 1 3 3L7 19l-4 1 1-4L16.5 3.5z" />
  </svg>
)

const IconQuote = () => (
  <svg className="w-3.5 h-3.5" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
    <path d="M3 21c3 0 7-1 7-8V5c0-1.25-.756-2.017-2-2H4c-1.25 0-2 .75-2 1.972V11c0 1.25.75 2 2 2 1 0 1 0 1 1v1c0 1-1 2-2 2s-1 .008-1 1.031V20c0 1 0 1 1 1z" />
    <path d="M15 21c3 0 7-1 7-8V5c0-1.25-.757-2.017-2-2h-4c-1.25 0-2 .75-2 1.972V11c0 1.25.75 2 2 2h.75c0 2.25.25 4-2.75 4v3c0 1 0 1 1 1z" />
  </svg>
)

const IconRegenerate = () => (
  <svg className="w-3.5 h-3.5" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
    <path d="M3 12a9 9 0 0 1 9-9 9.75 9.75 0 0 1 6.74 2.74L21 8" />
    <path d="M21 3v5h-5" />
    <path d="M21 12a9 9 0 0 1-9 9 9.75 9.75 0 0 1-6.74-2.74L3 16" />
    <path d="M3 21v-5h5" />
  </svg>
)

const IconBookmark = ({ filled }: { filled: boolean }) => (
  <svg className="w-3.5 h-3.5" viewBox="0 0 24 24" fill={filled ? 'currentColor' : 'none'} stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
    <path d="M19 21l-7-5-7 5V5a2 2 0 0 1 2-2h10a2 2 0 0 1 2 2z" />
  </svg>
)

export const MessageActionMenu = memo(function MessageActionMenu({
  isUser,
  isBookmarked,
  onCopy,
  onEdit,
  onQuote,
  onRegenerate,
  onToggleBookmark,
}: MessageActionMenuProps) {
  const { colors } = useThemeStore()
  const [copied, setCopied] = useState(false)
  const [menuOpen, setMenuOpen] = useState(false)
  const menuRef = useRef<HTMLDivElement>(null)

  // click external close menu
  useEffect(() => {
    if (!menuOpen) return
    const handleClick = (e: MouseEvent) => {
      if (menuRef.current && !menuRef.current.contains(e.target as Node)) {
        setMenuOpen(false)
      }
    }
    document.addEventListener('mousedown', handleClick)
    return () => document.removeEventListener('mousedown', handleClick)
  }, [menuOpen])

  const handleCopy = () => {
    onCopy()
    setCopied(true)
    setTimeout(() => setCopied(false), 1500)
  }

  // build action list
  const actions: { key: string; label: string; icon: React.ReactNode; onClick: () => void; disabled?: boolean; danger?: boolean }[] = [
    {
      key: 'copy',
      label: copied ? 'Copied' : 'Copy',
      icon: <IconCopy />,
      onClick: handleCopy,
    },
    {
      key: 'quote',
      label: 'Quote',
      icon: <IconQuote />,
      onClick: () => { onQuote(); setMenuOpen(false) },
    },
  ]

  // user message: edit
  if (isUser && onEdit) {
    actions.push({
      key: 'edit',
      label: 'Edit',
      icon: <IconEdit />,
      onClick: () => { onEdit(); setMenuOpen(false) },
    })
  }

  // assistant message: re- new generate
  if (!isUser && onRegenerate) {
    actions.push({
      key: 'regenerate',
      label: 'Regenerate',
      icon: <IconRegenerate />,
      onClick: () => { onRegenerate(); setMenuOpen(false) },
    })
  }

  // star(all message)
  actions.push({
    key: 'bookmark',
    label: isBookmarked ? 'Unstar' : 'Star',
    icon: <IconBookmark filled={isBookmarked} />,
    onClick: () => { onToggleBookmark(); setMenuOpen(false) },
  })

  return (
    <div ref={menuRef} className="relative flex items-center gap-0.5 opacity-0 group-hover/msg:opacity-100 transition-opacity">
      {/* shortcut button(always visible) */}
      <button
        onClick={handleCopy}
        title="Copy"
        className="rounded p-1 transition-all hover:opacity-70"
        style={{ color: copied ? '#22c55e' : colors.textDim }}
      >
        {copied ? (
          <svg className="w-3 h-3" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2"><polyline points="20 6 9 17 4 12" /></svg>
        ) : (
          <IconCopy />
        )}
      </button>

      {/* star shortcut button */}
      <button
        onClick={onToggleBookmark}
        title={isBookmarked ? 'Unstar' : 'Star'}
        className="rounded p-1 transition-all hover:opacity-70"
        style={{ color: isBookmarked ? '#f59e0b' : colors.textDim }}
      >
        <IconBookmark filled={isBookmarked} />
      </button>

      {/* more extra button */}
      <button
        onClick={() => setMenuOpen(!menuOpen)}
        title="More actions"
        className="rounded p-1 transition-all hover:opacity-70"
        style={{ color: colors.textDim }}
      >
        <svg className="w-3.5 h-3.5" viewBox="0 0 24 24" fill="currentColor">
          <circle cx="12" cy="5" r="1.5" />
          <circle cx="12" cy="12" r="1.5" />
          <circle cx="12" cy="19" r="1.5" />
        </svg>
      </button>

      {/* dropdown menu */}
      {menuOpen && (
        <div
          className="absolute z-50 rounded-lg shadow-xl overflow-hidden"
          style={{
            backgroundColor: colors.bgSecondary,
            border: `1px solid ${colors.border}`,
            top: '100%',
            right: 0,
            marginTop: '4px',
            minWidth: '140px',
          }}
        >
          {actions.map((action) => (
            <button
              key={action.key}
              onClick={action.onClick}
              disabled={action.disabled}
              className="w-full flex items-center gap-2 px-3 py-1.5 text-left transition-colors hover:bg-black/5 disabled:opacity-40 disabled:cursor-not-allowed"
              style={{ color: action.danger ? '#ef4444' : colors.text }}
            >
              <span className="flex-shrink-0">{action.icon}</span>
              <span className="text-[11px]">{action.label}</span>
            </button>
          ))}
        </div>
      )}
    </div>
  )
})
