import { useEffect, useLayoutEffect, useRef, useCallback, useState } from 'react'
import { Terminal } from '@xterm/xterm'
import { FitAddon } from '@xterm/addon-fit'
import { WebglAddon } from '@xterm/addon-webgl'
import '@xterm/xterm/css/xterm.css'
import { useThemeStore } from '../stores/themeStore'
import { useConnectionStore } from '../stores/connectionStore'
import { useSshAgentStore } from '../stores/sshAgentStore'
import { openTerminal, writeInput, readOutput, resizeTerminal, closeTerminal } from '../api/terminal'
import { ConnectionStatus } from '../types'
import { COMMAND_CATEGORIES, type CommandCategory, type CommandItem } from './CommandData/commandData'

/** terminal session status */
interface TerminalSession {
  sessionId: string
  connectionId: string
}

/** each connection hold has terminal status */
interface ConnectionTerminalState {
  terminal: Terminal
  fitAddon: FitAddon
  container: HTMLDivElement
  session: TerminalSession | null
  pollTimer: ReturnType<typeof setInterval> | null
  onDataDisposable: { dispose(): void } | null
  disconnected: boolean
  connecting: boolean
  resizeObserver: ResizeObserver | null
  lastSentSize: { cols: number; rows: number }
  inputBuffer: string[] | null
  inputFlushTimer: ReturnType<typeof setTimeout> | null
}

/** global terminal session storage - keep hold across component session */
const globalTerminalStates = new Map<string, ConnectionTerminalState>()

/** polling middle gap(ms) */
const POLL_INTERVAL = 50

/** backend return break connect mark */
const DISCONNECT_MARKER = '[Disconnected]'

/** polling consecutive error threshold */
const POLL_ERROR_THRESHOLD = 3

/** context menu position place */
interface ContextMenuPos {
  x: number
  y: number
}

interface TerminalPanelProps {
  onTerminalSessionChange?: (sessionId: string | null) => void
  /** whether to keep the terminal session on unmount(default true, real current switch tab keep hold connection) */
  keepSessionOnUnmount?: boolean
}

export function TerminalPanel({ 
  onTerminalSessionChange, 
  keepSessionOnUnmount = true 
}: TerminalPanelProps) {
  const { colors } = useThemeStore()
  const { currentConnectionId, connections, connect, disconnect } = useConnectionStore()
  const { addInputTag, registerConnectionSession, unregisterConnectionSession } = useSshAgentStore()

  // use global terminal state instead of component-local state
  const terminalStates = useRef<Map<string, ConnectionTerminalState>>(globalTerminalStates)
  const wrapperRef = useRef<HTMLDivElement>(null)
  const activeConnectionIdRef = useRef<string | null>(null)

  // context menu status
  const [contextMenu, setContextMenu] = useState<{
    visible: boolean
    pos: ContextMenuPos
    selectedText: string
  }>({ visible: false, pos: { x: 0, y: 0 }, selectedText: '' })

  // command assist help sidebar status
  const [showCommandSidebar, setShowCommandSidebar] = useState(false)
  const [expandedCategory, setExpandedCategory] = useState<string | null>(null)

  // current connection status
  const currentConn = connections.find((c) => c.id === currentConnectionId)

  /** get current terminal session ID */
  const getCurrentTerminalSessionId = useCallback(() => {
    if (!currentConnectionId) return null
    const state = terminalStates.current.get(currentConnectionId)
    return state?.session?.sessionId || null
  }, [currentConnectionId])

  /** through know parent component terminal session change */
  useEffect(() => {
    const sessionId = getCurrentTerminalSessionId()
    onTerminalSessionChange?.(sessionId)
  }, [currentConnectionId, getCurrentTerminalSessionId, onTerminalSessionChange])

  /** create xterm Terminal instance */
  const createTerminalInstance = useCallback(() => {
    return new Terminal({
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
  }, [colors])

  /** stop point set connection polling */
  const stopPolling = useCallback((state: ConnectionTerminalState) => {
    if (state.pollTimer) {
      clearInterval(state.pollTimer)
      state.pollTimer = null
    }
  }, [])

  /** mark terminal disconnect merge clean */
  const markDisconnected = useCallback(async (state: ConnectionTerminalState, reason: string) => {
    if (state.disconnected) return
    state.disconnected = true
    stopPolling(state)

    const conn = connections.find((c) => c.id === state.session!.connectionId)
    if (conn && conn.status === ConnectionStatus.CONNECTED) {
      try {
        await disconnect(conn.id)
      } catch {
        // ignore disconnect connection error
      }
    }

    state.terminal.writeln(`\x1b[33m\r\n*** ${reason} ***\x1b[0m`)
  }, [stopPolling, connections, disconnect])

  /** start polling */
  const startPolling = useCallback((state: ConnectionTerminalState) => {
    stopPolling(state)
    const sessionId = state.session!.sessionId
    let errorCount = 0

    state.pollTimer = setInterval(async () => {
      try {
        const res = await readOutput(sessionId)

        if (res.code === '0000') {
          errorCount = 0
          if (res.data?.output) {
            const output = res.data.output
            if (output.includes(DISCONNECT_MARKER)) {
              markDisconnected(state, 'Disconnected')
              return
            }
            state.terminal.write(output)
          }
          return
        }

        if (res.code === 'ILLEGAL_PARAMETER' && / missing|not found|不存在/i.test(res.info ?? '')) {
          markDisconnected(state, 'Session expired')
          return
        }

        errorCount++
        if (errorCount >= POLL_ERROR_THRESHOLD) {
          markDisconnected(state, 'Connection error')
        }
      } catch {
        errorCount++
        if (errorCount >= POLL_ERROR_THRESHOLD) {
          markDisconnected(state, 'Network error')
        }
      }
    }, POLL_INTERVAL)
  }, [stopPolling, markDisconnected])

  /** pin destroy point set connection terminal */
  const destroyTerminal = useCallback((connectionId: string) => {
    const state = terminalStates.current.get(connectionId)
    if (!state) return

    stopPolling(state)

    if (state.inputFlushTimer) {
      clearTimeout(state.inputFlushTimer)
    }

    if (state.session) {
      closeTerminal(state.session.sessionId).catch(() => {})
      // remove connectionId → terminalSessionId map
      unregisterConnectionSession(connectionId)
    }

    state.onDataDisposable?.dispose()
    state.resizeObserver?.disconnect()
    state.terminal.dispose()
    if (state.container.parentNode) {
      state.container.remove()
    }

    terminalStates.current.delete(connectionId)
  }, [stopPolling])

  /** create merge open terminal session */
  const openTerminalSession = useCallback(async (connectionId: string) => {
    // check whether a session already exists
    const existingState = terminalStates.current.get(connectionId)
    if (existingState) {
      if (existingState.connecting) return
      // each time all strong make pin destroy old session, avoid use lose effect session
      destroyTerminal(connectionId)
    }

    if (!wrapperRef.current) return

    const term = createTerminalInstance()
    const fitAddon = new FitAddon()
    term.loadAddon(fitAddon)

    try {
      term.loadAddon(new WebglAddon())
    } catch {
      /* WebGL not ok use when demote */
    }

    const container = document.createElement('div')
    container.style.cssText = 'position:absolute;top:0;left:0;right:0;bottom:0;padding:0 8px 25px 8px;overflow:hidden;'
    wrapperRef.current.appendChild(container)

    // listen selected event
    term.onSelectionChange(() => {
      const selection = term.getSelection()
      if (selection) {
        // use timeout wait mouse event done, get align confirm position place
        setTimeout(() => {
          // this inside we no method directly get to mouse position place, so show at a fixed position place or via other way
          // but due to we want at selected back directly popup, we need need at xterm content widget up listen mouseup
        }, 10)
      }
    })

    // add context menu and selected event
    container.addEventListener('contextmenu', (e) => {
      e.preventDefault()
      const selection = term.getSelection()
      if (selection) {
        setContextMenu({
          visible: true,
          pos: { x: e.clientX, y: e.clientY },
          selectedText: selection,
        })
      }
    })

    // mouse raise up when, if has selected content, directly popup menu
    container.addEventListener('mouseup', (e) => {
      // delay one down ensure xterm selection already via update
      setTimeout(() => {
        const selection = term.getSelection()
        if (selection && selection.trim().length > 0) {
          setContextMenu({
            visible: true,
            pos: { x: e.clientX, y: e.clientY },
            selectedText: selection,
          })
        } else {
          // if no has selected content, and not is right-click click, close menu
          if (e.button !== 2) {
            setContextMenu((prev) => ({ ...prev, visible: false }))
          }
        }
      }, 50)
    })

    term.open(container)
    fitAddon.fit()

    const state: ConnectionTerminalState = {
      terminal: term,
      fitAddon,
      container,
      session: null,
      pollTimer: null,
      onDataDisposable: null,
      disconnected: false,
      connecting: true,
      resizeObserver: null,
      lastSentSize: { cols: term.cols, rows: term.rows },
      inputBuffer: null,
      inputFlushTimer: null,
    }
    terminalStates.current.set(connectionId, state)

    let resizeTimer: ReturnType<typeof setTimeout> | null = null
    const ro = new ResizeObserver(() => {
      if (resizeTimer) return
      resizeTimer = setTimeout(() => {
        resizeTimer = null
        if (!state.session) return
        fitAddon.fit()
        if (term.cols > 0 && term.rows > 0) {
          const { cols, rows } = term
          if (cols === state.lastSentSize.cols && rows === state.lastSentSize.rows) return
          state.lastSentSize = { cols, rows }
          resizeTerminal({ sessionId: state.session.sessionId, cols, rows }).catch(() => {})
        }
      }, 300)
    })
    ro.observe(container)
    state.resizeObserver = ro

    try {
      const res = await openTerminal({
        connectionId,
        cols: term.cols,
        rows: term.rows,
      })

      if (res.code !== '0000' || !res.data) {
        term.writeln(`\x1b[31mFailed to open terminal: ${res.info}\x1b[0m`)
        state.connecting = false
        return
      }

      const { sessionId, initialOutput } = res.data!
      state.session = { sessionId, connectionId }

      // register connectionId → terminalSessionId map to global Store
      registerConnectionSession(connectionId, sessionId)

      // through know parent component session already establish
      if (connectionId === currentConnectionId) {
        onTerminalSessionChange?.(sessionId)
      }

      if (initialOutput) {
        term.write(initialOutput)
      }

      state.onDataDisposable = term.onData((data) => {
        if (state.disconnected || !state.session) return

        if (!state.inputBuffer) {
          state.inputBuffer = []
          state.inputFlushTimer = setTimeout(() => {
            if (state.inputBuffer && state.session && !state.disconnected) {
              const input = state.inputBuffer.join('')
              writeInput({ sessionId: state.session.sessionId, input }).catch(() => {
                term.writeln('\r\n\x1b[31mFailed to send input\x1b[0m')
              })
            }
            state.inputBuffer = null
            state.inputFlushTimer = null
          }, 10)
        }
        state.inputBuffer.push(data)
      })

      startPolling(state)
    } catch (err: any) {
      term.writeln(`\x1b[31mConnection error: ${err.message || 'Unknown error'}\x1b[0m`)
    } finally {
      state.connecting = false
    }
  }, [createTerminalInstance, destroyTerminal, startPolling, currentConnectionId, onTerminalSessionChange])

  /** switch terminal show */
  useEffect(() => {
    activeConnectionIdRef.current = currentConnectionId
    
    // first ensure all terminal content widget from DOM remove
    terminalStates.current.forEach((state) => {
      if (state.container.parentNode) {
        state.container.parentNode.removeChild(state.container)
      }
    })

    // then add current connection terminal to DOM
    if (currentConnectionId && wrapperRef.current) {
      const currentState = terminalStates.current.get(currentConnectionId)
      if (currentState) {
        wrapperRef.current.appendChild(currentState.container)
        currentState.container.style.visibility = 'visible'
        currentState.container.style.zIndex = '1'
        requestAnimationFrame(() => {
          currentState.terminal.focus()
        })
      }
    }

    // notify the parent of the current session when switching connections ID
    const activeState = currentConnectionId ? terminalStates.current.get(currentConnectionId) : undefined
    onTerminalSessionChange?.(activeState?.session?.sessionId ?? null)
  }, [currentConnectionId, onTerminalSessionChange])

  /** listen connection status change */
  useEffect(() => {
    if (!currentConn) return

    const isConnected = currentConn.status === ConnectionStatus.CONNECTED
    const state = terminalStates.current.get(currentConn.id)

    if (isConnected) {
      if (!state) {
        openTerminalSession(currentConn.id)
      } else if (state.disconnected) {
        destroyTerminal(currentConn.id)
        openTerminalSession(currentConn.id)
      }
    } else {
      // current SSH connection disconnect when, total is clean terminal session
      if (state) {
        destroyTerminal(currentConn.id)
      }
    }
  }, [currentConn, openTerminalSession, destroyTerminal])

  /**
   * restore existing terminal sessions on mount
   * 
   * scenario:MainView center servers/files tab each has a TerminalPanel instance.
   * from servers switch to files when,servers TerminalPanel unmount merge terminal container
   * from DOM remove(but session keep at globalTerminalStates).files TerminalPanel on mount,
   * currentConnectionId unchanged, so openTerminalSession not will re- new call.
   * this useEffect ensure on mount already has session attach to current wrapperRef.
   */
  useLayoutEffect(() => {
    if (!wrapperRef.current || !currentConnectionId) return

    const state = terminalStates.current.get(currentConnectionId)
    if (!state || !state.session) return

    // session already save at, only restore DOM mount and polling
    if (!state.container.parentNode) {
      wrapperRef.current.appendChild(state.container)
      state.container.style.visibility = 'visible'
      state.container.style.zIndex = '1'
      startPolling(state)
    }

    requestAnimationFrame(() => {
      state.terminal.focus()
    })
  }, [currentConnectionId, startPolling])

  /** component unmount clean - keep the session based on config */
  useEffect(() => {
    return () => {
      if (!keepSessionOnUnmount) {
        // complete all clean mode - pin destroy all session
        terminalStates.current.forEach((_, connId) => {
          destroyTerminal(connId)
        })
      } else {
        // keep session mode - only stop polling and from DOM remove, not close backend session
        terminalStates.current.forEach((state) => {
          stopPolling(state)
          if (state.container.parentNode) {
            state.container.parentNode.removeChild(state.container)
          }
        })
      }
    }
  }, [destroyTerminal, stopPolling, keepSessionOnUnmount])

  /** re- new connection */
  const handleReconnect = useCallback((connectionId: string) => {
    destroyTerminal(connectionId)
    openTerminalSession(connectionId)
  }, [destroyTerminal, openTerminalSession])

  /** handle add to chat */
  const handleAddToChat = useCallback(() => {
    if (contextMenu.selectedText) {
      addInputTag({
        label: contextMenu.selectedText.length > 20 ? contextMenu.selectedText.slice(0, 20) + '...' : contextMenu.selectedText,
        fullContent: contextMenu.selectedText,
        type: 'terminal-selection'
      })
    }
    setContextMenu((prev) => ({ ...prev, visible: false }))
  }, [contextMenu.selectedText, addInputTag])

  /** handle copy */
  const handleCopy = useCallback(() => {
    if (contextMenu.selectedText) {
      navigator.clipboard.writeText(contextMenu.selectedText)
    }
    setContextMenu((prev) => ({ ...prev, visible: false }))
  }, [contextMenu.selectedText])

  /** click elsewhere to close the context menu */
  useEffect(() => {
    const handleClickOutside = () => {
      setContextMenu((prev) => ({ ...prev, visible: false }))
    }
    if (contextMenu.visible) {
      document.addEventListener('click', handleClickOutside)
      return () => document.removeEventListener('click', handleClickOutside)
    }
  }, [contextMenu.visible])

  const currentState = currentConn ? terminalStates.current.get(currentConn.id) : undefined

  if (!currentConn) {
    return (
      <div className="flex-1 flex flex-col items-center justify-center min-w-0" style={{ backgroundColor: colors.bgPrimary }}>
        <div className="text-center">
          <div className="text-6xl mb-4">🖥️</div>
          <h2 className="text-lg font-medium mb-2" style={{ color: colors.text }}>
            Not connected to an SSH server
          </h2>
          <p className="text-sm mb-6" style={{ color: colors.textDim }}>
            Select or add an SSH connection on the left
          </p>
        </div>
      </div>
    )
  }

  const isConnected = currentConn.status === ConnectionStatus.CONNECTED

  return (
    <div className="h-full flex flex-col min-w-0 relative" style={{ backgroundColor: colors.bgPrimary }}>
      {/* terminal toolbar */}
      <div className="h-9 flex items-center justify-between px-3 border-b flex-shrink-0" style={{ backgroundColor: colors.bgSecondary, borderColor: colors.border }}>
        <div className="flex items-center gap-2">
          <div className="w-2 h-2 rounded-full" style={{ backgroundColor: isConnected ? colors.green : colors.yellow }} />
          <span className="text-xs font-medium" style={{ color: colors.text }}>
            {currentConn.name}
          </span>
          <span className="text-[10px] font-mono" style={{ color: colors.textDim }}>
            {currentConn.username}@{currentConn.host}:{currentConn.port}
          </span>
          {currentState?.disconnected && (
            <span className="text-[10px] px-1.5 py-0.5 rounded" style={{ backgroundColor: colors.red + '20', color: colors.red }}>
              Disconnected
            </span>
          )}
        </div>
        <div className="flex items-center gap-1">
          {currentState?.disconnected ? (
            <button
              onClick={() => handleReconnect(currentConn.id)}
              className="px-2 py-1 rounded text-[11px] font-medium transition-colors"
              style={{ backgroundColor: colors.green, color: '#ffffff' }}
            >
              Reconnect
            </button>
          ) : (
            <>
              <button
                onClick={() => currentState?.terminal.clear()}
                className="p-1.5 rounded hover:bg-white/10 transition-colors"
                style={{ color: colors.textDim }}
                title="Clear"
              >
                <svg className="w-4 h-4" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                  <path d="M3 6h18M19 6v14a2 2 0 01-2 2H7a2 2 0 01-2-2V6m3 0V4a2 2 0 012-2h4a2 2 0 012 2v2" />
                </svg>
              </button>
              <button
                onClick={async () => {
                  // disconnect first SSH connection
                  await disconnect(currentConn.id)
                  // then clean terminal session
                  destroyTerminal(currentConn.id)
                }}
                className="p-1.5 rounded hover:bg-white/10 transition-colors"
                style={{ color: colors.textDim }}
                title="Disconnect"
              >
                <svg className="w-4 h-4" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                  <path d="M9 21H5a2 2 0 01-2-2V5a2 2 0 012-2h4" />
                  <polyline points="16 17 21 12 16 7" />
                  <line x1="21" y1="12" x2="9" y2="12" />
                </svg>
              </button>
              {/* command assist help button */}
              <button
                onClick={() => setShowCommandSidebar(!showCommandSidebar)}
                className="p-1.5 rounded hover:bg-white/10 transition-colors"
                style={{ color: showCommandSidebar ? colors.accent : colors.textDim }}
                title="Command helper"
              >
                <svg className="w-4 h-4" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                  <path d="M4 17l6-6-6-6M12 19h8" />
                </svg>
              </button>
            </>
          )}
        </div>
      </div>

      {/* command assist help sidebar */}
      {showCommandSidebar && (
        <CommandSidebar
          categories={COMMAND_CATEGORIES}
          expandedCategory={expandedCategory}
          onToggleCategory={(name: string | null) => setExpandedCategory(name)}
          onSelectCommand={(cmd) => {
            // paste the command into the terminal input line, let user can so edit back again execute
            if (!currentConnectionId) return
            const state = terminalStates.current.get(currentConnectionId)
            if (state?.terminal) {
              // if not is root user, auto add sudo(row remove already via has sudo command and one some not need need sudo command)
              let finalCmd = cmd
              if (currentConn && currentConn.username !== 'root') {
                const noSudoCommands = ['cd', 'pwd', 'ls', 'echo', 'cat', 'exit', 'clear', 'history']
                const cmdFirstWord = cmd.trim().split(' ')[0]
                if (!cmd.startsWith('sudo') && !noSudoCommands.includes(cmdFirstWord)) {
                  finalCmd = `sudo ${cmd}`
                }
              }
              // use terminal.paste() method text paste to terminal
              state.terminal.paste(finalCmd)
              // aggregate focus to terminal
              state.terminal.focus()
            }
          }}
          colors={colors}
          isRoot={currentConn?.username === 'root'}
        />
      )}

      {/* terminal content widget */}
      <div className="flex-1 overflow-hidden" style={{ position: 'relative' }}>
        <div ref={wrapperRef} className="absolute inset-0" />

        {/* non CONNECTED status: overlay */}
        {!isConnected && (
          <div className="absolute inset-0 z-10 flex flex-col items-center justify-center" style={{ backgroundColor: colors.bgPrimary }}>
            {currentConn.status === ConnectionStatus.CONNECTING && (
              <div className="text-center">
                <div className="animate-spin text-4xl mb-4" style={{ color: colors.accent }}>⚙️</div>
                <p style={{ color: colors.text }}>Connecting {currentConn.name}...</p>
              </div>
            )}
            {currentConn.status === ConnectionStatus.FAILED && (
              <div className="text-center">
                <div className="text-6xl mb-4">❌</div>
                <h2 className="text-lg font-medium mb-2" style={{ color: colors.red }}>Connection failed</h2>
                <p className="text-sm mb-6" style={{ color: colors.textDim }}>
                  {currentConn.name} could not connect
                </p>
                <button
                  onClick={() => connect(currentConn.id)}
                  className="px-4 py-2 rounded-lg text-sm font-medium transition-colors"
                  style={{ backgroundColor: colors.accent, color: '#ffffff' }}
                >
                  Retry connection
                </button>
              </div>
            )}
            {currentConn.status === ConnectionStatus.DISCONNECTED && (
              <div className="text-center">
                <div className="text-6xl mb-4">🔌</div>
                <h2 className="text-lg font-medium mb-2" style={{ color: colors.text }}>
                  {currentConn.name}
                </h2>
                <p className="text-sm mb-2" style={{ color: colors.textDim }}>
                  {currentConn.username}@{currentConn.host}:{currentConn.port}
                </p>
                <p className="text-sm mb-6" style={{ color: colors.textDim }}>
                  Click below to open an SSH connection
                </p>
                <button
                  onClick={() => connect(currentConn.id)}
                  className="px-4 py-2 rounded-lg text-sm font-medium transition-colors"
                  style={{ backgroundColor: colors.accent, color: '#ffffff' }}
                >
                  Connect to server
                </button>
              </div>
            )}
          </div>
        )}
      </div>

      {/* break connect hint */}
      {currentState?.disconnected && (
        <div className="p-3 border-t flex items-center justify-between" style={{ backgroundColor: colors.bgSecondary, borderColor: colors.border }}>
          <span className="text-xs" style={{ color: colors.red }}>
            ⚠️ Terminal disconnected
          </span>
          <button
            onClick={() => handleReconnect(currentConn.id)}
            className="px-3 py-1 rounded text-xs font-medium transition-colors"
            style={{ backgroundColor: colors.accent, color: '#ffffff' }}
          >
            Reconnect
          </button>
        </div>
      )}

      {/* context menu */}
      {contextMenu.visible && (
        <div
          className="fixed z-50 rounded-lg py-1 shadow-lg border"
          style={{
            left: contextMenu.pos.x,
            top: contextMenu.pos.y,
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

/** command assist help sidebar component */
function CommandSidebar({
  categories,
  expandedCategory,
  onToggleCategory,
  onSelectCommand,
  colors,
  isRoot,
}: {
  categories: CommandCategory[]
  expandedCategory: string | null
  onToggleCategory: (name: string | null) => void
  onSelectCommand: (cmd: string) => void
  colors: ReturnType<typeof useThemeStore.getState>['colors']
  isRoot: boolean
}) {
  const handleCopyCommand = (e: React.MouseEvent, cmd: string) => {
    e.stopPropagation()
    let finalCmd = cmd
    if (!isRoot) {
      const noSudoCommands = ['cd', 'pwd', 'ls', 'echo', 'cat', 'exit', 'clear', 'history']
      const cmdFirstWord = cmd.trim().split(' ')[0]
      if (!cmd.startsWith('sudo') && !noSudoCommands.includes(cmdFirstWord)) {
        finalCmd = `sudo ${cmd}`
      }
    }
    navigator.clipboard.writeText(finalCmd)
  }

  const needsSudo = (cmd: string) => {
    if (isRoot || cmd.startsWith('sudo')) return false
    const noSudoCommands = ['cd', 'pwd', 'ls', 'echo', 'cat', 'exit', 'clear', 'history']
    const cmdFirstWord = cmd.trim().split(' ')[0]
    return !noSudoCommands.includes(cmdFirstWord)
  }

  return (
    <div
      className="absolute right-0 top-9 bottom-0 w-80 border-l overflow-hidden flex flex-col z-20"
      style={{ backgroundColor: colors.bgPrimary, borderColor: colors.border }}
    >
      <div className="p-3 border-b flex items-center justify-between" style={{ borderColor: colors.border }}>
        <span className="text-xs font-medium" style={{ color: colors.text }}>Command helper</span>
        {!isRoot && (
          <span className="text-[10px] px-2 py-0.5 rounded" style={{ backgroundColor: colors.accent + '20', color: colors.accent }}>
            Not root; sudo will be prepended
          </span>
        )}
      </div>
      <div className="flex-1 overflow-y-auto p-3">
        {categories.map((category) => (
          <div key={category.name} className="mb-4">
            <button
              onClick={() => onToggleCategory(expandedCategory === category.name ? null : category.name)}
              className="w-full flex items-center justify-between px-3 py-2 rounded text-left mb-2"
              style={{ backgroundColor: colors.bgSecondary, color: colors.text }}
            >
              <span className="text-xs flex items-center gap-1.5">
                <span>{category.emoji}</span>
                <span className="font-medium">{category.name}</span>
                <span className="text-[10px]" style={{ color: colors.textDim }}>
                  ({category.commands.length})
                </span>
              </span>
              <span style={{ color: colors.textDim }}>
                {expandedCategory === category.name ? '▼' : '▶'}
              </span>
            </button>
            {expandedCategory === category.name && (
              <div className="space-y-2">
                {category.commands.map((cmdItem: CommandItem, index: number) => (
                  <div key={`${category.name}-${index}`} className="group">
                    <div 
                      className="flex items-center gap-2 px-3 py-3 rounded-lg cursor-pointer border hover:opacity-90 transition-all"
                      style={{ backgroundColor: colors.bgPrimary, borderColor: colors.border }}
                      onClick={() => onSelectCommand(cmdItem.command)}
                    >
                      {/* line number */}
                      <div className="flex-shrink-0 w-6 h-6 flex items-center justify-center rounded-full text-[10px] font-mono" style={{ backgroundColor: colors.bgSecondary, color: colors.textDim }}>
                        {index + 1}
                      </div>
                      
                      {/* command content area */}
                      <div className="flex-1 min-w-0">
                        <div className="text-[11px] font-mono flex items-center gap-1.5 mb-1">
                          {needsSudo(cmdItem.command) && (
                            <span className="px-1.5 py-0.5 rounded text-[10px]" style={{ backgroundColor: colors.green + '20', color: colors.green }}>sudo</span>
                          )}
                          <span style={{ color: colors.accent }}>{cmdItem.command}</span>
                        </div>
                        <div className="text-[10px] leading-relaxed" style={{ color: colors.textDim }}>
                          {cmdItem.description}
                        </div>
                      </div>
                      
                      {/* copy button */}
                      <button
                        onClick={(e) => handleCopyCommand(e, cmdItem.command)}
                        className="opacity-0 group-hover:opacity-100 p-1.5 rounded hover:bg-white/10 flex-shrink-0 transition-all"
                        style={{ color: colors.textDim }}
                        title="Copy command"
                      >
                        <svg className="w-4 h-4" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                          <rect x="9" y="9" width="13" height="13" rx="2" ry="2"></rect>
                          <path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"></path>
                        </svg>
                      </button>
                    </div>
                  </div>
                ))}
              </div>
            )}
          </div>
        ))}
      </div>
    </div>
  )
}
