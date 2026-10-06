import { useCallback, useMemo, useState } from 'react'
import { useThemeStore } from '../stores/themeStore'
import { useFileExplorerStore, formatFileSize, isDiffTab } from '../stores/fileExplorerStore'
import { DiffFileTab } from '../stores/fileExplorerStore'
import { useAiPatchStore } from '../stores/aiPatchStore'
import { useSshAgentStore } from '../stores/sshAgentStore'
import Editor from '@monaco-editor/react'
import { DiffEditor } from '@monaco-editor/react'

/** Diff view image component: left/right compare before / after */
function DiffEditorView({ activeTab }: { activeTab: DiffFileTab }) {
  const { colors, currentTheme } = useThemeStore()

  return (
    <div className="h-full flex flex-col">
      <div className="flex items-center justify-between px-3 py-1 shrink-0 border-b" style={{ backgroundColor: colors.bgSecondary, borderColor: colors.border }}>
        <div className="flex items-center gap-2">
          <span className="text-xs font-medium" style={{ color: colors.text }}>
            🔀 Diff: {activeTab.name}
          </span>
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

export function FileWorkspace() {
  const { colors, currentTheme } = useThemeStore()
  const { openTabs, activeTabKey, updateFileContent, saveFile, loadMoreContent, restoreFileContent } = useFileExplorerStore()
  const previews = useAiPatchStore((state) => state.previews)
  const removePreview = useAiPatchStore((state) => state.removePreview)
  const [hasSelection, setHasSelection] = useState(false)
  const [isEditing, setIsEditing] = useState(false)
  const [useSudo, setUseSudo] = useState(false)

  const activeTab = useMemo(
    () => openTabs.find((tab) => tab.key === activeTabKey) ?? null,
    [openTabs, activeTabKey],
  )
  // Narrowed: only non-diff file tabs (OpenFileTab)
  const fileTab = useMemo(
    () => activeTab && !isDiffTab(activeTab) ? activeTab : null,
    [activeTab],
  )
  const activePreview = useMemo(
    () => fileTab
      ? previews.find((item) => item.target === 'remote' && item.connectionId === fileTab.connectionId && item.path === fileTab.path) ?? null
      : null,
    [previews, fileTab],
  )

  // simple extension name push break language
  const getLanguage = (filename: string) => {
    const ext = filename.split('.').pop()?.toLowerCase()
    switch (ext) {
      case 'js': case 'jsx': return 'javascript'
      case 'ts': case 'tsx': return 'typescript'
      case 'json': return 'json'
      case 'html': return 'html'
      case 'css': return 'css'
      case 'md': return 'markdown'
      case 'py': return 'python'
      case 'java': return 'java'
      case 'class': return 'java'
      case 'sh': case 'bash': return 'shell'
      case 'yml': case 'yaml': return 'yaml'
      case 'xml': return 'xml'
      case 'sql': return 'sql'
      default: return 'plaintext'
    }
  }

  const handleChange = useCallback((value: string | undefined) => {
    if (activeTabKey && value !== undefined) {
      updateFileContent(activeTabKey, value)
    }
  }, [activeTabKey, updateFileContent])

  const handleSave = useCallback(async () => {
    if (activeTabKey) {
      const success = await saveFile(activeTabKey, useSudo)
      if (!success) {
        if (useSudo) {
          alert('Save failed even with sudo. Check file permissions.')
        } else {
          if (confirm('Save failed: Permission denied\nRetry with sudo?')) {
            setUseSudo(true)
            // use sudo retry
            const retrySuccess = await saveFile(activeTabKey, true)
            if (retrySuccess) {
              setIsEditing(false)
              setUseSudo(false)
            }
          }
        }
      } else {
        setIsEditing(false)
        setUseSudo(false)
      }
    }
  }, [activeTabKey, saveFile, useSudo])

  const handleStartEdit = () => {
    setIsEditing(true)
  }

  const handleCancelEdit = () => {
    if (fileTab?.modified) {
      if (confirm('File has unsaved changes. Discard?')) {
        setIsEditing(false)
      }
    } else {
      setIsEditing(false)
    }
  }

  return (
    <div className="h-full flex flex-col min-w-0" style={{ backgroundColor: colors.bgTertiary }}>
      <div className="flex-1 overflow-hidden relative">
        {/* Diff Tab: use Monaco DiffEditor compare before/after */}
        {isDiffTab(activeTab) ? (
          <DiffEditorView activeTab={activeTab} />
        ) : !fileTab ? (
          <div className="h-full flex items-center justify-center">
            <div className="text-center">
              <p className="text-sm" style={{ color: colors.textSecondary }}>No open file</p>
              <p className="text-xs mt-1" style={{ color: colors.textDim }}>Click a file in the tree on the left to view it</p>
            </div>
          </div>
        ) : fileTab.loading ? (
          <div className="h-full flex items-center justify-center">
            <p className="text-sm" style={{ color: colors.textSecondary }}>Loading file...</p>
          </div>
        ) : fileTab.error ? (
          <div className="h-full flex items-center justify-center">
            <p className="text-sm" style={{ color: colors.red }}>{fileTab.error}</p>
          </div>
        ) : (
          <div className="h-full flex flex-col">
            {fileTab.binary && (
              <div className="text-xs px-3 py-2 shrink-0 border-b" style={{ backgroundColor: `${colors.yellow}10`, color: colors.yellow, borderColor: colors.border }}>
                This file looks binary and cannot be previewed.
              </div>
            )}
            {!fileTab.binary && fileTab.parsedType === 'java-class' && (
              <div className="text-xs px-3 py-2 shrink-0 border-b flex items-center gap-1.5" style={{ backgroundColor: `${colors.accent}10`, color: colors.accent, borderColor: colors.border }}>
                <span>☕</span>
                <span>class bytecode parsed as a structured view (read-only) · size {formatFileSize(fileTab.size ?? 0)}</span>
              </div>
            )}
            {fileTab.truncated && !fileTab.binary && (
              <div className="text-xs px-3 py-2 shrink-0 border-b flex items-center justify-between" style={{ backgroundColor: `${colors.yellow}10`, color: colors.yellow, borderColor: colors.border }}>
                <span>File too large ({formatFileSize(fileTab.content.length)}/{formatFileSize(fileTab.size ?? 0)}); showing the first {formatFileSize(fileTab.content.length)}.</span>
                <div className="flex items-center gap-2 shrink-0">
                  <button
                    onClick={() => loadMoreContent(fileTab.key)}
                    disabled={fileTab.loading}
                    className="px-2 py-0.5 rounded text-[11px] transition-colors hover:scale-105"
                    style={{ backgroundColor: colors.yellow, color: '#000' }}
                  >
                    {fileTab.loading ? 'Loading...' : 'Load more'}
                  </button>
                </div>
              </div>
            )}
            {/* save button and fix edit mark(parse view image read-only, not display edit in port) */}
            {!fileTab.binary && !fileTab.parsedType && (
              <div className="flex items-center justify-between px-3 py-1 shrink-0 border-b" style={{ backgroundColor: colors.bgSecondary, borderColor: colors.border }}>
                <div className="flex items-center gap-2">
                  {fileTab.modified && (
                    <span className="text-xs" style={{ color: colors.yellow }}>● Modified</span>
                  )}
                  {useSudo && (
                    <span className="text-xs" style={{ color: colors.accent }}>⚡ sudo mode</span>
                  )}
                </div>
                <div className="flex items-center gap-2">
                  {!isEditing ? (
                    <button
                      onClick={handleStartEdit}
                      className="flex items-center gap-1 px-3 py-1 rounded text-xs transition-colors"
                      style={{
                        backgroundColor: colors.accent,
                        color: '#fff',
                      }}
                    >
                      <svg className="w-3.5 h-3.5" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                        <path d="M11 4H4a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7"/>
                        <path d="M18.5 2.5a2.121 2.121 0 0 1 3 3L12 15l-4 1 1-4 9.5-9.5z"/>
                      </svg>
                      Edit
                    </button>
                  ) : (
                    <>
                      <button
                        onClick={handleCancelEdit}
                        className="flex items-center gap-1 px-3 py-1 rounded text-xs transition-colors"
                        style={{
                          color: colors.textDim,
                        }}
                      >
                        Cancel
                      </button>
                      <button
                        onClick={handleSave}
                        disabled={!fileTab.modified}
                        className="flex items-center gap-1 px-3 py-1 rounded text-xs transition-colors"
                        style={{
                          backgroundColor: fileTab.modified ? colors.accent : 'transparent',
                          color: fileTab.modified ? '#fff' : colors.textDim,
                          cursor: fileTab.modified ? 'pointer' : 'not-allowed',
                        }}
                      >
                        <svg className="w-3.5 h-3.5" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                          <path d="M19 21H5a2 2 0 01-2-2V7a2 2 0 012-2h11l5 5v11a2 2 0 01-2 2z"/>
                          <polyline points="17 21 17 13 7 13 7 21"/>
                          <polyline points="7 3 7 8 15 8"/>
                        </svg>
                        Save
                      </button>
                    </>
                  )}
                </div>
              </div>
            )}
            {activePreview && (
              <div className="border-b px-3 py-2 text-xs shrink-0" style={{ backgroundColor: `${colors.accent}10`, borderColor: colors.border }}>
                <div className="flex items-center justify-between gap-3">
                  <div className="min-w-0">
                    <div style={{ color: colors.text }}>
                      AI edited this remote file: +{activePreview.addedLines} / -{activePreview.removedLines} lines
                    </div>
                    <div className="mt-1 truncate" style={{ color: colors.textDim }}>
                      {activePreview.path}
                    </div>
                  </div>
                  <div className="flex items-center gap-2 shrink-0">
                    <button
                      onClick={async () => {
                        const success = await restoreFileContent(activePreview.connectionId!, activePreview.path, activePreview.beforeContent)
                        if (success) {
                          removePreview(activePreview.id)
                        }
                      }}
                      className="px-2 py-1 rounded text-[11px]"
                      style={{ backgroundColor: `${colors.red}15`, color: colors.red }}
                    >
                      Revert
                    </button>
                    <button
                      onClick={() => removePreview(activePreview.id)}
                      className="px-2 py-1 rounded text-[11px]"
                      style={{ backgroundColor: colors.accent, color: '#fff' }}
                    >
                      Accept
                    </button>
                  </div>
                </div>
                <details className="mt-2">
                  <summary className="cursor-pointer select-none" style={{ color: colors.textSecondary }}>
                    View before/after
                  </summary>
                  <div className="grid grid-cols-2 gap-2 mt-2">
                    <pre className="text-[11px] p-2 rounded overflow-auto max-h-48" style={{ backgroundColor: colors.bgPrimary, color: colors.text }}>
                      {activePreview.beforeContent}
                    </pre>
                    <pre className="text-[11px] p-2 rounded overflow-auto max-h-48" style={{ backgroundColor: colors.bgPrimary, color: colors.text }}>
                      {activePreview.afterContent}
                    </pre>
                  </div>
                </details>
              </div>
            )}
            {!fileTab.binary && (
              <div className="flex-1 min-h-0 relative">
                {hasSelection && isEditing && (
                  <div className="absolute top-2 right-6 z-10">
                    <button
                      onClick={() => {
                        // @ts-ignore
                        const editor = window.__activeMonacoEditor
                        if (editor) {
                          editor.getAction('add-to-ai-chat')?.run()
                        }
                      }}
                      className="flex items-center gap-1.5 px-3 py-1.5 rounded shadow-lg border transition-all hover:scale-105"
                      style={{
                        backgroundColor: colors.accent,
                        borderColor: colors.border,
                        color: '#fff',
                      }}
                    >
                      <svg className="w-3.5 h-3.5" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                        <line x1="12" y1="5" x2="12" y2="19"></line>
                        <line x1="5" y1="12" x2="19" y2="12"></line>
                      </svg>
                      <span className="text-[11px] font-medium">Send selection to AI</span>
                    </button>
                  </div>
                )}
                <Editor
                  key={`${fileTab.key}:${fileTab.contentVersion ?? 0}`}
                  height="100%"
                  language={getLanguage(fileTab.name)}
                  theme={currentTheme === 'light' ? 'vs-light' : 'vs-dark'}
                  value={fileTab.content || ''}
                  path={fileTab.path}
                  onChange={(value) => {
                    // start end register onChange ensure value prop can sync to editor
                    if (isEditing && value !== undefined) {
                      handleChange(value)
                    }
                  }}
                  onMount={(editor) => {
                    // @ts-ignore
                    window.__activeMonacoEditor = editor

                    editor.onDidChangeCursorSelection((e) => {
                      if (!e.selection.isEmpty()) {
                        setHasSelection(true)
                      } else {
                        setHasSelection(false)
                      }
                    })

                    editor.addAction({
                      id: 'add-to-ai-chat',
                      label: 'Add to AI chat',
                      contextMenuGroupId: '1_modification',
                      contextMenuOrder: 1,
                      run: (ed) => {
                        const selection = ed.getSelection()
                        if (!selection) return
                        const text = ed.getModel()?.getValueInRange(selection)
                        
                        const currentActiveTabKey = useFileExplorerStore.getState().activeTabKey
                        const currentActiveTab = useFileExplorerStore.getState().openTabs.find(t => t.key === currentActiveTabKey)
                        if (!currentActiveTab || isDiffTab(currentActiveTab)) return

                        if (text && text.trim()) {
                          useSshAgentStore.getState().addInputTag({
                            label: 'Selected text',
                            fullContent: `File: ${currentActiveTab.path}\nSelected code/text:\n\`\`\`\n${text}\n\`\`\``,
                            type: 'custom'
                          })
                        } else {
                          // no has selected text when, add whole file
                          useSshAgentStore.getState().addInputTag({
                            label: `File: ${currentActiveTab.name}`,
                            fullContent: `File path: ${currentActiveTab.path}\n\n${currentActiveTab.content}`,
                            type: 'file'
                          })
                        }
                      }
                    })
                  }}
                  options={{
                    readOnly: !isEditing,
                    fontSize: 13,
                    fontFamily: "'JetBrains Mono', 'Fira Code', Consolas, monospace",
                    minimap: { enabled: true },
                    scrollBeyondLastLine: false,
                    automaticLayout: true,
                    wordWrap: 'on',
                    lineNumbers: 'on',
                    renderWhitespace: 'selection',
                    padding: { top: 16 },
                  }}
                />
              </div>
            )}
          </div>
        )}
      </div>
    </div>
  )
}
