/**
 * LocalTerminal - local PTY terminal component
 *
 * based on xterm.js + Tauri local_pty real current real local terminal.
 * and SSH terminal(TerminalPanel) side by side, by TerminalTabBar switch.
 */
import { useEffect, useRef, useCallback, useState } from 'react'
import { Terminal } from '@xterm/xterm'
import { FitAddon } from '@xterm/addon-fit'
import { WebglAddon } from '@xterm/addon-webgl'
import '@xterm/xterm/css/xterm.css'
import { useThemeStore } from '../stores/themeStore'
import { useSshAgentStore } from '../stores/sshAgentStore'
import {
  spawnLocalPty,
  writeToPty,
  resizeLocalPty,
  killLocalPty,
  onLocalPtyOutput,
  onLocalPtyExit,
} from '../api/localTerminal'
import type { UnlistenFn } from '@tauri-apps/api/event'

interface LocalTerminalProps {
  /** terminal session ID(external manage when pass in, otherwise internal generate) */
  sessionId?: string
  /** working directory */
  cwd?: string
  /** cwd change count widget: change when at already move ok session center execute cd */
  cwdNonce?: number
  /** session change callback */
  onSessionChange?: (sessionId: string | null) => void
  /** add to chat callback */
  onAddToChat?: (text: string) => void
}

/** generate unique session ID */
function generateSessionId(): string {
  return `local-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`
}

export function LocalTerminal({
  sessionId: externalSessionId,
  cwd,
  cwdNonce = 0,
  onSessionChange,
  onAddToChat,
}: LocalTerminalProps) {
  const { colors } = useThemeStore()
  const { addInputTag } = useSshAgentStore()

  const wrapperRef = useRef<HTMLDivElement>(null)
  const termRef = useRef<Terminal | null>(null)
  const fitRef = useRef<FitAddon | null>(null)
  const sessionIdRef = useRef<string>(externalSessionId || generateSessionId())
  const unlistenOutputRef = useRef<UnlistenFn | null>(null)
  const unlistenExitRef = useRef<UnlistenFn | null>(null)
  const resizeTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null)
  const lastSentSizeRef = useRef({ cols: 0, rows: 0 })

  const [exited, setExited] = useState(false)
  const [contextMenu, setContextMenu] = useState<{
    visible: boolean
    x: number
    y: number
    selectedText: string
  }>({ visible: false, x: 0, y: 0, selectedText: '' })

  /** create xterm instance */
  const createTerminal = useCallback(() => {
    const term = new Terminal({
      cursorBlink: true,
      cursorStyle: 'block',
      fontSize: 13,
      fontFamily: "'JetBrains Mono', 'Fira Code', Consolas, monospace",
      scrollback: 100000,
      theme: {
        background: colors.bgPrimary,
        foreground: colors.text,
        cursor: colors.accent,
        cursorAccent: colors.bgPrimary,
        selectionBackground: colors.accent + '50',
        selectionForeground: '#ffffff',
        black: '#000000',
        red: colors.red,
        green: colors.green,
        yellow: colors.yellow,
        blue: colors.accent,
        magenta: '#c084fc',
        cyan: '#22d3ee',
        white: colors.text,
        brightBlack: '#555555',
        brightRed: colors.red,
        brightGreen: colors.green,
        brightYellow: colors.yellow,
        brightBlue: colors.accent,
        brightMagenta: '#c084fc',
        brightCyan: '#22d3ee',
        brightWhite: '#ffffff',
      },
      rows: 24,
      cols: 120,
      allowProposedApi: false,
    })
    return term
  }, [colors])

  /** initial start terminal merge create PTY session */
  useEffect(() => {
    if (!wrapperRef.current) return

    const term = createTerminal()
    const fitAddon = new FitAddon()
    term.loadAddon(fitAddon)

    try {
      term.loadAddon(new WebglAddon())
    } catch {
      /* WebGL not ok use when demote */
    }

    const container = document.createElement('div')
    container.style.cssText =
      'position:absolute;top:0;left:0;right:0;bottom:0;padding:0 8px 25px 8px;overflow:hidden;'
    wrapperRef.current.appendChild(container)

    term.open(container)
    fitAddon.fit()

    termRef.current = term
    fitRef.current = fitAddon

    const sid = sessionIdRef.current

    // listen PTY output
    onLocalPtyOutput(sid, (data) => {
      term.write(data)
    }).then((unlisten) => {
      unlistenOutputRef.current = unlisten
    })

    // listen PTY exit
    onLocalPtyExit(sid, (exitCode) => {
      setExited(true)
      term.writeln(`\x1b[33m\r\n*** Terminal exited (exit code: ${exitCode}) ***\x1b[0m`)
    }).then((unlisten) => {
      unlistenExitRef.current = unlisten
    })

    // user input → PTY
    const onDataDisposable = term.onData((data) => {
      writeToPty(sid, data).catch(() => {
        term.writeln('\r\n\x1b[31mFailed to send input\x1b[0m')
      })
    })

    // ResizeObserver(debounced, avoid frequent complex resize cause shell repaint prompt)
    const ro = new ResizeObserver(() => {
      if (resizeTimerRef.current) return
      resizeTimerRef.current = setTimeout(() => {
        resizeTimerRef.current = null
        fitAddon.fit()
        const { cols, rows } = term
        if (cols <= 0 || rows <= 0) return
        if (cols === lastSentSizeRef.current.cols && rows === lastSentSizeRef.current.rows) return
        lastSentSizeRef.current = { cols, rows }
        resizeLocalPty(sid, cols, rows).catch(() => {})
      }, 300)
    })
    ro.observe(container)

    // delay create PTY: wait DOM layout game stable set back again spawn, avoid initial start size not ready cause extra nth SIGWINCH
    // requestAnimationFrame ensure open + fit already rendered, again delay one frame let ResizeObserver first fit done
    let rafId1: number
    let rafId2: number
    const scheduleSpawn = () => {
      rafId1 = requestAnimationFrame(() => {
        fitAddon.fit()
        rafId2 = requestAnimationFrame(() => {
          // at this point DOM stable, ruler inch ready confirm
          const { cols, rows } = term
          lastSentSizeRef.current = { cols, rows }
          spawnLocalPty({
            sessionId: sid,
            cwd,
            cols,
            rows,
          })
            .then(() => {
              onSessionChange?.(sid)
            })
            .catch((err) => {
              term.writeln(`\x1b[31mFailed to create local terminal: ${err}\x1b[0m`)
            })
        })
      })
    }
    scheduleSpawn()

    // context menu
    container.addEventListener('contextmenu', (e) => {
      e.preventDefault()
      const selection = term.getSelection()
      if (selection) {
        setContextMenu({ visible: true, x: e.clientX, y: e.clientY, selectedText: selection })
      }
    })

    container.addEventListener('mouseup', (e) => {
      setTimeout(() => {
        const selection = term.getSelection()
        if (selection && selection.trim().length > 0) {
          setContextMenu({ visible: true, x: e.clientX, y: e.clientY, selectedText: selection })
        } else if (e.button !== 2) {
          setContextMenu((prev) => ({ ...prev, visible: false }))
        }
      }, 50)
    })

    requestAnimationFrame(() => term.focus())

    // clean
    return () => {
      cancelAnimationFrame(rafId1)
      cancelAnimationFrame(rafId2)
      if (resizeTimerRef.current) clearTimeout(resizeTimerRef.current)
      onDataDisposable.dispose()
      ro.disconnect()
      unlistenOutputRef.current?.()
      unlistenExitRef.current?.()
      killLocalPty(sid).catch(() => {})
      term.dispose()
      if (container.parentNode) {
        container.remove()
      }
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  /** theme change update when terminal color */
  useEffect(() => {
    if (!termRef.current) return
    termRef.current.options.theme = {
      background: colors.bgPrimary,
      foreground: colors.text,
      cursor: colors.accent,
      cursorAccent: colors.bgPrimary,
      selectionBackground: colors.accent + '50',
      selectionForeground: '#ffffff',
      black: '#000000',
      red: colors.red,
      green: colors.green,
      yellow: colors.yellow,
      blue: colors.accent,
      magenta: '#c084fc',
      cyan: '#22d3ee',
      white: colors.text,
      brightBlack: '#555555',
      brightRed: colors.red,
      brightGreen: colors.green,
      brightYellow: colors.yellow,
      brightBlue: colors.accent,
      brightMagenta: '#c084fc',
      brightCyan: '#22d3ee',
      brightWhite: '#ffffff',
    }
  }, [colors])

  /** reset terminal */
  const handleReset = useCallback(() => {
    if (!termRef.current || !sessionIdRef.current) return
    const sid = sessionIdRef.current

    // close old session
    killLocalPty(sid).catch(() => {})

    // clear screen
    termRef.current.clear()
    termRef.current.reset()
    setExited(false)

    // create new session
    const newSid = generateSessionId()
    sessionIdRef.current = newSid

    // re- new listen
    unlistenOutputRef.current?.()
    unlistenExitRef.current?.()

    onLocalPtyOutput(newSid, (data) => {
      termRef.current?.write(data)
    }).then((unlisten) => {
      unlistenOutputRef.current = unlisten
    })

    onLocalPtyExit(newSid, (exitCode) => {
      setExited(true)
      termRef.current?.writeln(`\x1b[33m\r\n*** Terminal exited (exit code: ${exitCode}) ***\x1b[0m`)
    }).then((unlisten) => {
      unlistenExitRef.current = unlisten
    })

    spawnLocalPty({
      sessionId: newSid,
      cwd,
      cols: termRef.current.cols,
      rows: termRef.current.rows,
    })
      .then(() => {
        onSessionChange?.(newSid)
      })
      .catch((err) => {
        termRef.current?.writeln(`\x1b[31mFailed to create local terminal: ${err}\x1b[0m`)
      })
  }, [cwd, onSessionChange])

  /** " at terminal open in":nonce change when at already move ok session inside switch working directory */
  const lastCwdNonceRef = useRef(cwdNonce)
  useEffect(() => {
    if (cwdNonce === lastCwdNonceRef.current) return
    lastCwdNonceRef.current = cwdNonce
    if (!cwd) return
    if (exited) {
      // session already exit: use latest cwd restart
      handleReset()
      return
    }
    const quoted = `"${cwd.replace(/"/g, '\\"')}"`
    writeToPty(sessionIdRef.current, `cd ${quoted}\n`).catch(() => {})
    termRef.current?.focus()
  }, [cwdNonce, cwd, exited, handleReset])

  /** add to chat */
  const handleAddToChat = useCallback(() => {
    if (contextMenu.selectedText) {
      if (onAddToChat) {
        onAddToChat(contextMenu.selectedText)
      } else {
        addInputTag({
          label:
            contextMenu.selectedText.length > 20
              ? contextMenu.selectedText.slice(0, 20) + '...'
              : contextMenu.selectedText,
          fullContent: contextMenu.selectedText,
          type: 'terminal-selection',
        })
      }
    }
    setContextMenu((prev) => ({ ...prev, visible: false }))
  }, [contextMenu.selectedText, onAddToChat, addInputTag])

  /** copy */
  const handleCopy = useCallback(() => {
    if (contextMenu.selectedText) {
      navigator.clipboard.writeText(contextMenu.selectedText)
    }
    setContextMenu((prev) => ({ ...prev, visible: false }))
  }, [contextMenu.selectedText])

  /** click outside to close the context menu */
  useEffect(() => {
    if (!contextMenu.visible) return
    const handler = () => setContextMenu((prev) => ({ ...prev, visible: false }))
    document.addEventListener('click', handler)
    return () => document.removeEventListener('click', handler)
  }, [contextMenu.visible])

  return (
    <div
      className="h-full flex flex-col min-w-0 relative"
      style={{ backgroundColor: colors.bgPrimary }}
    >
      {/* toolbar */}
      <div
        className="h-9 flex items-center justify-between px-3 border-b flex-shrink-0"
        style={{ backgroundColor: colors.bgSecondary, borderColor: colors.border }}
      >
        <div className="flex items-center gap-2">
          <div
            className="w-2 h-2 rounded-full"
            style={{ backgroundColor: exited ? colors.red : colors.green }}
          />
          <span className="text-xs font-medium" style={{ color: colors.text }}>
            Local terminal
          </span>
          {exited && (
            <span
              className="text-[10px] px-1.5 py-0.5 rounded"
              style={{ backgroundColor: colors.red + '20', color: colors.red }}
            >
              Exited
            </span>
          )}
        </div>
        <div className="flex items-center gap-1">
          <button
            onClick={() => termRef.current?.clear()}
            className="p-1.5 rounded hover:bg-white/10 transition-colors"
            style={{ color: colors.textDim }}
            title="Clear"
          >
            <svg className="w-4 h-4" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
              <path d="M3 6h18M19 6v14a2 2 0 01-2 2H7a2 2 0 01-2-2V6m3 0V4a2 2 0 012-2h4a2 2 0 012 2v2" />
            </svg>
          </button>
          <button
            onClick={handleReset}
            className="p-1.5 rounded hover:bg-white/10 transition-colors"
            style={{ color: colors.textDim }}
            title="Reset terminal"
          >
            <svg className="w-4 h-4" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
              <path d="M23 4v6h-6M1 20v-6h6" />
              <path d="M3.51 9a9 9 0 0114.85-3.36L23 10M1 14l4.64 4.36A9 9 0 0020.49 15" />
            </svg>
          </button>
        </div>
      </div>

      {/* terminal content widget */}
      <div className="flex-1" style={{ position: 'relative', overflow: 'hidden' }}>
        <div ref={wrapperRef} className="absolute inset-0" style={{ overflow: 'hidden' }} />
      </div>

      {/* context menu */}
      {contextMenu.visible && (
        <div
          className="fixed z-50 rounded-lg py-1 shadow-lg border"
          style={{
            left: contextMenu.x,
            top: contextMenu.y,
            backgroundColor: colors.bgPrimary,
            borderColor: colors.border,
            minWidth: '140px',
          }}
        >
          <button
            onClick={handleAddToChat}
            className="w-full px-3 py-2 text-left text-[12px] hover:bg-black/5 flex items-center gap-2"
            style={{ color: colors.text }}
          >
            <svg className="w-3.5 h-3.5" viewBox="0 0 24 24" fill="none" stroke={colors.accent} strokeWidth="2">
              <path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z"></path>
            </svg>
            Add to chat
          </button>
          <button
            onClick={handleCopy}
            className="w-full px-3 py-2 text-left text-[12px] hover:bg-black/5 flex items-center gap-2"
            style={{ color: colors.text }}
          >
            <svg className="w-3.5 h-3.5" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
              <rect x="9" y="9" width="13" height="13" rx="2" ry="2"></rect>
              <path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"></path>
            </svg>
            Copy
          </button>
        </div>
      )}
    </div>
  )
}
