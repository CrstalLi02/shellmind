import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import Editor from '@monaco-editor/react'
import { DiffEditor } from '@monaco-editor/react'
import { useThemeStore } from '../stores/themeStore'
import { useLocalFileStore, isLocalDiffTab, type LocalDiffTab, type LocalOpenTab } from '../stores/localFileStore'
import { useAiPatchStore } from '../stores/aiPatchStore'
import { useSshAgentStore } from '../stores/sshAgentStore'

/** Diff view image component: left/right compare before / after(local file) */
function LocalDiffEditorView({ activeTab, onAccept, onRestore }: { activeTab: LocalDiffTab; onAccept: () => void; onRestore: () => void }) {
  const { colors, currentTheme } = useThemeStore()
  const preview = useAiPatchStore((state) => state.previews.find((p) => p.id === activeTab.previewId))
  const openSourceFile = useLocalFileStore((s) => s.openFile)

  // click file name open source file(switch to general through edit tab)
  const handleOpenSource = useCallback(async (e: React.MouseEvent) => {
    e.preventDefault()
    e.stopPropagation()
    if (!preview) return
    await openSourceFile(preview.path)
  }, [preview, openSourceFile])

  return (
    <div className="h-full flex flex-col">
      <div className="flex items-center justify-between px-3 py-1 shrink-0 border-b" style={{ backgroundColor: colors.bgSecondary, borderColor: colors.border }}>
        <div className="flex items-center gap-2">
          <span className="text-xs font-medium" style={{ color: colors.text }}>
            🔀 Diff:{' '}
          </span>
          {/* ok click file name - jump turn to source file */}
          <span
            onClick={handleOpenSource}
            className="text-xs font-medium underline cursor-pointer hover:opacity-70 transition-opacity"
            style={{ color: colors.accent }}
            title={`Open source file: ${activeTab.path}`}
          >
            {activeTab.name}
          </span>
          <span className="text-[10px] font-mono" style={{ color: '#22c55e' }}>+{activeTab.addedLines}</span>
          <span className="text-[10px] font-mono" style={{ color: '#ef4444' }}>-{activeTab.removedLines}</span>
        </div>
        <div className="flex items-center gap-3">
          <span className="text-[10px]" style={{ color: colors.textDim }}>{activeTab.path}</span>
          {preview && (
            <div className="flex items-center gap-2">
              <button
                onClick={onRestore}
                className="px-2 py-1 rounded text-[11px]"
                style={{ backgroundColor: `${colors.red}15`, color: colors.red }}
              >
                Revert
              </button>
              <button
                onClick={onAccept}
                className="px-2 py-1 rounded text-[11px]"
                style={{ backgroundColor: colors.accent, color: '#fff' }}
              >
                Accept
              </button>
            </div>
          )}
        </div>
      </div>
      <div className="flex-1 min-h-0">
        <DiffEditor
          height="100%"
          language={activeTab.language}
          theme={currentTheme === 'light' ? 'vs-light' : 'vs-dark'}
          original={activeTab.beforeContent}
          modified={activeTab.afterContent}
          options={{
            readOnly: true,
            fontSize: 13,
            fontFamily: "'JetBrains Mono', 'Fira Code', Consolas, monospace",
            minimap: { enabled: false },
            scrollBeyondLastLine: false,
            automaticLayout: true,
            wordWrap: 'on',
            renderSideBySide: true,
          }}
        />
      </div>
    </div>
  )
}

export function LocalFileWorkspace() {
  const { colors, currentTheme } = useThemeStore()
  const { openTabs, activeTabKey, updateFileContent, saveFile, setActiveTab, closeTab, restoreFileContent, closeOtherTabs, closeAllTabs, closeTabsToLeft, closeTabsToRight } = useLocalFileStore()
  const removePreview = useAiPatchStore((state) => state.removePreview)
  const [hasSelection, setHasSelection] = useState(false)
  const [tabContextMenu, setTabContextMenu] = useState<{ x: number; y: number; tabKey: string } | null>(null)
  const [tabDropdownOpen, setTabDropdownOpen] = useState(false)
  const tabsScrollRef = useRef<HTMLDivElement>(null)

  // click external / Esc close tab context menu
  useEffect(() => {
    if (!tabContextMenu) return
    const close = () => setTabContextMenu(null)
    const onKeyDown = (e: KeyboardEvent) => {
      if (e.key === 'Escape') setTabContextMenu(null)
    }
    document.addEventListener('click', close)
    document.addEventListener('keydown', onKeyDown)
    return () => {
      document.removeEventListener('click', close)
      document.removeEventListener('keydown', onKeyDown)
    }
  }, [tabContextMenu])

  // click outside to close the tab dropdown
  useEffect(() => {
    if (!tabDropdownOpen) return
    const close = (e: MouseEvent) => {
      if (!(e.target instanceof Element) || !e.target.closest('[data-local-tab-dropdown]')) {
        setTabDropdownOpen(false)
      }
    }
    document.addEventListener('pointerdown', close)
    return () => document.removeEventListener('pointerdown', close)
  }, [tabDropdownOpen])

  // scroll the active tab into view when it changes
  useEffect(() => {
    if (!activeTabKey || !tabsScrollRef.current) return
    const el = tabsScrollRef.current.querySelector(`[data-local-tab-key="${CSS.escape(activeTabKey)}"]`)
    el?.scrollIntoView({ block: 'nearest', inline: 'nearest' })
  }, [activeTabKey])

  const activeTab = useMemo(
    () => openTabs.find((tab) => tab.key === activeTabKey) ?? null,
    [openTabs, activeTabKey],
  )

  const isDiffTab = isLocalDiffTab(activeTab)
  const fileTab = isDiffTab ? null : (activeTab as LocalOpenTab | null)
  const diffTab = isDiffTab ? (activeTab as LocalDiffTab) : null

  // Diff Tab accept/ still raw callback - must be called before the conditional return(hooks rule)
  const handleAcceptDiff = useCallback(() => {
    if (!diffTab) return
    removePreview(diffTab.previewId)
    closeTab(diffTab.key)
  }, [diffTab, removePreview, closeTab])

  const handleRestoreDiff = useCallback(async () => {
    if (!diffTab) return
    const preview = useAiPatchStore.getState().previews.find((p) => p.id === diffTab.previewId)
    if (!preview) return
    const success = await restoreFileContent(preview.path, preview.beforeContent)
    if (success) {
      removePreview(diffTab.previewId)
      closeTab(diffTab.key)
    }
  }, [diffTab, restoreFileContent, removePreview, closeTab])

  const handleChange = useCallback(
    (value: string | undefined) => {
      if (activeTabKey && value !== undefined) {
        updateFileContent(activeTabKey, value)
      }
    },
    [activeTabKey, updateFileContent],
  )

  const handleSave = useCallback(async () => {
    if (activeTabKey) {
      const currentTab = useLocalFileStore.getState().openTabs.find((t) => t.key === activeTabKey)
      // parse view image(like class byte code shape) read-only, not trigger save
      if (currentTab && !isLocalDiffTab(currentTab) && currentTab.parsedType) return
      const success = await saveFile(activeTabKey)
      if (!success) {
        alert('Save failed; check file permissions')
      }
    }
  }, [activeTabKey, saveFile])

  const handleEditorMount = useCallback(
    (editor: any) => {
      // @ts-ignore
      window.__activeMonacoEditor = editor

      editor.addCommand(
        // 2048 = Cmd/Ctrl modifier, 49 = 'S' key
        2048 | 49,
        () => {
          void handleSave()
        },
      )

      editor.onDidChangeCursorSelection((e: any) => {
        setHasSelection(!e.selection.isEmpty())
      })

      // context menu: add to AI chat
      editor.addAction({
        id: 'add-to-ai-chat',
        label: 'Add to AI chat',
        contextMenuGroupId: '1_modification',
        contextMenuOrder: 1,
        run: (ed: any) => {
          const selection = ed.getSelection()
          if (!selection) return
          const text = ed.getModel()?.getValueInRange(selection)

          const currentTab = useLocalFileStore.getState().openTabs.find(
            (t) => !isLocalDiffTab(t) && t.key === useLocalFileStore.getState().activeTabKey,
          ) as LocalOpenTab | undefined
          if (!currentTab) return

          if (text && text.trim()) {
            useSshAgentStore.getState().addInputTag({
              label: `Selected: ${currentTab.name}`,
              fullContent: `Local file: ${currentTab.path}\nSelected code/text:\n\`\`\`\n${text}\n\`\`\``,
              type: 'terminal-selection',
            })
          } else {
            useSshAgentStore.getState().addInputTag({
              label: `File: ${currentTab.name}`,
              fullContent: `Local file: ${currentTab.path}\n\n\`\`\`\n${currentTab.content}\n\`\`\``,
              type: 'file',
            })
          }
        },
      })
    },
    [handleSave],
  )

  // ── tab bar(shared) ──
  const contextTabIndex = tabContextMenu ? openTabs.findIndex((t) => t.key === tabContextMenu.tabKey) : -1
  const tabBar = (
    <div className="relative h-9 border-b flex items-center pl-2 flex-shrink-0" style={{ backgroundColor: colors.bgSecondary, borderColor: colors.border }}>
      <div ref={tabsScrollRef} className="flex-1 min-w-0 h-full flex items-center gap-1 overflow-x-auto no-scrollbar">
        {openTabs.map((tab) => {
          const isActive = activeTabKey === tab.key
          const isDiff = isLocalDiffTab(tab)
          return (
            <button
              key={tab.key}
              data-local-tab-key={tab.key}
              onClick={() => setActiveTab(tab.key)}
              onContextMenu={(e) => {
                e.preventDefault()
                e.stopPropagation()
                setTabDropdownOpen(false)
                setTabContextMenu({ x: e.clientX, y: e.clientY, tabKey: tab.key })
              }}
              className="group h-7 px-3 rounded-md flex items-center gap-2 text-xs max-w-[200px] flex-shrink-0 transition-colors"
              style={{
                color: isActive ? colors.text : colors.textSecondary,
                backgroundColor: isActive ? colors.bgPrimary : 'transparent',
                border: `1px solid ${isActive ? colors.border : 'transparent'}`,
              }}
            >
              {isDiff && <span className="text-[10px] flex-shrink-0" title="Diff">🔀</span>}
              {!isDiff && tab.modified && (
                <span className="w-1.5 h-1.5 rounded-full flex-shrink-0" style={{ backgroundColor: colors.yellow }} />
              )}
              <span className="truncate">{tab.name}</span>
              <span
                onClick={(e) => { e.stopPropagation(); closeTab(tab.key) }}
                className="opacity-0 group-hover:opacity-60 hover:!opacity-100 flex items-center justify-center w-4 h-4 rounded-sm"
              >
                <svg className="w-3 h-3" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                  <line x1="18" y1="6" x2="6" y2="18"></line>
                  <line x1="6" y1="6" x2="18" y2="18"></line>
                </svg>
              </span>
            </button>
          )
        })}
      </div>

      {/* expand all tab(with count) */}
      {openTabs.length > 0 && (
        <div data-local-tab-dropdown className="relative flex-shrink-0 flex items-center pr-1 pl-1 border-l" style={{ borderColor: colors.border }}>
          <button
            onClick={(e) => {
              e.stopPropagation()
              setTabContextMenu(null)
              setTabDropdownOpen(!tabDropdownOpen)
            }}
            className="h-7 px-2 rounded-md flex items-center gap-1 text-xs hover:bg-white/5 transition-colors"
            style={{ color: colors.textSecondary }}
            title="Expand all open files"
          >
            <span className="font-mono text-[10px] bg-black/10 px-1 rounded">{openTabs.length}</span>
            <svg
              className="w-3.5 h-3.5 transition-transform"
              style={{ transform: tabDropdownOpen ? 'rotate(180deg)' : '' }}
              viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2"
            >
              <polyline points="6 9 12 15 18 9"></polyline>
            </svg>
          </button>

          {tabDropdownOpen && (
            <div
              className="absolute top-full right-0 mt-1 w-64 rounded-lg border py-1 z-[100] max-h-80 overflow-y-auto shadow-xl"
              style={{ backgroundColor: colors.bgSecondary, borderColor: colors.border }}
            >
              <div className="px-3 py-1.5 text-[11px] uppercase font-medium tracking-wider border-b mb-1" style={{ color: colors.textDim, borderColor: colors.border }}>
                Open files({openTabs.length})
              </div>
              {openTabs.map((tab) => {
                const isActive = activeTabKey === tab.key
                const isDiff = isLocalDiffTab(tab)
                return (
                  <button
                    key={`dropdown-${tab.key}`}
                    className="w-full text-left px-3 py-1.5 text-xs flex items-center justify-between gap-2 hover:bg-white/5 transition-colors"
                    style={{ color: isActive ? colors.accent : colors.text }}
                    title={tab.path}
                    onClick={() => {
                      setActiveTab(tab.key)
                      setTabDropdownOpen(false)
                    }}
                  >
                    <span className="flex items-center gap-1.5 min-w-0">
                      {isDiff && <span className="text-[10px] flex-shrink-0">🔀</span>}
                      {!isDiff && tab.modified && (
                        <span className="w-1.5 h-1.5 rounded-full flex-shrink-0" style={{ backgroundColor: colors.yellow }} />
                      )}
                      <span className="truncate">{tab.name}</span>
                    </span>
                    <span
                      className="opacity-60 hover:opacity-100 flex-shrink-0 flex items-center justify-center w-4 h-4 rounded-sm"
                      onClick={(e) => {
                        e.stopPropagation()
                        closeTab(tab.key)
                      }}
                    >
                      <svg className="w-3 h-3" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                        <line x1="18" y1="6" x2="6" y2="18"></line>
                        <line x1="6" y1="6" x2="18" y2="18"></line>
                      </svg>
                    </span>
                  </button>
                )
              })}
            </div>
          )}
        </div>
      )}

      {/* tab context menu */}
      {tabContextMenu && contextTabIndex >= 0 && (
        <div
          className="fixed z-[100] w-44 rounded-md shadow-lg border py-1"
          style={{
            left: Math.min(tabContextMenu.x, window.innerWidth - 190),
            top: Math.min(tabContextMenu.y, window.innerHeight - 180),
            backgroundColor: colors.bgSecondary,
            borderColor: colors.border,
          }}
          onClick={(e) => e.stopPropagation()}
        >
          <button
            className="w-full text-left px-3 py-1.5 text-xs hover:bg-white/5 transition-colors"
            style={{ color: colors.text }}
            onClick={() => { closeTab(tabContextMenu.tabKey); setTabContextMenu(null) }}
          >
            Close
          </button>
          <button
            disabled={contextTabIndex === 0}
            className="w-full text-left px-3 py-1.5 text-xs hover:bg-white/5 transition-colors disabled:opacity-40 disabled:hover:bg-transparent"
            style={{ color: colors.text }}
            onClick={() => { closeTabsToLeft(tabContextMenu.tabKey); setTabContextMenu(null) }}
          >
            Close to the left
          </button>
          <button
            disabled={contextTabIndex === openTabs.length - 1}
            className="w-full text-left px-3 py-1.5 text-xs hover:bg-white/5 transition-colors disabled:opacity-40 disabled:hover:bg-transparent"
            style={{ color: colors.text }}
            onClick={() => { closeTabsToRight(tabContextMenu.tabKey); setTabContextMenu(null) }}
          >
            Close to the right
          </button>
          <button
            disabled={openTabs.length <= 1}
            className="w-full text-left px-3 py-1.5 text-xs hover:bg-white/5 transition-colors disabled:opacity-40 disabled:hover:bg-transparent"
            style={{ color: colors.text }}
            onClick={() => { closeOtherTabs(tabContextMenu.tabKey); setTabContextMenu(null) }}
          >
            Close others
          </button>
          <div className="my-1 mx-2" style={{ height: 1, backgroundColor: colors.border }} />
          <button
            className="w-full text-left px-3 py-1.5 text-xs hover:bg-white/5 transition-colors"
            style={{ color: colors.text }}
            onClick={() => { closeAllTabs(); setTabContextMenu(null) }}
          >
            Close all
          </button>
        </div>
      )}
    </div>
  )

  // ── render branch ──
  // all hooks already at upper call complete finish, so down only do condition render

  // 1. Diff Tab
  if (isDiffTab && activeTab) {
    return (
      <div className="h-full flex flex-col min-w-0" style={{ backgroundColor: colors.bgTertiary }}>
        {tabBar}
        <LocalDiffEditorView activeTab={activeTab} onAccept={handleAcceptDiff} onRestore={handleRestoreDiff} />
      </div>
    )
  }

  // 2. idle tab
  if (!fileTab) {
    return (
      <div className="h-full flex items-center justify-center" style={{ backgroundColor: colors.bgTertiary }}>
        <div className="text-center">
          <svg className="w-16 h-16 mx-auto mb-4 opacity-20" viewBox="0 0 24 24" fill="none" stroke={colors.textDim} strokeWidth="1.5">
            <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"></path>
            <polyline points="14 2 14 8 20 8"></polyline>
          </svg>
          <p className="text-sm" style={{ color: colors.textSecondary }}>Open a file to start editing</p>
          <p className="text-xs mt-1" style={{ color: colors.textDim }}>Click a file in the tree on the left</p>
        </div>
      </div>
    )
  }

  // 3. loading
  if (fileTab.loading) {
    return (
      <div className="h-full flex items-center justify-center" style={{ backgroundColor: colors.bgTertiary }}>
        <p className="text-sm" style={{ color: colors.textSecondary }}>Loading...</p>
      </div>
    )
  }

  // 4. error
  if (fileTab.error) {
    return (
      <div className="h-full flex items-center justify-center" style={{ backgroundColor: colors.bgTertiary }}>
        <p className="text-sm" style={{ color: colors.red }}>{fileTab.error}</p>
      </div>
    )
  }

  // 5. align always file edit
  return (
    <div className="h-full flex flex-col min-w-0" style={{ backgroundColor: colors.bgTertiary }}>
      {tabBar}

      {/* parse view image hint(class byte code equal read-only content) */}
      {fileTab.parsedType === 'java-class' && (
        <div className="text-xs px-3 py-2 shrink-0 border-b flex items-center gap-1.5" style={{ backgroundColor: `${colors.accent}10`, color: colors.accent, borderColor: colors.border }}>
          <span>☕</span>
          <span>class bytecode parsed as a structured view (read-only)</span>
        </div>
      )}

      {/* toolbar */}
      <div className="flex items-center justify-between px-3 py-1 shrink-0 border-b" style={{ backgroundColor: colors.bgSecondary, borderColor: colors.border }}>
        <div className="flex items-center gap-2 min-w-0">
          <span className="text-xs truncate" style={{ color: colors.textDim }}>{fileTab.path}</span>
          {fileTab.modified && (
            <span className="text-xs flex-shrink-0" style={{ color: colors.yellow }}>● Modified</span>
          )}
        </div>
        <div className="flex items-center gap-2 shrink-0">
          {hasSelection && (
            <button
              onClick={() => {
                // @ts-ignore
                const editor = window.__activeMonacoEditor
                if (editor) {
                  editor.getAction('add-to-ai-chat')?.run()
                }
              }}
              className="flex items-center gap-1 px-2 py-1 rounded text-[11px] transition-colors"
              style={{ backgroundColor: colors.accent, color: '#fff' }}
            >
              <svg className="w-3 h-3" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                <line x1="12" y1="5" x2="12" y2="19"></line>
                <line x1="5" y1="12" x2="19" y2="12"></line>
              </svg>
              Send to AI
            </button>
          )}
        </div>
      </div>

      {/* Monaco editor */}
      <div className="flex-1 min-h-0 relative">
        <Editor
          key={`${fileTab.key}:${fileTab.contentVersion ?? 0}`}
          height="100%"
          language={fileTab.language}
          theme={currentTheme === 'light' ? 'vs-light' : 'vs-dark'}
          value={fileTab.content}
          path={fileTab.path}
          onChange={handleChange}
          onMount={handleEditorMount}
          options={{
            readOnly: !!fileTab.parsedType,
            fontSize: 13,
            fontFamily: "'JetBrains Mono', 'Fira Code', Consolas, monospace",
            minimap: { enabled: true },
            scrollBeyondLastLine: false,
            automaticLayout: true,
            wordWrap: 'on',
            lineNumbers: 'on',
            renderWhitespace: 'selection',
            padding: { top: 16 },
            tabSize: 2,
            formatOnPaste: true,
            formatOnType: true,
          }}
        />
      </div>
    </div>
  )
}
