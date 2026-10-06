import { useState, useEffect, useCallback, useRef, lazy, Suspense } from 'react'
import { Header } from '../components/Header'
import { ActivityBar } from '../components/ActivityBar'
import { LeftSidebar } from '../components/LeftSidebar'
import { RightSidebar } from '../components/RightSidebar'
import { HistoryPanel } from '../components/HistoryPanel'
import { TerminalContainer } from '../components/TerminalContainer'
import { SFTPWorkspace } from '../components/SFTPWorkspace'
import { Settings } from '../components/Settings'
import { SSHConnectionModal } from '../components/SSHConnectionModal'
import { useThemeStore } from '../stores/themeStore'
import { useFileExplorerStore } from '../stores/fileExplorerStore'
import { useLocalFileStore } from '../stores/localFileStore'
import { useTerminalStore } from '../stores/terminalStore'
import { useAgentStore } from '../stores/agentStore'
import { ErrorBoundary } from '../components/ErrorBoundary'
import { BottomPanelBar } from '../components/BottomPanelBar'

// Lazy load Monaco editor component(reduce first screen bundle)
const FileWorkspace = lazy(() => import('../components/FileWorkspace').then(m => ({ default: m.FileWorkspace })))
const LocalFileWorkspace = lazy(() => import('../components/LocalFileWorkspace').then(m => ({ default: m.LocalFileWorkspace })))

// editor load placeholder
const EditorSkeleton = () => (
  <div className="flex items-center justify-center h-full text-gray-400 text-sm">
    <div className="flex gap-1">
      {[0, 150, 300].map(d => (
        <span key={d} className="w-1.5 h-1.5 rounded-full bg-current animate-pulse" style={{ animationDelay: `${d}ms` }} />
      ))}
    </div>
  </div>
)

type TabId = 'servers' | 'files' | 'sftp' | 'local' | 'extensions'

const ACTIVITY_BAR_WIDTH = 48
const PANEL_HANDLE_WIDTH = 6
const HISTORY_PANEL_WIDTH = 320
const MIN_WORKBENCH_WIDTH = 320
const MIN_SIDEBAR_WIDTH = 180
const MIN_CHAT_WIDTH = 520
const DEFAULT_CHAT_WIDTH = 640
const MAX_CHAT_WIDTH = 700

/**
 * MainView V4 - unified terminal management + multi-file tabs
 *
 * preferred content:
 * 1. terminal unify at MainView layer manages,servers and files tabs share the same terminal instance
 * 2. the files tab supports multiple layout modes(tab/ horizontal split/ vertical split)
 * 3. keep the terminal session across tab switches
 * 4. multi-file tab switching and management
 */
export function MainView() {
  const [settingsOpen, setSettingsOpen] = useState(false)
  const [sshModalOpen, setSshModalOpen] = useState(false)
  const [activeTab, setActiveTab] = useState<TabId>('local')
  const [sidebarVisible, setSidebarVisible] = useState(true)
  const [sidebarWidth, setSidebarWidth] = useState(() => {
    const saved = Number(localStorage.getItem('shellmind-sidebar-width'))
    return Number.isFinite(saved) && saved > 0 ? saved : 260
  })
  const [chatVisible, setChatVisible] = useState(true)
  const [chatWidth, setChatWidth] = useState(DEFAULT_CHAT_WIDTH)
  const [viewportWidth, setViewportWidth] = useState(() => window.innerWidth)
  const [chatExpanded, setChatExpanded] = useState(true)
  const [terminalVisible, setTerminalVisible] = useState(true)
  const [isResizingSidebar, setIsResizingSidebar] = useState(false)
  const [isResizingChat, setIsResizingChat] = useState(false)
  
  // file tab page layout game mode
  const [workbenchLayoutMode, setWorkbenchLayoutMode] = useState<'tabs' | 'split-horizontal' | 'split-vertical'>('tabs')
  const [terminalPanelSize, setTerminalPanelSize] = useState(300)
  const [isResizingTerminal, setIsResizingTerminal] = useState(false)
  const [dropdownOpen, setDropdownOpen] = useState(false)
  const [contextMenu, setContextMenu] = useState<{ x: number; y: number; tabKey: string } | null>(null)

  // listen RightSidebar triggered SSH popup window event(from /connect command)
  useEffect(() => {
    const handler = () => {
      setSshModalOpen(true)
      setSidebarVisible(true)
      setActiveTab('servers')
    }
    window.addEventListener('open-ssh-modal', handler)
    return () => window.removeEventListener('open-ssh-modal', handler)
  }, [])

  useEffect(() => {
    const handler = () => {
      setSidebarVisible(true)
      setActiveTab('local')
    }
    window.addEventListener('open-local-project', handler)
    return () => window.removeEventListener('open-local-project', handler)
  }, [])

  // listen file tree" at Terminal open in": expand the terminal panel and switch working directory
  useEffect(() => {
    const handler = (e: CustomEvent<{ cwd: string }>) => {
      const cwd = e.detail?.cwd
      if (!cwd) return
      setTerminalVisible(true)
      setIsTerminalActive(true)
      useTerminalStore.getState().openLocalTerminalAt(cwd)
    }
    window.addEventListener('open-local-terminal', handler as EventListener)
    return () => window.removeEventListener('open-local-terminal', handler as EventListener)
  }, [])
  const tabsScrollRef = useRef<HTMLDivElement>(null)
  
  // current activate terminal session ID
  const [activeTerminalSessionId, setActiveTerminalSessionId] = useState<string | null>(null)
  const [isTerminalActive, setIsTerminalActive] = useState(true)

  const { colors } = useThemeStore()
  const showHistoryPanel = useAgentStore((s) => s.showHistoryPanel)
  // history panel width judge set by" actual render placeholder use" compute:
  // side bar hide when not again reserve ActivityBar/ side bar width; the workspace is not rendered when chat is fullscreen, not again reserve workspace width.
  const sidebarReserve = sidebarVisible
    ? ACTIVITY_BAR_WIDTH + Math.min(sidebarWidth, 500) + PANEL_HANDLE_WIDTH
    : 0
  const historyPanelVisible = showHistoryPanel &&
    viewportWidth >= sidebarReserve + MIN_CHAT_WIDTH +
      (chatExpanded ? 0 : MIN_WORKBENCH_WIDTH) + HISTORY_PANEL_WIDTH + PANEL_HANDLE_WIDTH
  const sidebarMaxWidth = Math.min(
    500,
    Math.max(
      MIN_SIDEBAR_WIDTH,
      viewportWidth - ACTIVITY_BAR_WIDTH - PANEL_HANDLE_WIDTH * 2 -
        MIN_WORKBENCH_WIDTH - MIN_CHAT_WIDTH -
        (historyPanelVisible ? HISTORY_PANEL_WIDTH : 0),
    ),
  )
  const effectiveSidebarWidth = Math.min(sidebarWidth, sidebarMaxWidth)
  // chat pane width always follows the user setting(and not low than MIN_CHAT_WIDTH), open history panel equal
  // extra content when do not collapse chat bar; when space is tight when by middle workspace(flex-1 min-w-0) yield.
  const chatMaxWidth = Math.min(
    MAX_CHAT_WIDTH,
    Math.max(chatWidth, MIN_CHAT_WIDTH),
  )
  const effectiveChatWidth = Math.min(chatWidth, chatMaxWidth)
  const expandedChatWidth = Math.max(
    MIN_CHAT_WIDTH,
    viewportWidth -
      (sidebarVisible ? ACTIVITY_BAR_WIDTH + PANEL_HANDLE_WIDTH + effectiveSidebarWidth : 0) -
      (historyPanelVisible ? HISTORY_PANEL_WIDTH : 0),
  )

  useEffect(() => {
    const handleViewportResize = () => setViewportWidth(window.innerWidth)
    handleViewportResize()
    window.addEventListener('resize', handleViewportResize)
    return () => window.removeEventListener('resize', handleViewportResize)
  }, [])
  const { 
    openTabs, 
    activeTabKey, 
    setActiveTab: setActiveFileTab, 
    closeTab, 
    closeTabsToLeft, 
    closeTabsToRight, 
    closeOtherTabs, 
    closeAllTabs 
  } = useFileExplorerStore()

  // Sidebar drag
  const handleSidebarResizeStart = useCallback((e: React.PointerEvent) => {
    e.preventDefault()
    e.currentTarget.setPointerCapture(e.pointerId)
    setIsResizingSidebar(true)
    const startX = e.clientX
    const startWidth = effectiveSidebarWidth
    let latestWidth = startWidth

    const onPointerMove = (moveEvent: PointerEvent) => {
      latestWidth = Math.max(MIN_SIDEBAR_WIDTH, Math.min(sidebarMaxWidth, startWidth + (moveEvent.clientX - startX)))
      setSidebarWidth(latestWidth)
    }

    const onPointerUp = () => {
      setIsResizingSidebar(false)
      window.removeEventListener('pointermove', onPointerMove)
      window.removeEventListener('pointerup', onPointerUp)
      window.removeEventListener('pointercancel', onPointerUp)
      document.body.style.cursor = ''
      localStorage.setItem('shellmind-sidebar-width', String(Math.round(latestWidth)))
    }

    document.body.style.cursor = 'col-resize'
    window.addEventListener('pointermove', onPointerMove)
    window.addEventListener('pointerup', onPointerUp)
    window.addEventListener('pointercancel', onPointerUp)
  }, [effectiveSidebarWidth, sidebarMaxWidth])

  // Chat drag
  const handleChatResizeStart = useCallback((e: React.MouseEvent) => {
    e.preventDefault()
    setIsResizingChat(true)
    const startX = e.clientX
    const startWidth = chatWidth

    const onMouseMove = (moveEvent: MouseEvent) => {
      const newWidth = Math.max(MIN_CHAT_WIDTH, Math.min(chatMaxWidth, startWidth - (moveEvent.clientX - startX)))
      setChatWidth(newWidth)
    }

    const onMouseUp = () => {
      setIsResizingChat(false)
      document.removeEventListener('mousemove', onMouseMove)
      document.removeEventListener('mouseup', onMouseUp)
      document.body.style.cursor = ''
    }

    document.addEventListener('mousemove', onMouseMove)
    document.addEventListener('mouseup', onMouseUp)
  }, [chatMaxWidth])

  const toggleChatExpanded = useCallback(() => {
    setChatExpanded((expanded) => {
      if (expanded) {
        setChatWidth(DEFAULT_CHAT_WIDTH)
        return false
      }
      return true
    })
  }, [])

  useEffect(() => {
    document.body.style.backgroundColor = colors.bgPrimary
    document.body.style.color = colors.text
  }, [colors])

  // handle terminal session change
  const handleTerminalSessionChange = useCallback((sessionId: string | null) => {
    setActiveTerminalSessionId(sessionId)
    // SSH terminal session create success → auto open terminal panel
    if (sessionId) {
      setTerminalVisible(true)
    }
  }, [])

  // listen left panel SSH connection on off event: connection success back auto open SSH terminal panel
  useEffect(() => {
    const handler = (_e: CustomEvent<{ connectionId: string }>) => {
      setTerminalVisible(true)
      setIsTerminalActive(true)
      setActiveTab('servers')
    }
    window.addEventListener('open-ssh-terminal', handler as EventListener)
    return () => window.removeEventListener('open-ssh-terminal', handler as EventListener)
  }, [])

  // listen fileExplorerStore.activeTabKey change: current remote file/diff tab activate when, auto switch to files tab merge show file view image
  // note: use ref avoid localFileStore activeTabKey change produce create race race
  const prevActiveTabKeyRef = useRef<string | null>(activeTabKey)
  useEffect(() => {
    // only activeTabKey true align change when just switch(avoid local racing with tab switching)
    if (activeTabKey && activeTabKey !== prevActiveTabKeyRef.current) {
      prevActiveTabKeyRef.current = activeTabKey
      setIsTerminalActive(false)
      // remote file tab or diff tab → ensure activeTab as 'files'
      if (activeTab !== 'files') {
        setActiveTab('files')
      }
    }
  }, [activeTabKey]) // eslint-disable-line react-hooks/exhaustive-deps

  // listen localFileStore.activeTabKey change: current local file tab activate when, auto switch to local tab
  // note: use ref avoid fileExplorerStore activeTabKey change produce create race race
  const localActiveTabKey = useLocalFileStore((s) => s.activeTabKey)
  const prevLocalActiveTabKeyRef = useRef<string | null>(localActiveTabKey)
  useEffect(() => {
    // only localActiveTabKey true align change when just switch(avoid files racing with tab switching)
    if (localActiveTabKey && localActiveTabKey !== prevLocalActiveTabKeyRef.current) {
      prevLocalActiveTabKeyRef.current = localActiveTabKey
      if (activeTab !== 'local') {
        setActiveTab('local')
      }
    }
  }, [localActiveTabKey]) // eslint-disable-line react-hooks/exhaustive-deps

  useEffect(() => {
    if (activeTabKey || localActiveTabKey) {
      // open file tab when only collapse chat bar expand mode; not edit write chatWidth,
      // chat pane width is always set by the user drag(min MIN_CHAT_WIDTH=520).
      setChatExpanded(false)
    }
  }, [activeTabKey, localActiveTabKey])

  // handle terminal size resize
  const handleTerminalResizeStart = useCallback((e: React.MouseEvent) => {
    e.preventDefault()
    setIsResizingTerminal(true)
    
    const onMouseMove = (moveEvent: MouseEvent) => {
      if (workbenchLayoutMode === 'split-horizontal') {
        const container = document.querySelector('.workbench-container') as HTMLElement
        if (container) {
          const containerRect = container.getBoundingClientRect()
          const newSize = containerRect.bottom - moveEvent.clientY
          setTerminalPanelSize(Math.max(150, Math.min(600, newSize)))
        }
      } else if (workbenchLayoutMode === 'split-vertical') {
        const container = document.querySelector('.workbench-container') as HTMLElement
        if (container) {
          const containerRect = container.getBoundingClientRect()
          const newSize = containerRect.right - moveEvent.clientX
          setTerminalPanelSize(Math.max(250, Math.min(800, newSize)))
        }
      }
    }

    const onMouseUp = () => {
      setIsResizingTerminal(false)
      document.removeEventListener('mousemove', onMouseMove)
      document.removeEventListener('mouseup', onMouseUp)
    }

    document.addEventListener('mousemove', onMouseMove)
    document.addEventListener('mouseup', onMouseUp)
  }, [workbenchLayoutMode])

  // click empty space to close the dropdown
  useEffect(() => {
    const closeDropdown = () => setDropdownOpen(false)
    if (dropdownOpen) {
      document.addEventListener('click', closeDropdown)
      return () => document.removeEventListener('click', closeDropdown)
    }
  }, [dropdownOpen])

  // context menu handle
  useEffect(() => {
    const closeContextMenu = () => setContextMenu(null)
    if (contextMenu) {
      document.addEventListener('click', closeContextMenu)
      document.addEventListener('contextmenu', closeContextMenu)
      return () => {
        document.removeEventListener('click', closeContextMenu)
        document.removeEventListener('contextmenu', closeContextMenu)
      }
    }
  }, [contextMenu])

  return (
    <div className="w-full h-full flex flex-col overflow-hidden" style={{ backgroundColor: colors.bgPrimary }}>
      {/* ===== top title bar ===== */}
      <Header
        onToggleChat={() => setChatVisible(!chatVisible)}
        chatVisible={chatVisible}
        onOpenLocalFolder={() => {
          setActiveTab('local')
          setSidebarVisible(true)
          // open the folder picker
          void useLocalFileStore.getState().openFolder()
        }}
      />

      {/* ===== main area ===== */}
      <div className="flex-1 flex overflow-hidden relative">
        {/* left:ActivityBar + Sidebar(collapse when fully hidden, only keep top-left expand button) */}
        {sidebarVisible ? (
          <>
            <ActivityBar
              activeTab={activeTab}
              onTabChange={(tab) => {
                setActiveTab(tab)
                setSidebarVisible(true)
              }}
              sidebarVisible={sidebarVisible}
              onToggleSidebar={() => setSidebarVisible(!sidebarVisible)}
              onOpenSettings={() => setSettingsOpen(true)}
            />

            <div
              className="flex-shrink-0 transition-none overflow-hidden relative"
              style={{ width: effectiveSidebarWidth }}
            >
              <LeftSidebar activeTab={activeTab} />
            </div>

            {/* Sidebar drag handle */}
            <div
              className="w-1 h-full cursor-col-resize relative z-50 flex-shrink-0 group"
              style={{ backgroundColor: isResizingSidebar ? colors.accent : colors.border }}
              onPointerDown={handleSidebarResizeStart}
              title="Drag to resize the project pane"
            >
              <div className="absolute inset-y-0 -left-2 -right-2 z-10" />
              <div
                className="absolute top-1/2 left-1/2 -translate-x-1/2 -translate-y-1/2 flex gap-0.5 opacity-30 group-hover:opacity-70"
              >
                <span className="w-0.5 h-3 rounded-full" style={{ backgroundColor: colors.textDim }} />
                <span className="w-0.5 h-3 rounded-full" style={{ backgroundColor: colors.textDim }} />
              </div>
              {isResizingSidebar && <div className="absolute inset-y-0 -left-2 -right-2 bg-blue-500/10" />}
            </div>
          </>
        ) : (
          <button
            onClick={() => setSidebarVisible(true)}
            className="absolute top-2 left-2 z-40 w-7 h-7 flex items-center justify-center rounded-md transition-colors hover:brightness-110"
            style={{ backgroundColor: colors.bgSecondary, border: `1px solid ${colors.border}`, color: colors.textSecondary }}
            title="Expand sidebar"
          >
            <svg className="w-4 h-4" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">
              <rect x="3" y="4" width="18" height="16" rx="2" />
              <line x1="9" y1="4" x2="9" y2="20" />
            </svg>
          </button>
        )}

        {/* middle: based on left Tab switch workspace */}
        {!chatExpanded && (
        <div className="flex-1 min-w-0 overflow-hidden relative workbench-container">
          {/* local folder tab - file tree + Monaco editor + terminal */}
          {activeTab === 'local' && (
            <div className="h-full min-w-0 flex flex-col">
              <Suspense fallback={<EditorSkeleton />}>
                <LocalFileWorkspace />
              </Suspense>
              {/* bottom terminal panel */}
              {terminalVisible && (
                <div style={{ height: terminalPanelSize, minHeight: 150, borderTop: `1px solid ${colors.border}` }} className="flex-shrink-0">
                  <ErrorBoundary>
                    <TerminalContainer 
                      onTerminalSessionChange={handleTerminalSessionChange}
                      onCloseTerminal={() => setTerminalVisible(false)}
                    />
                  </ErrorBoundary>
                </div>
              )}
              {/* terminal drag handle */}
              {terminalVisible && (
                <div
                  className="h-1 cursor-row-resize hover:bg-blue-500/30 flex-shrink-0"
                  style={{ backgroundColor: isResizingTerminal ? colors.accent : 'transparent' }}
                  onMouseDown={handleTerminalResizeStart}
                />
              )}
              {/* bottom edge bar - always visible, for collapse/ expand terminal */}
              <BottomPanelBar
                terminalVisible={terminalVisible}
                onToggleTerminal={() => setTerminalVisible(!terminalVisible)}
              />
            </div>
          )}

          {/* SSH server tab - only show terminal */}
          {activeTab === 'servers' && (
            <div className="h-full min-w-0 flex flex-col">
              <div className="flex-1 min-h-0">
                {terminalVisible ? (
                  <ErrorBoundary>
                    <TerminalContainer 
                      onTerminalSessionChange={handleTerminalSessionChange}
                      onCloseTerminal={() => setTerminalVisible(false)}
                    />
                  </ErrorBoundary>
                ) : (
                  <div className="h-full flex items-center justify-center">
                    <p className="text-sm" style={{ color: colors.textDim }}>Terminal hidden (⌘` to show)</p>
                  </div>
                )}
              </div>
              {/* bottom edge bar - always visible, for collapse/ expand terminal */}
              <BottomPanelBar
                terminalVisible={terminalVisible}
                onToggleTerminal={() => setTerminalVisible(!terminalVisible)}
              />
            </div>
          )}

          {/* file/SFTP tab - unified terminal management + multi-file tabs */}
          {(activeTab === 'files' || activeTab === 'sftp') && (
            <div className="h-full min-w-0 flex flex-col">
              {/* toolbar(only in files tab down show terminal switch and file tab) */}
              {activeTab === 'files' && (
                <div className="h-9 border-b flex items-center pr-2 relative" style={{ backgroundColor: colors.bgSecondary, borderColor: `${colors.border}99` }}>
                  {/* left layout switch button */}
                  {terminalVisible && (
                    <div className="flex-shrink-0 flex items-center h-full px-2 gap-1" style={{ borderRight: `1px solid ${colors.border}` }}>
                      {/* tab mode button group */}
                      <div className="flex items-center gap-1">
                        <button
                          onClick={() => {
                            setWorkbenchLayoutMode('tabs')
                            setIsTerminalActive(true)
                          }}
                          className={`h-7 px-2 rounded-md flex items-center gap-1 text-xs transition-colors`}
                          style={{
                            color: workbenchLayoutMode === 'tabs' && isTerminalActive ? colors.accent : colors.textSecondary,
                            backgroundColor: workbenchLayoutMode === 'tabs' && isTerminalActive ? colors.accentSoft : 'transparent',
                            border: `1px solid ${workbenchLayoutMode === 'tabs' && isTerminalActive ? `${colors.accent}26` : 'transparent'}`,
                          }}
                          title="Tabs"
                        >
                          <svg className="w-3.5 h-3.5" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                            <polyline points="4 17 10 11 4 5"></polyline>
                            <line x1="12" y1="19" x2="20" y2="19"></line>
                          </svg>
                          <span className="font-medium">Terminal</span>
                        </button>
                      </div>
                      
                      <button
                        onClick={() => setWorkbenchLayoutMode('split-horizontal')}
                        className={`h-7 px-2 rounded-md flex items-center gap-1 text-xs transition-colors`}
                        style={{
                          color: workbenchLayoutMode === 'split-horizontal' ? colors.accent : colors.textSecondary,
                          backgroundColor: workbenchLayoutMode === 'split-horizontal' ? colors.accentSoft : 'transparent',
                          border: `1px solid ${workbenchLayoutMode === 'split-horizontal' ? `${colors.accent}26` : 'transparent'}`,
                        }}
                        title="Split horizontal"
                      >
                        <svg className="w-3.5 h-3.5" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                          <rect x="3" y="3" width="18" height="8" rx="1"></rect>
                          <rect x="3" y="13" width="18" height="8" rx="1"></rect>
                        </svg>
                      </button>
                      
                      <button
                        onClick={() => setWorkbenchLayoutMode('split-vertical')}
                        className={`h-7 px-2 rounded-md flex items-center gap-1 text-xs transition-colors`}
                        style={{
                          color: workbenchLayoutMode === 'split-vertical' ? colors.accent : colors.textSecondary,
                          backgroundColor: workbenchLayoutMode === 'split-vertical' ? colors.accentSoft : 'transparent',
                          border: `1px solid ${workbenchLayoutMode === 'split-vertical' ? `${colors.accent}26` : 'transparent'}`,
                        }}
                        title="Split vertical"
                      >
                        <svg className="w-3.5 h-3.5" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                          <rect x="3" y="3" width="8" height="18" rx="1"></rect>
                          <rect x="13" y="3" width="8" height="18" rx="1"></rect>
                        </svg>
                      </button>
                    </div>
                  )}

                  {/* file tab area */}
                  <div 
                    ref={tabsScrollRef}
                    className="flex-1 h-full flex items-center px-2 gap-1 overflow-x-auto no-scrollbar"
                  >
                    {openTabs.map((tab) => {
                      const isActive = activeTabKey === tab.key
                      const isDiff = 'kind' in tab && tab.kind === 'diff'
                      return (
                        <button
                          key={tab.key}
                          data-tab-key={tab.key}
                          onClick={() => {
                            setActiveFileTab(tab.key)
                            setIsTerminalActive(false)
                            if (workbenchLayoutMode === 'tabs') {
                              setWorkbenchLayoutMode('tabs')
                            }
                          }}
                          onContextMenu={(e) => {
                            e.preventDefault()
                            e.stopPropagation()
                            setContextMenu({ x: e.clientX, y: e.clientY, tabKey: tab.key })
                          }}
                          className="group h-7 px-3 rounded-md flex items-center gap-2 text-xs max-w-[200px] flex-shrink-0 transition-colors"
                          style={{
                            color: isActive ? colors.text : colors.textSecondary,
                            backgroundColor: isActive ? colors.bgPrimary : 'transparent',
                            border: `1px solid ${isActive ? `${colors.border}A6` : 'transparent'}`,
                          }}
                        >
                          {isDiff && <span className="text-[10px] flex-shrink-0" title="Diff">🔀</span>}
                          <span className="truncate">{tab.name}</span>
                          <span
                            onClick={(e) => {
                              e.stopPropagation()
                              closeTab(tab.key)
                            }}
                            className="opacity-0 group-hover:opacity-60 hover:!opacity-100 flex items-center justify-center w-4 h-4 rounded-sm"
                            style={{ backgroundColor: isActive ? 'rgba(255,255,255,0.1)' : 'transparent' }}
                          >
                            <svg className="w-3 h-3" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                              <line x1="18" y1="6" x2="6" y2="18"></line>
                              <line x1="6" y1="6" x2="18" y2="18"></line>
                            </svg>
                          </span>
                        </button>
                      )
                    })}
                    {openTabs.length === 0 && (
                      <span className="text-xs px-2" style={{ color: colors.textDim }}>
                        Open a file from the tree on the left
                      </span>
                    )}
                  </div>

                  {/* right dropdown menu button */}
                  {openTabs.length > 0 && (
                    <div className="relative flex-shrink-0 flex items-center pl-1 border-l" style={{ borderColor: colors.border }}>
                      <button
                        onClick={(e) => {
                          e.stopPropagation()
                          setDropdownOpen(!dropdownOpen)
                        }}
                        className="h-7 px-2 rounded-md flex items-center gap-1 text-xs hover:bg-white/5 transition-colors"
                        style={{ color: colors.textSecondary }}
                      >
                        <span className="font-mono text-[10px] bg-black/10 px-1 rounded">{openTabs.length}</span>
                        <svg className="w-3.5 h-3.5" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                          <polyline points="6 9 12 15 18 9"></polyline>
                        </svg>
                      </button>
                      
                      {/* dropdown menu panel */}
                      {dropdownOpen && (
                        <div 
                          className="absolute top-full right-0 mt-1 w-56 rounded-lg border py-1 z-50 max-h-80 overflow-y-auto"
                          style={{ backgroundColor: colors.bgSecondary, borderColor: colors.border }}
                        >
                          <div className="px-3 py-1.5 text-[11px] uppercase font-medium tracking-wider border-b mb-1" style={{ color: colors.textDim, borderColor: colors.border }}>
                            Open files
                          </div>
                          {openTabs.map((tab) => (
                            <button
                              key={`menu-${tab.key}`}
                              className="w-full text-left px-3 py-1.5 text-xs flex items-center justify-between hover:bg-white/5 transition-colors"
                              style={{ color: activeTabKey === tab.key ? colors.accent : colors.text }}
                              onClick={() => {
                                setActiveFileTab(tab.key)
                                setIsTerminalActive(false)
                                setDropdownOpen(false)
                              }}
                            >
                              <span className="truncate pr-4">{tab.name}</span>
                              <span 
                                className="opacity-60 hover:opacity-100 flex-shrink-0"
                                onClick={(e) => {
                                  e.stopPropagation()
                                  closeTab(tab.key)
                                }}
                              >
                                <svg className="w-3.5 h-3.5" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                                  <line x1="18" y1="6" x2="6" y2="18"></line>
                                  <line x1="6" y1="6" x2="18" y2="18"></line>
                                </svg>
                              </span>
                            </button>
                          ))}
                        </div>
                      )}
                    </div>
                  )}
                </div>
              )}

              {/* content area */}
              <div className="flex-1 min-w-0 relative">
                {activeTab === 'sftp' ? (
                  <div className="absolute inset-0">
                    <SFTPWorkspace />
                  </div>
                ) : (
                  <>
                    {/* tab mode */}
                    {(workbenchLayoutMode === 'tabs' || !terminalVisible) && (
                      <>
                        {/* terminal tab */}
                        {terminalVisible && isTerminalActive && (
                          <div className="absolute inset-0">
                            <ErrorBoundary>
                              <TerminalContainer 
                                keepSessionOnUnmount={true}
                                onTerminalSessionChange={handleTerminalSessionChange}
                                onCloseTerminal={() => setTerminalVisible(false)}
                              />
                            </ErrorBoundary>
                          </div>
                        )}
                        {/* file tab */}
                        {(!terminalVisible || !isTerminalActive || openTabs.length > 0) && (
                          <div className="absolute inset-0">
                            <Suspense fallback={<EditorSkeleton />}>
                              <FileWorkspace />
                            </Suspense>
                          </div>
                        )}
                      </>
                    )}

                    {/* horizontal split mode(file at up, terminal at down) */}
                    {workbenchLayoutMode === 'split-horizontal' && terminalVisible && (
                      <div className="absolute inset-0 flex flex-col">
                        <div className="flex-1 min-h-0">
                          <Suspense fallback={<EditorSkeleton />}>
                            <FileWorkspace />
                          </Suspense>
                        </div>
                        <div 
                          className="h-1 cursor-row-resize hover:bg-blue-500/30" 
                          style={{ backgroundColor: isResizingTerminal ? colors.accent : 'transparent' }}
                          onMouseDown={handleTerminalResizeStart}
                        />
                        <div style={{ height: terminalPanelSize, minHeight: 150 }}>
                          <ErrorBoundary>
                            <TerminalContainer 
                              keepSessionOnUnmount={true}
                              onTerminalSessionChange={handleTerminalSessionChange}
                              onCloseTerminal={() => setTerminalVisible(false)}
                            />
                          </ErrorBoundary>
                        </div>
                      </div>
                    )}

                    {/* vertical split mode(file at left, terminal at right) */}
                    {workbenchLayoutMode === 'split-vertical' && terminalVisible && (
                      <div className="absolute inset-0 flex flex-row">
                        <div className="flex-1 min-w-0">
                          <Suspense fallback={<EditorSkeleton />}>
                            <FileWorkspace />
                          </Suspense>
                        </div>
                        <div 
                          className="w-1 cursor-col-resize hover:bg-blue-500/30" 
                          style={{ backgroundColor: isResizingTerminal ? colors.accent : 'transparent' }}
                          onMouseDown={handleTerminalResizeStart}
                        />
                        <div style={{ width: terminalPanelSize, minWidth: 250 }}>
                          <ErrorBoundary>
                            <TerminalContainer 
                              keepSessionOnUnmount={true}
                              onTerminalSessionChange={handleTerminalSessionChange}
                              onCloseTerminal={() => setTerminalVisible(false)}
                            />
                          </ErrorBoundary>
                        </div>
                      </div>
                    )}
                  </>
                )}
              </div>
              {/* bottom edge bar - always visible, for collapse/ expand terminal */}
              <BottomPanelBar
                terminalVisible={terminalVisible}
                onToggleTerminal={() => setTerminalVisible(!terminalVisible)}
              />
            </div>
          )}

          {/* SFTP pass io panel */}
          {activeTab === 'sftp' && (
            <div className="h-full">
              <SFTPWorkspace />
            </div>
          )}

          {/* extension tab */}
          {activeTab === 'extensions' && (
            <div className="h-full flex items-center justify-center">
              <p className="text-sm" style={{ color: colors.textDim }}>Extensions panel coming soon</p>
            </div>
          )}
        </div>
        )}

        {/* right:AI chat panel */}
        {chatVisible && (
          <>
            {!chatExpanded && (
              <div
                className="w-1.5 h-full cursor-col-resize relative z-50 flex-shrink-0 bg-[#3c3c3c]/40"
                style={{ backgroundColor: isResizingChat ? colors.accent : undefined }}
                onMouseDown={handleChatResizeStart}
              >
                <div
                  className={`absolute inset-y-0 -left-[3px] -right-[3px] ${isResizingChat ? '' : 'hover:bg-blue-500/30'} rounded-full`}
                />
              </div>
            )}
            <ErrorBoundary>
              <RightSidebar
                width={chatExpanded ? expandedChatWidth : effectiveChatWidth}
                activeTerminalSessionId={activeTerminalSessionId}
                expanded={chatExpanded}
                onToggleExpanded={toggleChatExpanded}
              />
            </ErrorBoundary>
            {/* history panel - chat bar right expand */}
            {historyPanelVisible && (
              <HistoryPanel width={HISTORY_PANEL_WIDTH} />
            )}
          </>
        )}
      </div>

      {/* SSH connection config popup window */}
      <SSHConnectionModal open={sshModalOpen} onClose={() => setSshModalOpen(false)} />

      {/* settings popup window */}
      <Settings open={settingsOpen} onClose={() => setSettingsOpen(false)} />

      {/* context menu */}
      {contextMenu && (
        <div
          className="fixed z-50 w-48 rounded-md shadow-lg border py-1"
          style={{
            left: Math.min(contextMenu.x, window.innerWidth - 200),
            top: Math.min(contextMenu.y, window.innerHeight - 200),
            backgroundColor: colors.bgSecondary,
            borderColor: colors.border,
          }}
          onClick={(e) => e.stopPropagation()}
        >
          <button
            className="w-full text-left px-3 py-1.5 text-xs hover:bg-white/5 transition-colors"
            style={{ color: colors.text }}
            onClick={() => {
              closeTabsToLeft(contextMenu.tabKey)
              setContextMenu(null)
            }}
          >
            Close to the left
          </button>
          <button
            className="w-full text-left px-3 py-1.5 text-xs hover:bg-white/5 transition-colors"
            style={{ color: colors.text }}
            onClick={() => {
              closeTabsToRight(contextMenu.tabKey)
              setContextMenu(null)
            }}
          >
            Close to the right
          </button>
          <button
            className="w-full text-left px-3 py-1.5 text-xs hover:bg-white/5 transition-colors"
            style={{ color: colors.text }}
            onClick={() => {
              closeOtherTabs(contextMenu.tabKey)
              setContextMenu(null)
            }}
          >
            Close others
          </button>
          <div style={{ height: 1, backgroundColor: colors.border, margin: '4px 0' }} />
          <button
            className="w-full text-left px-3 py-1.5 text-xs hover:bg-white/5 transition-colors"
            style={{ color: colors.text }}
            onClick={() => {
              closeAllTabs()
              setContextMenu(null)
            }}
          >
            Close all
          </button>
        </div>
      )}
    </div>
  )
}
