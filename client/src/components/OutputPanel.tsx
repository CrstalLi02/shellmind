/**
 * OutputPanel - IntelliJ IDEA "Run" tool-window style plain-text console
 *
 * feature:
 * - pure text each ok render, not do shape parse/ collapse, info not lose lose
 * - ANSI color support
 * - toolbar: clear, scroll lock set, auto scroll to bottom
 * - left Gutter show line number
 * - command heading line($ command)+ output area + exit code ok
 *
 * data from source:
 * 1. RightSidebar onStep callback → outputStore(main need data source,tool_result SSE event)
 * 2. Tauri Event "shell-stream" - streaming command execute output(backup)
 */
import { useEffect, useRef, useState, useCallback, useMemo } from 'react'
import { useThemeStore } from '../stores/themeStore'
import { listen, type UnlistenFn } from '@tauri-apps/api/event'
import { useOutputStore } from '../stores/outputStore'
import type { StreamEvent } from '../types/stream'

interface OutputPanelProps {
  /** whether ok see */
  visible?: boolean
}

// ─── ANSI parse ─────────────────────────────────────────────

interface AnsiSegment {
  text: string
  bold: boolean
  color?: string
  bgColor?: string
}

/** simple ANSI escape code parse widget */
function parseAnsi(text: string): AnsiSegment[] {
  const segments: AnsiSegment[] = []
  const re = /\x1b\[(\d+(?:;\d+)*)m([^\x1b]*)/g
  let lastIndex = 0
  let match: RegExpExecArray | null
  const current: AnsiSegment = { text: '', bold: false }

  while ((match = re.exec(text)) !== null) {
    if (match.index > lastIndex) {
      segments.push({ ...current, text: text.slice(lastIndex, match.index) })
    }
    const codes = match[1].split(';').map(Number)
    applySgr(codes, current)
    lastIndex = match.index + match[0].length + (match[2]?.length || 0)
    if (match[2]) {
      segments.push({ ...current, text: match[2] })
    }
  }
  if (lastIndex < text.length) {
    segments.push({ ...current, text: text.slice(lastIndex) })
  }

  return segments.filter((s) => s.text.length > 0)
}

function applySgr(codes: number[], target: AnsiSegment) {
  for (const code of codes) {
    switch (code) {
      case 0: target.bold = false; target.color = undefined; target.bgColor = undefined; break
      case 1: target.bold = true; break
      case 31: target.color = '#f87171'; break
      case 32: target.color = '#4ade80'; break
      case 33: target.color = '#facc15'; break
      case 34: target.color = '#60a5fa'; break
      case 35: target.color = '#c084fc'; break
      case 36: target.color = '#22d3ee'; break
      case 37: target.color = '#e5e7eb'; break
      case 90: target.color = '#6b7280'; break
      case 91: target.color = '#ef4444'; break
      case 92: target.color = '#22c55e'; break
      case 93: target.color = '#eab308'; break
      case 94: target.color = '#3b82f6'; break
      case 95: target.color = '#a855f7'; break
      case 96: target.color = '#06b6d4'; break
      case 97: target.color = '#f3f4f6'; break
    }
  }
}

// ─── main component ──────────────────────────────────────────────────

export function OutputPanel({ visible = true }: OutputPanelProps) {
  const { colors } = useThemeStore()
  const { entries, updateEntry, clearEntries } = useOutputStore()
  const scrollRef = useRef<HTMLDivElement>(null)
  const [scrollLocked, setScrollLocked] = useState(false)
  const unlistenStreamRef = useRef<UnlistenFn | null>(null)

  /** listen shell-stream event(backup data source) */
  useEffect(() => {
    listen<StreamEvent>('shell-stream', (event) => {
      const payload = event.payload
      const entry = entries.find((e) => e.sessionId === payload.session_id)

      if (payload.kind === 'stdout') {
        if (entry) {
          updateEntry(payload.session_id, {
            stdout: (entry.stdout || '') + payload.data,
            status: 'running',
          })
        }
      } else if (payload.kind === 'stderr') {
        if (entry) {
          updateEntry(payload.session_id, {
            stderr: (entry.stderr || '') + payload.data,
            status: 'running',
          })
        }
      } else if (payload.kind === 'done') {
        if (entry) {
          updateEntry(payload.session_id, {
            status: payload.exit_code === 0 ? 'success' : 'failed',
            exitCode: payload.exit_code ?? -1,
            durationMs: payload.duration_ms ?? 0,
          })
        }
      } else if (payload.kind === 'error') {
        if (entry) {
          updateEntry(payload.session_id, {
            status: 'failed',
            stderr: (entry.stderr || '') + payload.data,
          })
        }
      }
    }).then((unlisten) => {
      unlistenStreamRef.current = unlisten
    })

    return () => {
      unlistenStreamRef.current?.()
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [entries])

  /** auto scroll to bottom(remove non lock set) */
  useEffect(() => {
    if (!scrollLocked && scrollRef.current) {
      scrollRef.current.scrollTop = scrollRef.current.scrollHeight
    }
  }, [entries, scrollLocked])

  /** detect user manual scroll to top → auto lock set */
  const handleScroll = useCallback(() => {
    if (!scrollRef.current) return
    const { scrollTop, scrollHeight, clientHeight } = scrollRef.current
    // distance away bottom exceed 40px view as lock set
    setScrollLocked(scrollHeight - scrollTop - clientHeight > 40)
  }, [])

  /** merge all entries into plain text, generate consecutive control make host output */
  const consoleLines = useMemo(() => {
    const lines: { text: string; isCommand: boolean; isStatus: boolean; entryId: string }[] = []
    for (const entry of entries) {
      // command line
      lines.push({ text: `$ ${entry.command}`, isCommand: true, isStatus: false, entryId: entry.id })
      // stdout
      if (entry.stdout) {
        for (const line of entry.stdout.split('\n')) {
          lines.push({ text: line, isCommand: false, isStatus: false, entryId: entry.id })
        }
      }
      // stderr
      if (entry.stderr) {
        for (const line of entry.stderr.split('\n')) {
          lines.push({ text: line, isCommand: false, isStatus: false, entryId: entry.id })
        }
      }
      // end status line
      if (entry.status === 'success' || entry.status === 'failed') {
        const exitInfo = entry.exitCode !== null ? ` (exit ${entry.exitCode})` : ''
        const duration = entry.durationMs !== null ? ` ${entry.durationMs}ms` : ''
        lines.push({
          text: entry.status === 'success'
            ? `✓ Process finished${exitInfo}${duration}`
            : `✕ Process finished${exitInfo}${duration}`,
          isCommand: false,
          isStatus: true,
          entryId: entry.id,
        })
        // blank line separator not same command
        lines.push({ text: '', isCommand: false, isStatus: false, entryId: entry.id })
      } else if (entry.status === 'running') {
        lines.push({
          text: '⏳ Running...',
          isCommand: false,
          isStatus: true,
          entryId: entry.id,
        })
      }
    }
    return lines
  }, [entries])

  if (!visible) return null

  return (
    <div
      className="h-full flex flex-col min-w-0"
      style={{ backgroundColor: colors.bgPrimary }}
    >
      {/* IntelliJ style toolbar */}
      <div
        className="h-7 flex items-center justify-between px-2 border-b flex-shrink-0 select-none"
        style={{ backgroundColor: colors.bgSecondary, borderColor: colors.border }}
      >
        <div className="flex items-center gap-1.5">
          <svg className="w-3.5 h-3.5" viewBox="0 0 16 16" fill="none" stroke={colors.textDim} strokeWidth="1.5">
            <rect x="1" y="2" width="14" height="12" rx="1" />
            <polyline points="4 6 7 9 4 12" />
            <line x1="8" y1="12" x2="12" y2="12" />
          </svg>
          <span className="text-[11px] font-medium" style={{ color: colors.text }}>
            Output
          </span>
          {entries.length > 0 && (
            <span className="text-[10px] px-1 rounded" style={{ backgroundColor: colors.bgTertiary, color: colors.textDim }}>
              {entries.length}
            </span>
          )}
        </div>
        <div className="flex items-center gap-0.5">
          {/* scroll lock set button */}
          <button
            onClick={() => setScrollLocked(!scrollLocked)}
            className="p-1 rounded hover:bg-white/10 transition-colors"
            style={{ color: scrollLocked ? colors.accent : colors.textDim }}
            title={scrollLocked ? 'Unlock auto-scroll' : 'Lock auto-scroll'}
          >
            <svg className="w-3.5 h-3.5" viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.5">
              {scrollLocked ? (
                <>
                  <line x1="2" y1="2" x2="14" y2="14" />
                  <path d="M6 3h7v7" />
                </>
              ) : (
                <path d="M3 13h7V6M10 3H3v7" />
              )}
            </svg>
          </button>
          {/* clear button */}
          {entries.length > 0 && (
            <button
              onClick={clearEntries}
              className="p-1 rounded hover:bg-white/10 transition-colors"
              style={{ color: colors.textDim }}
              title="Clear all output"
            >
              <svg className="w-3.5 h-3.5" viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.5">
                <circle cx="8" cy="8" r="6" />
                <line x1="5.5" y1="5.5" x2="10.5" y2="10.5" />
              </svg>
            </button>
          )}
        </div>
      </div>

      {/* control make host output area */}
      <div
        ref={scrollRef}
        onScroll={handleScroll}
        className="flex-1 overflow-y-auto overflow-x-auto font-mono text-[12px] leading-[1.5]"
        style={{ backgroundColor: colors.bgPrimary }}
      >
        {consoleLines.length === 0 ? (
          <EmptyState colors={colors} />
        ) : (
          <div className="py-1">
            {consoleLines.map((line, idx) => (
              <ConsoleLine
                key={`${line.entryId}-${idx}`}
                line={line}
                lineNum={idx + 1}
                colors={colors}
              />
            ))}
          </div>
        )}
      </div>
    </div>
  )
}

// ─── empty state ──────────────────────────────────────────────────

function EmptyState({ colors }: { colors: ReturnType<typeof useThemeStore.getState>['colors'] }) {
  return (
    <div className="h-full flex flex-col items-center justify-center gap-2 opacity-30">
      <svg className="w-8 h-8" viewBox="0 0 24 24" fill="none" stroke={colors.textDim} strokeWidth="1.5">
        <polyline points="4 17 10 11 4 5" />
        <line x1="12" y1="19" x2="20" y2="19" />
      </svg>
      <p className="text-[11px]" style={{ color: colors.textDim }}>
        Commands the AI runs will show up here
      </p>
    </div>
  )
}

// ─── form ok control make host output ──────────────────────────────────────────

function ConsoleLine({
  line,
  lineNum,
  colors,
}: {
  line: { text: string; isCommand: boolean; isStatus: boolean; entryId: string }
  lineNum: number
  colors: ReturnType<typeof useThemeStore.getState>['colors']
}) {
  if (line.text === '' && !line.isCommand && !line.isStatus) {
    return <div className="h-[18px]" />
  }

  // command line: blue high bright
  if (line.isCommand) {
    return (
      <div className="flex hover:bg-white/[0.03]">
        <span
          className="w-10 flex-shrink-0 text-right pr-2 select-none text-[10px] leading-[18px]"
          style={{ color: colors.textDim + '60' }}
        >
          {lineNum}
        </span>
        <span className="whitespace-pre" style={{ color: colors.accent, fontWeight: 500 }}>
          {line.text}
        </span>
      </div>
    )
  }

  // status line
  if (line.isStatus) {
    const isSuccess = line.text.startsWith('✓')
    return (
      <div className="flex hover:bg-white/[0.03]">
        <span
          className="w-10 flex-shrink-0 text-right pr-2 select-none text-[10px] leading-[18px]"
          style={{ color: colors.textDim + '60' }}
        >
          {lineNum}
        </span>
        <span
          className="whitespace-pre"
          style={{ color: isSuccess ? colors.green : colors.red, fontWeight: 500 }}
        >
          {line.text}
        </span>
      </div>
    )
  }

  // general through output ok:ANSI parse render
  return (
    <div className="flex hover:bg-white/[0.03]">
      <span
        className="w-10 flex-shrink-0 text-right pr-2 select-none text-[10px] leading-[18px]"
        style={{ color: colors.textDim + '60' }}
      >
        {lineNum}
      </span>
      <span className="whitespace-pre">
        <AnsiText text={line.text} colors={colors} />
      </span>
    </div>
  )
}

// ─── ANSI text render component ──────────────────────────────────────

function AnsiText({ text, colors }: { text: string; colors: ReturnType<typeof useThemeStore.getState>['colors'] }) {
  const segments = useMemo(() => parseAnsi(text), [text])

  if (segments.length === 0) {
    return <span style={{ color: colors.text }}>{text}</span>
  }

  return (
    <>
      {segments.map((seg, i) => (
        <span
          key={i}
          style={{
            fontWeight: seg.bold ? 700 : 400,
            color: seg.color || colors.text,
            backgroundColor: seg.bgColor,
          }}
        >
          {seg.text}
        </span>
      ))}
    </>
  )
}
