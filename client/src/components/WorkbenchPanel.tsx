import { useEffect, useMemo, useRef, useState } from 'react'
import { useThemeStore } from '../stores/themeStore'
import { useFileExplorerStore } from '../stores/fileExplorerStore'
import { TerminalPanel } from './TerminalPanel'
import { FileWorkspace } from './FileWorkspace'
import { SFTPWorkspace } from './SFTPWorkspace'

interface WorkbenchPanelProps {
  terminalVisible: boolean
  onTerminalSessionChange?: (sessionId: string | null) => void
  globalTerminalManaged?: boolean
  activeTab?: string // added to know if it's 'sftp'
}

const TERMINAL_TAB_KEY = '__terminal__'

type PanelLayout = 'tabs' | 'split-vertical' | 'split-horizontal'

export function WorkbenchPanel({ terminalVisible, onTerminalSessionChange, globalTerminalManaged = false, activeTab }: WorkbenchPanelProps) {
  const { colors } = useThemeStore()
  const { openTabs, activeTabKey, setActiveTab, closeTab, closeTabsToLeft, closeTabsToRight, closeOtherTabs, closeAllTabs } = useFileExplorerStore()
  
  // default use tab mode, but user can so switch
  const [layoutMode, setLayoutMode] = useState<PanelLayout>('tabs')
  const [terminalPanelSize, setTerminalPanelSize] = useState(300) // terminal panel size in split layout
  const [isResizing, setIsResizing] = useState(false)
  
  const [activePane, setActivePane] = useState<string>(globalTerminalManaged ? '' : (terminalVisible ? TERMINAL_TAB_KEY : ''))
  const lastActiveTabKeyRef = useRef<string | null>(activeTabKey)

  const [dropdownOpen, setDropdownOpen] = useState(false)
  const tabsScrollRef = useRef<HTMLDivElement>(null)
  const [contextMenu, setContextMenu] = useState<{ x: number; y: number; tabKey: string } | null>(null)
  const panelResizeRef = useRef<HTMLDivElement>(null)

  const paneKeys = useMemo(() => {
    const keys: string[] = []
    if (terminalVisible && !globalTerminalManaged) keys.push(TERMINAL_TAB_KEY)
    keys.push(...openTabs.map((tab) => tab.key))
    return keys
  }, [terminalVisible, openTabs, globalTerminalManaged])

  useEffect(() => {
    // global terminal mode down, do not handle TERMINAL_TAB_KEY
    if (globalTerminalManaged) {
      if (activeTabKey && activePane !== activeTabKey) {
        setActivePane(activeTabKey)
      }
      return
    }

    // raw has logic edit(non global terminal mode)
    if (terminalVisible && activePane === '') {
      setActivePane(TERMINAL_TAB_KEY)
      return
    }
    if (!terminalVisible && activePane === TERMINAL_TAB_KEY) {
      if (activeTabKey) setActivePane(activeTabKey)
      else setActivePane('')
      return
    }
    if (activePane && !paneKeys.includes(activePane)) {
      if (terminalVisible) setActivePane(TERMINAL_TAB_KEY)
      else if (activeTabKey) setActivePane(activeTabKey)
      else setActivePane('')
    }
  }, [terminalVisible, activePane, paneKeys, activeTabKey, globalTerminalManaged])

  useEffect(() => {
    const prev = lastActiveTabKeyRef.current
    const next = activeTabKey
    if (next && next !== prev && paneKeys.includes(next)) {
      setActivePane(next)
      // auto scroll to ok view area
      setTimeout(() => {
        const activeBtn = tabsScrollRef.current?.querySelector(`[data-tab-key="${next}"]`) as HTMLElement
        if (activeBtn && tabsScrollRef.current) {
          activeBtn.scrollIntoView({ behavior: 'smooth', block: 'nearest', inline: 'center' })
        }
      }, 50)
    }
    lastActiveTabKeyRef.current = next
  }, [activeTabKey, paneKeys])

  // handle terminal panel resize size
  useEffect(() => {
    if (!isResizing) return

    const handleMouseMove = (e: MouseEvent) => {
      if (layoutMode === 'split-horizontal') {
        const container = panelResizeRef.current?.parentElement
        if (container) {
          const containerRect = container.getBoundingClientRect()
          const newSize = containerRect.bottom - e.clientY
          setTerminalPanelSize(Math.max(150, Math.min(600, newSize)))
        }
      } else if (layoutMode === 'split-vertical') {
        const container = panelResizeRef.current?.parentElement
        if (container) {
          const containerRect = container.getBoundingClientRect()
          const newSize = containerRect.right - e.clientY
          setTerminalPanelSize(Math.max(250, Math.min(800, newSize)))
        }
      }
    }

    const handleMouseUp = () => {
      setIsResizing(false)
    }

    document.addEventListener('mousemove', handleMouseMove)
    document.addEventListener('mouseup', handleMouseUp)
    return () => {
      document.removeEventListener('mousemove', handleMouseMove)
      document.removeEventListener('mouseup', handleMouseUp)
    }
  }, [isResizing, layoutMode])

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

  // switch layout game mode
  const toggleLayoutMode = (mode: PanelLayout) => {
    if (layoutMode === mode) {
      setLayoutMode('tabs')
    } else {
      setLayoutMode(mode)
    }
  }

  return (
    <div className="h-full flex flex-col min-w-0 relative" style={{ backgroundColor: colors.bgTertiary }}>
      <div className="h-9 border-b flex items-center pr-2 relative" style={{ borderColor: colors.border }}>
        {/* fixed at most left terminal button - support extra kind view image mode */}
        {terminalVisible && (
          <div className="flex-shrink-0 flex items-center h-full px-2 gap-1" style={{ borderRight: `1px solid ${colors.border}` }}>
            {/* tab mode button */}
            <button
              data-tab-key={TERMINAL_TAB_KEY}
              onClick={() => {
                if (globalTerminalManaged) {
                  // if is global mode, switch to tab mode
                  setLayoutMode('tabs')
                  setActivePane(TERMINAL_TAB_KEY)
                } else {
                  setActivePane(TERMINAL_TAB_KEY)
                }
              }}
              className={`h-7 px-2 rounded-md flex items-center gap-1 text-xs transition-colors ${
                (!globalTerminalManaged && activePane === TERMINAL_TAB_KEY && layoutMode === 'tabs') ? '' : ''
              }`}
              style={{
                color: (!globalTerminalManaged && activePane === TERMINAL_TAB_KEY && layoutMode === 'tabs') ? colors.accent : colors.textSecondary,
                backgroundColor: (!globalTerminalManaged && activePane === TERMINAL_TAB_KEY && layoutMode === 'tabs') ? colors.bgSecondary : 'transparent',
                border: `1px solid ${(!globalTerminalManaged && activePane === TERMINAL_TAB_KEY && layoutMode === 'tabs') ? colors.border : 'transparent'}`,
              }}
              title="Tabs"
            >
              <svg className="w-3.5 h-3.5" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                <polyline points="4 17 10 11 4 5"></polyline>
                <line x1="12" y1="19" x2="20" y2="19"></line>
              </svg>
              <span className="font-medium">Terminal</span>
            </button>

            {/* flat split button */}
            <button
              onClick={() => toggleLayoutMode('split-horizontal')}
              className={`h-7 px-2 rounded-md flex items-center gap-1 text-xs transition-colors ${
                layoutMode === 'split-horizontal' ? '' : ''
              }`}
              style={{
                color: layoutMode === 'split-horizontal' ? colors.accent : colors.textSecondary,
                backgroundColor: layoutMode === 'split-horizontal' ? colors.bgSecondary : 'transparent',
                border: `1px solid ${layoutMode === 'split-horizontal' ? colors.border : 'transparent'}`,
              }}
              title="Split horizontal"
            >
              <svg className="w-3.5 h-3.5" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                <rect x="3" y="3" width="18" height="8" rx="1"></rect>
                <rect x="3" y="13" width="18" height="8" rx="1"></rect>
              </svg>
            </button>

            {/* vertical straight split button */}
            <button
              onClick={() => toggleLayoutMode('split-vertical')}
              className={`h-7 px-2 rounded-md flex items-center gap-1 text-xs transition-colors ${
                layoutMode === 'split-vertical' ? '' : ''
              }`}
              style={{
                color: layoutMode === 'split-vertical' ? colors.accent : colors.textSecondary,
                backgroundColor: layoutMode === 'split-vertical' ? colors.bgSecondary : 'transparent',
                border: `1px solid ${layoutMode === 'split-vertical' ? colors.border : 'transparent'}`,
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

        {/* right ok scroll file Tab area */}
        <div 
          ref={tabsScrollRef}
          className="flex-1 h-full flex items-center px-2 gap-1 overflow-x-auto no-scrollbar"
        >
          {openTabs.map((tab) => {
            const active = activePane === tab.key
            return (
              <button
                key={tab.key}
                data-tab-key={tab.key}
                onClick={() => { setActivePane(tab.key); setActiveTab(tab.key) }}
                onContextMenu={(e) => {
                  e.preventDefault()
                  e.stopPropagation()
                  setContextMenu({ x: e.clientX, y: e.clientY, tabKey: tab.key })
                }}
                className="group h-7 px-3 rounded-md flex items-center gap-2 text-xs max-w-[200px] flex-shrink-0 transition-colors"
                style={{
                  color: active ? colors.text : colors.textSecondary,
                  backgroundColor: active ? colors.bgSecondary : 'transparent',
                  border: `1px solid ${active ? colors.border : 'transparent'}`,
                }}
              >
                <span className="truncate">{tab.name}</span>
                <span
                  onClick={(e) => {
                    e.stopPropagation()
                    closeTab(tab.key)
                  }}
                  className="opacity-0 group-hover:opacity-60 hover:!opacity-100 flex items-center justify-center w-4 h-4 rounded-sm"
                  style={{ backgroundColor: active ? 'rgba(255,255,255,0.1)' : 'transparent' }}
                >
                  <svg className="w-3 h-3" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                    <line x1="18" y1="6" x2="6" y2="18"></line>
                    <line x1="6" y1="6" x2="18" y2="18"></line>
                  </svg>
                </span>
              </button>
            )
          })}
          {(openTabs.length === 0) && (
            <span className="text-xs px-2" style={{ color: colors.textDim }}>
              Open a file from the tree on the left
            </span>
          )}
        </div>

        {/* right dropdown menu button */}
        {openTabs.length > 0 && (
          <div className="relative flex-shrink-0 flex items-center pl-1 border-l" style={{ borderColor: colors.border }}>
            <button
              onClick={(e) => { e.stopPropagation(); setDropdownOpen(!dropdownOpen) }}
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
                className="absolute top-full right-0 mt-1 w-56 rounded-md shadow-lg border py-1 z-50 max-h-80 overflow-y-auto"
                style={{ backgroundColor: colors.bgSecondary, borderColor: colors.border }}
              >
                <div className="px-3 py-1.5 text-[11px] uppercase font-medium tracking-wider border-b mb-1" style={{ color: colors.textDim, borderColor: colors.border }}>
                  Open files
                </div>
                {openTabs.map(tab => (
                  <button
                    key={`menu-${tab.key}`}
                    className="w-full text-left px-3 py-1.5 text-xs flex items-center justify-between hover:bg-white/5 transition-colors"
                    style={{ color: activePane === tab.key ? colors.accent : colors.text }}
                    onClick={() => {
                      setActivePane(tab.key)
                      setActiveTab(tab.key)
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

      <div className="flex-1 min-w-0 relative" ref={panelResizeRef}>
        {/* tab mode */}
        {(layoutMode === 'tabs' || !terminalVisible) && (
          <>
            {terminalVisible && (
              <div
                className="absolute inset-0"
                style={{ 
                  display: (!globalTerminalManaged && activePane === TERMINAL_TAB_KEY) ? 'block' : 'none', 
                  backgroundColor: colors.bgPrimary 
                }}
              >
                <TerminalPanel onTerminalSessionChange={onTerminalSessionChange} />
              </div>
            )}

            <div
              className="absolute inset-0"
              style={{ 
                display: (!globalTerminalManaged && activePane !== TERMINAL_TAB_KEY) || globalTerminalManaged || layoutMode === 'tabs' ? 'block' : 'none' 
              }}
            >
              {activeTab === 'sftp' ? <SFTPWorkspace /> : <FileWorkspace />}
            </div>
          </>
        )}

        {/* flat split mode(above/below) */}
        {layoutMode === 'split-horizontal' && terminalVisible && (
          <div className="absolute inset-0 flex flex-col">
            <div className="flex-1 min-h-0">
              {activeTab === 'sftp' ? <SFTPWorkspace /> : <FileWorkspace />}
            </div>
            <div 
              className="h-1 cursor-row-resize hover:bg-blue-500/30" 
              style={{ backgroundColor: isResizing ? colors.accent : 'transparent' }}
              onMouseDown={() => setIsResizing(true)}
            />
            <div style={{ height: terminalPanelSize, minHeight: 150 }}>
              <TerminalPanel onTerminalSessionChange={onTerminalSessionChange} />
            </div>
          </div>
        )}

        {/* vertical straight split mode(left/right) */}
        {layoutMode === 'split-vertical' && terminalVisible && (
          <div className="absolute inset-0 flex flex-row">
            <div className="flex-1 min-w-0">
              {activeTab === 'sftp' ? <SFTPWorkspace /> : <FileWorkspace />}
            </div>
            <div 
              className="w-1 cursor-col-resize hover:bg-blue-500/30" 
              style={{ backgroundColor: isResizing ? colors.accent : 'transparent' }}
              onMouseDown={() => setIsResizing(true)}
            />
            <div style={{ width: terminalPanelSize, minWidth: 250 }}>
              <TerminalPanel onTerminalSessionChange={onTerminalSessionChange} />
            </div>
          </div>
        )}
      </div>

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
              if (!globalTerminalManaged && terminalVisible) {
                setActivePane(TERMINAL_TAB_KEY)
              }
            }}
          >
            Close all
          </button>
        </div>
      )}
    </div>
  )
}
