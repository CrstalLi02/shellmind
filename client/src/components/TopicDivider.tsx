import { memo, useState } from 'react'
import { useThemeStore } from '../stores/themeStore'

/**
 * TopicDivider - chat title separator token
 * detect message middle chat title switch(time middle gap / corner color mode / content relative like), insert ok collapse separator token.
 *
 * detect strategy slightly:
 * 1. time middle gap > 10 min → new topic
 * 2. consecutive user message(no in between assistant reply)→ merge to same chat title
 * 3. user extract for fallback title(ok manual edit)
 */

interface TopicDividerProps {
  prevTimestamp: number
  currTimestamp: number
  topicIndex: number
  defaultTitle?: string
}

function formatTopicTime(ts: number): string {
  const d = new Date(ts)
  const mo = (d.getMonth() + 1).toString().padStart(2, '0')
  const day = d.getDate().toString().padStart(2, '0')
  const h = d.getHours()
  const ap = h < 12 ? 'AM' : 'PM'
  const h12 = h === 0 ? 12 : h > 12 ? h - 12 : h
  const m = d.getMinutes().toString().padStart(2, '0')
  return `${mo}-${day} ${ap}${h12}:${m}`
}

export const TopicDivider = memo(function TopicDivider({ prevTimestamp, currTimestamp, topicIndex, defaultTitle }: TopicDividerProps) {
  const { colors } = useThemeStore()
  const [collapsed, setCollapsed] = useState(false)
  const [title, setTitle] = useState(defaultTitle || `Topic ${topicIndex + 1}`)
  const [editing, setEditing] = useState(false)

  const gapMs = currTimestamp - prevTimestamp
  const gapMin = Math.floor(gapMs / 60000)
  const gapText = gapMin >= 60
    ? `${Math.floor(gapMin / 60)}h ${gapMin % 60}m`
    : `${gapMin}m`

  return (
    <div className="flex items-center gap-2 px-4 py-1.5 select-none" style={{ borderTop: `1px solid ${colors.border}30`, marginTop: '4px' }}>
      {/* collapse arrow head */}
      <button
        onClick={() => setCollapsed(!collapsed)}
        className="flex items-center gap-1 transition-colors hover:opacity-70"
        style={{ color: colors.textDim }}
      >
        <svg
          className={`w-3 h-3 transition-transform duration-200 ${collapsed ? 'rotate-0' : '-rotate-90'}`}
          viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round"
        >
          <polyline points="6 9 12 15 18 9" />
        </svg>
      </button>

      {/* chat title title(editable) */}
      {editing ? (
        <input
          type="text"
          value={title}
          onChange={(e) => setTitle(e.target.value)}
          onBlur={() => setEditing(false)}
          onKeyDown={(e) => { if (e.key === 'Enter') setEditing(false) }}
          autoFocus
          className="text-[11px] font-medium bg-transparent outline-none flex-1 min-w-0"
          style={{ color: colors.textSecondary, borderBottom: `1px solid ${colors.accent}` }}
        />
      ) : (
        <button
          onClick={() => setEditing(true)}
          className="text-[11px] font-medium transition-colors hover:opacity-70 truncate"
          style={{ color: colors.textSecondary }}
          title="Click to edit topic title"
        >
          {title}
        </button>
      )}

      {/* time middle gap tab */}
      <span
        className="text-[9px] px-1.5 py-0.5 rounded-full flex-shrink-0"
        style={{ backgroundColor: `${colors.accent}10`, color: colors.textDim }}
      >
        ⏱ {gapText}
      </span>

      {/* timestamp */}
      <span className="text-[9px] flex-shrink-0" style={{ color: colors.textDim }}>
        {formatTopicTime(currTimestamp)}
      </span>

      <div className="flex-1" style={{ borderTop: `1px dashed ${colors.border}20` }} />
    </div>
  )
})

/**
 * decide whether to insert a topic divider between two messages
 */
export function shouldInsertTopicDivider(
  prevMsg: { role: string; timestamp: number; content: string },
  currMsg: { role: string; timestamp: number; content: string },
): { shouldInsert: boolean; title?: string } {
  // time middle gap > 10 min → new topic
  const gapMs = currMsg.timestamp - prevMsg.timestamp
  if (gapMs > 10 * 60 * 1000) {
    // try try from current message extract title
    const content = currMsg.content?.trim() || ''
    const firstLine = content.split('\n')[0]?.slice(0, 30) || ''
    return {
      shouldInsert: true,
      title: firstLine || undefined,
    }
  }

  return { shouldInsert: false }
}
