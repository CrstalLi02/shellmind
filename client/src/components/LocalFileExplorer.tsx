import { useEffect, useRef, useState, type ReactNode } from 'react'
import { invoke } from '@tauri-apps/api/core'
import { exists, mkdir, remove, rename, writeTextFile } from '@tauri-apps/plugin-fs'
import { openPath } from '@tauri-apps/plugin-opener'
import { useThemeStore } from '../stores/themeStore'
import {
  useLocalFileStore,
  type LocalFileNode,
  type LocalAnyTab,
  type LocalOpenTab,
  isLocalDiffTab,
} from '../stores/localFileStore'
import { useSshAgentStore } from '../stores/sshAgentStore'
import type { ThemeColors } from '../stores/themeStore'

/** get parent directory path */
function parentOf(p: string): string {
  const idx = p.lastIndexOf('/')
  return idx > 0 ? p.slice(0, idx) : '/'
}

function joinPath(parent: string, child: string): string {
  return parent.endsWith('/') ? `${parent}${child}` : `${parent}/${child}`
}

/** shell form lead number package wrap(injection-safe/ space) */
function shellQuote(p: string): string {
  return `'${p.replace(/'/g, `'\\''`)}'`
}

interface ShellResult {
  success: boolean
  stdout: string
  stderr: string
}

async function runShell(command: string, cwd: string): Promise<ShellResult> {
  return invoke<ShellResult>('execute_shell_cmd', {
    command,
    cwd,
    timeoutMs: 30000,
    autoBackground: false,
  })
}

/** rename/ move/ after delete, fix paths of already open tabs(newPath as null table show delete → close relative off tab) */
function remapOpenTabs(oldPath: string, newPath: string | null) {
  useLocalFileStore.setState((state) => {
    if (newPath === null) {
      const openTabs = state.openTabs.filter(
        (t) => !(t.path === oldPath || t.path.startsWith(oldPath + '/')),
      )
      const activeTabKey =
        state.activeTabKey && openTabs.some((t) => t.key === state.activeTabKey)
          ? state.activeTabKey
          : openTabs.length
            ? openTabs[openTabs.length - 1].key
            : null
      return { openTabs, activeTabKey }
    }

    const newName = newPath.split('/').pop() || newPath
    const openTabs = state.openTabs.map((t): LocalAnyTab => {
      if (t.path === oldPath) {
        const renamed = { ...t, path: newPath, name: newName }
        if (!isLocalDiffTab(t) && t.key === oldPath) renamed.key = newPath
        return renamed
      }
      if (t.path.startsWith(oldPath + '/')) {
        const remappedPath = newPath + t.path.slice(oldPath.length)
        const remapped = { ...t, path: remappedPath }
        if (!isLocalDiffTab(t) && t.key === t.path) remapped.key = remappedPath
        return remapped
      }
      return t
    })

    const remapKey = (key: string | null) =>
      key === oldPath
        ? newPath
        : key && key.startsWith(oldPath + '/')
          ? newPath + key.slice(oldPath.length)
          : key

    return {
      openTabs,
      activeTabKey: remapKey(state.activeTabKey),
      selectedPath: remapKey(state.selectedPath),
    }
  })
}

/** inline command name input box(new/ rename shared) */
function InlineNameInput({
  initial,
  selectBaseName,
  colors,
  onSubmit,
  onCancel,
}: {
  initial: string
  selectBaseName?: boolean
  colors: ThemeColors
  onSubmit: (value: string) => void
  onCancel: () => void
}) {
  const [value, setValue] = useState(initial)
  const inputRef = useRef<HTMLInputElement>(null)

  useEffect(() => {
    const el = inputRef.current
    if (!el) return
    el.focus()
    if (selectBaseName) {
      const dot = initial.lastIndexOf('.')
      el.setSelectionRange(0, dot > 0 ? dot : initial.length)
    } else {
      el.select()
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  return (
    <input
      ref={inputRef}
      value={value}
      onChange={(e) => setValue(e.target.value)}
      onKeyDown={(e) => {
        if (e.key === 'Enter') onSubmit(value)
        else if (e.key === 'Escape') onCancel()
      }}
      onBlur={() => onSubmit(value)}
      onClick={(e) => e.stopPropagation()}
      className="flex-1 min-w-0 text-xs px-1 py-0.5 rounded outline-none"
      style={{
        backgroundColor: colors.bgPrimary,
        color: colors.text,
        border: `1px solid ${colors.accent}`,
      }}
      spellCheck={false}
    />
  )
}

type MenuEntry =
  | { kind: 'sep' }
  | {
      kind: 'item'
      label: string
      icon: ReactNode
      danger?: boolean
      disabled?: boolean
      action: () => void
    }

/* ---------- menu icon(feather style) ---------- */
const iconProps = {
  className: 'w-3.5 h-3.5 shrink-0',
  viewBox: '0 0 24 24',
  fill: 'none',
  stroke: 'currentColor',
  strokeWidth: 2,
  strokeLinecap: 'round',
  strokeLinejoin: 'round',
} as const

const Icons = {
  newFile: (
    <svg {...iconProps}>
      <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z" />
      <polyline points="14 2 14 8 20 8" />
      <line x1="12" y1="18" x2="12" y2="12" />
      <line x1="9" y1="15" x2="15" y2="15" />
    </svg>
  ),
  newFolder: (
    <svg {...iconProps}>
      <path d="M22 19a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h5l2 3h9a2 2 0 0 1 2 2z" />
      <line x1="12" y1="10" x2="12" y2="16" />
      <line x1="9" y1="13" x2="15" y2="13" />
    </svg>
  ),
  cut: (
    <svg {...iconProps}>
      <circle cx="6" cy="6" r="3" />
      <circle cx="6" cy="18" r="3" />
      <line x1="20" y1="4" x2="8.12" y2="15.88" />
      <line x1="14.47" y1="14.48" x2="20" y2="20" />
      <line x1="8.12" y1="8.12" x2="12" y2="12" />
    </svg>
  ),
  copy: (
    <svg {...iconProps}>
      <rect x="9" y="9" width="13" height="13" rx="2" ry="2" />
      <path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1" />
    </svg>
  ),
  paste: (
    <svg {...iconProps}>
      <path d="M16 4h2a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2h2" />
      <rect x="8" y="2" width="8" height="4" rx="1" ry="1" />
    </svg>
  ),
  link: (
    <svg {...iconProps}>
      <path d="M10 13a5 5 0 0 0 7.54.54l3-3a5 5 0 0 0-7.07-7.07l-1.72 1.71" />
      <path d="M14 11a5 5 0 0 0-7.54-.54l-3 3a5 5 0 0 0 7.07 7.07l1.71-1.71" />
    </svg>
  ),
  edit: (
    <svg {...iconProps}>
      <path d="M17 3a2.828 2.828 0 1 1 4 4L7.5 20.5 2 22l1.5-5.5L17 3z" />
    </svg>
  ),
  chat: (
    <svg {...iconProps}>
      <path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z" />
    </svg>
  ),
  finder: (
    <svg {...iconProps}>
      <path d="M22 19a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h5l2 3h9a2 2 0 0 1 2 2z" />
      <circle cx="12" cy="13" r="1" fill="currentColor" />
    </svg>
  ),
  terminal: (
    <svg {...iconProps}>
      <polyline points="4 17 10 11 4 5" />
      <line x1="12" y1="19" x2="20" y2="19" />
    </svg>
  ),
  trash: (
    <svg {...iconProps}>
      <polyline points="3 6 5 6 21 6" />
      <path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2" />
    </svg>
  ),
}

export function LocalFileExplorer() {
  const { colors } = useThemeStore()
  const {
    rootPath,
    projects,
    activeProjectId,
    switchProject,
    tree,
    expandedPaths,
    selectedPath,
    loading,
    error,
    openFolder,
    toggleDirectory,
    expandDirectory,
    openFile,
    setSelectedPath,
    closeProject,
    activeTabKey,
  } = useLocalFileStore()

  const [contextMenu, setContextMenu] = useState<{ x: number; y: number; node: LocalFileNode | null } | null>(null)
  const [projectMenuOpen, setProjectMenuOpen] = useState(false)
  const [creating, setCreating] = useState<{ parentPath: string; kind: 'file' | 'dir' } | null>(null)
  const [renamingPath, setRenamingPath] = useState<string | null>(null)
  const [deleteTarget, setDeleteTarget] = useState<LocalFileNode | null>(null)
  const [fileClipboard, setFileClipboard] = useState<{ path: string; name: string; directory: boolean; cut: boolean } | null>(null)
  const [notice, setNotice] = useState<string | null>(null)
  const noticeTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null)

  const activeProject = projects.find(project => project.id === activeProjectId)
  const activeProjectIndex = projects.findIndex(project => project.id === activeProjectId)

  const showNotice = (message: string) => {
    setNotice(message)
    if (noticeTimerRef.current) clearTimeout(noticeTimerRef.current)
    noticeTimerRef.current = setTimeout(() => setNotice(null), 3000)
  }

  useEffect(() => {
    if (contextMenu) {
      const close = () => setContextMenu(null)
      const onKeyDown = (e: KeyboardEvent) => {
        if (e.key === 'Escape') setContextMenu(null)
      }
      document.addEventListener('click', close)
      document.addEventListener('keydown', onKeyDown)
      return () => {
        document.removeEventListener('click', close)
        document.removeEventListener('keydown', onKeyDown)
      }
    }
  }, [contextMenu])

  useEffect(() => {
    if (!projectMenuOpen) return
    const close = (event: MouseEvent) => {
      if (!(event.target instanceof Element) || !event.target.closest('[data-project-menu]')) {
        setProjectMenuOpen(false)
      }
    }
    document.addEventListener('pointerdown', close)
    return () => document.removeEventListener('pointerdown', close)
  }, [projectMenuOpen])

  const handleSwitchProject = (projectId: string) => {
    setProjectMenuOpen(false)
    requestAnimationFrame(() => {
      void switchProject(projectId)
    })
  }

  const handleAddToChat = async (node: LocalFileNode) => {
    const store = useSshAgentStore.getState()
    if (node.directory) {
      // directory: column out file
      const children = node.children || []
      const fileList = children
        .map((c) => `  ${c.directory ? '📁' : '📄'} ${c.name}`)
        .join('\n')
      store.addInputTag({
        label: `Folder: ${node.name}`,
        fullContent: `Local folder: ${node.path}\n\nFolder contents:\n${fileList}`,
        type: 'directory',
      })
    } else {
      // file: read content
      const tab = useLocalFileStore.getState().openTabs.find((t) => !isLocalDiffTab(t) && t.path === node.path) as LocalOpenTab | undefined
      let content = tab?.content
      if (!content) {
        // trigger file load
        await openFile(node.path)
        const updated = useLocalFileStore.getState().openTabs.find((t) => !isLocalDiffTab(t) && t.path === node.path) as LocalOpenTab | undefined
        content = updated?.content || ''
      }
      store.addInputTag({
        label: `File: ${node.name}`,
        fullContent: `Local file: ${node.path}\n\n\`\`\`\n${content}\n\`\`\``,
        type: 'file',
      })
    }
  }

  /* ---------- context menu action as ---------- */

  /** at point set directory down start new(auto expand merge load child node) */
  const startCreate = async (parentPath: string, kind: 'file' | 'dir') => {
    setContextMenu(null)
    const store = useLocalFileStore.getState()
    if (!store.expandedPaths.has(parentPath)) {
      useLocalFileStore.setState((state) => ({
        expandedPaths: new Set(state.expandedPaths).add(parentPath),
      }))
    }
    await expandDirectory(parentPath)
    setCreating({ parentPath, kind })
  }

  const submitCreate = async (name: string) => {
    const target = creating
    const trimmed = name.trim()
    if (!target) return
    if (!trimmed) {
      setCreating(null)
      return
    }
    if (trimmed.includes('/')) {
      showNotice('Name cannot contain /')
      return
    }
    const newPath = joinPath(target.parentPath, trimmed)
    try {
      if (await exists(newPath)) {
        showNotice(`A ${target.kind === 'file' ? 'file' : 'folder'} with this name already exists`)
        return
      }
      if (target.kind === 'file') {
        await writeTextFile(newPath, '')
      } else {
        await mkdir(newPath)
      }
      setCreating(null)
      await useLocalFileStore.getState().refreshDirectory(target.parentPath)
      if (target.kind === 'file') {
        await useLocalFileStore.getState().openFile(newPath)
      }
    } catch (err: any) {
      showNotice(`Create failed: ${err?.message ?? err}`)
    }
  }

  const submitRename = async (node: LocalFileNode, newName: string) => {
    const trimmed = newName.trim()
    setRenamingPath(null)
    if (!trimmed || trimmed === node.name) return
    if (trimmed.includes('/')) {
      showNotice('Name cannot contain /')
      return
    }
    const parent = parentOf(node.path)
    const newPath = joinPath(parent, trimmed)
    try {
      if (await exists(newPath)) {
        showNotice('An item with this name already exists')
        return
      }
      await rename(node.path, newPath)
      remapOpenTabs(node.path, newPath)
      await useLocalFileStore.getState().refreshDirectory(parent)
    } catch (err: any) {
      showNotice(`Rename failed: ${err?.message ?? err}`)
    }
  }

  const confirmDelete = async () => {
    const node = deleteTarget
    setDeleteTarget(null)
    if (!node) return
    try {
      await remove(node.path, { recursive: true })
      remapOpenTabs(node.path, null)
      if (fileClipboard && (fileClipboard.path === node.path || fileClipboard.path.startsWith(node.path + '/'))) {
        setFileClipboard(null)
      }
      await useLocalFileStore.getState().refreshDirectory(parentOf(node.path))
    } catch (err: any) {
      showNotice(`Delete failed: ${err?.message ?? err}`)
    }
  }

  const handlePaste = async (targetDir: string) => {
    const clip = fileClipboard
    setContextMenu(null)
    if (!clip) return
    if (targetDir === clip.path || targetDir.startsWith(clip.path + '/')) {
      showNotice('Cannot paste into itself or a child folder')
      return
    }
    const dest = joinPath(targetDir, clip.name)
    try {
      if (await exists(dest)) {
        showNotice('An item with this name already exists at the destination')
        return
      }
      const command = clip.cut
        ? `mv ${shellQuote(clip.path)} ${shellQuote(dest)}`
        : clip.directory
          ? `cp -R ${shellQuote(clip.path)} ${shellQuote(dest)}`
          : `cp ${shellQuote(clip.path)} ${shellQuote(dest)}`
      const result = await runShell(command, targetDir)
      if (!result.success) {
        showNotice(`Paste failed: ${result.stderr.trim() || 'Unknown error'}`)
        return
      }
      if (clip.cut) {
        remapOpenTabs(clip.path, dest)
        setFileClipboard(null)
        const sourceParent = parentOf(clip.path)
        if (sourceParent !== targetDir) {
          await useLocalFileStore.getState().refreshDirectory(sourceParent)
        }
      }
      await useLocalFileStore.getState().refreshDirectory(targetDir)
    } catch (err: any) {
      showNotice(`Paste failed: ${err?.message ?? err}`)
    }
  }

  const copyText = async (text: string, label: string) => {
    setContextMenu(null)
    try {
      await navigator.clipboard.writeText(text)
      showNotice(`${label} copied`)
    } catch {
      showNotice('Copy failed')
    }
  }

  const handleReveal = async (path: string, directory: boolean) => {
    setContextMenu(null)
    try {
      if (navigator.userAgent.includes('Mac')) {
        // macOS:open -R at Finder center set position file/ folder
        const result = await runShell(`open -R ${shellQuote(path)}`, parentOf(path))
        if (!result.success) showNotice('Unable to reveal in Finder')
      } else {
        await openPath(directory ? path : parentOf(path))
      }
    } catch {
      showNotice('Unable to reveal in the file manager')
    }
  }

  const handleOpenInTerminal = (path: string, directory: boolean) => {
    setContextMenu(null)
    const cwd = directory ? path : parentOf(path)
    window.dispatchEvent(new CustomEvent('open-local-terminal', { detail: { cwd } }))
  }

  /* ---------- context menu item ---------- */

  const buildMenuEntries = (): MenuEntry[] => {
    if (!contextMenu) return []
    const node = contextMenu.node
    // right-click empty area targets the project root
    const targetDir = node ? (node.directory ? node.path : parentOf(node.path)) : rootPath!
    const entries: MenuEntry[] = [
      { kind: 'item', label: 'New file', icon: Icons.newFile, action: () => void startCreate(targetDir, 'file') },
      { kind: 'item', label: 'New folder', icon: Icons.newFolder, action: () => void startCreate(targetDir, 'dir') },
      { kind: 'sep' },
    ]

    if (node) {
      entries.push(
        {
          kind: 'item', label: 'Cut', icon: Icons.cut,
          action: () => {
            setFileClipboard({ path: node.path, name: node.name, directory: node.directory, cut: true })
            setContextMenu(null)
          },
        },
        {
          kind: 'item', label: 'Copy', icon: Icons.copy,
          action: () => {
            setFileClipboard({ path: node.path, name: node.name, directory: node.directory, cut: false })
            setContextMenu(null)
          },
        },
      )
    }
    entries.push({
      kind: 'item', label: 'Paste', icon: Icons.paste, disabled: !fileClipboard,
      action: () => void handlePaste(targetDir),
    })

    if (node) {
      const relativePath = rootPath && node.path.startsWith(rootPath + '/')
        ? node.path.slice(rootPath.length + 1)
        : null
      entries.push(
        { kind: 'sep' },
        { kind: 'item', label: 'Copy path', icon: Icons.link, action: () => void copyText(node.path, 'Path') },
        {
          kind: 'item', label: 'Copy relative path', icon: Icons.link, disabled: !relativePath,
          action: () => void copyText(relativePath ?? node.path, 'Relative path'),
        },
        { kind: 'sep' },
        {
          kind: 'item', label: 'Rename', icon: Icons.edit,
          action: () => {
            setRenamingPath(node.path)
            setContextMenu(null)
          },
        },
        {
          kind: 'item', label: 'Add to AI chat', icon: Icons.chat,
          action: () => {
            void handleAddToChat(node)
            setContextMenu(null)
          },
        },
        { kind: 'item', label: 'Reveal in Finder', icon: Icons.finder, action: () => void handleReveal(node.path, node.directory) },
        { kind: 'item', label: 'Open in Terminal', icon: Icons.terminal, action: () => handleOpenInTerminal(node.path, node.directory) },
        { kind: 'sep' },
        {
          kind: 'item', label: 'Delete', icon: Icons.trash, danger: true,
          action: () => {
            setDeleteTarget(node)
            setContextMenu(null)
          },
        },
      )
    } else {
      entries.push(
        { kind: 'sep' },
        { kind: 'item', label: 'Reveal in Finder', icon: Icons.finder, action: () => void handleReveal(targetDir, true) },
        { kind: 'item', label: 'Open in Terminal', icon: Icons.terminal, action: () => handleOpenInTerminal(targetDir, true) },
      )
    }
    return entries
  }

  const renderMenuEntry = (entry: MenuEntry, index: number) => {
    if (entry.kind === 'sep') {
      return <div key={`sep-${index}`} className="my-1 mx-2" style={{ height: 1, backgroundColor: colors.border }} />
    }
    return (
      <button
        key={`${entry.label}-${index}`}
        disabled={entry.disabled}
        className="w-full text-left px-3 py-1.5 text-xs flex items-center gap-2 transition-colors hover:bg-white/10 disabled:hover:bg-transparent disabled:opacity-40"
        style={{ color: entry.danger ? colors.red : colors.text }}
        onClick={(e) => {
          e.stopPropagation()
          if (!entry.disabled) entry.action()
        }}
      >
        {entry.icon}
        {entry.label}
      </button>
    )
  }

  /** new/ rename inline edit ok */
  const renderEditRow = (
    depth: number,
    opts: {
      directory: boolean
      initial: string
      selectBaseName?: boolean
      onSubmit: (value: string) => void
      onCancel: () => void
    },
  ) => (
    <div
      className="w-full flex items-center gap-1.5 px-2 py-1 text-xs"
      style={{ paddingLeft: `${8 + depth * 14}px`, color: colors.text }}
    >
      <span className="w-3" />
      <svg
        className="w-4 h-4 shrink-0"
        style={{ color: opts.directory ? colors.accent : colors.textDim }}
        viewBox="0 0 24 24"
        fill="none"
        stroke="currentColor"
        strokeWidth="2"
      >
        {opts.directory ? (
          <path d="M22 19a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h5l2 3h9a2 2 0 0 1 2 2z"></path>
        ) : (
          <>
            <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"></path>
            <polyline points="14 2 14 8 20 8"></polyline>
          </>
        )}
      </svg>
      <InlineNameInput
        initial={opts.initial}
        selectBaseName={opts.selectBaseName}
        colors={colors}
        onSubmit={opts.onSubmit}
        onCancel={opts.onCancel}
      />
    </div>
  )

  const renderTree = (nodes: LocalFileNode[], depth = 0) => {
    return nodes.map((node) => {
      const isExpanded = expandedPaths.has(node.path)
      const isSelected = selectedPath === node.path
      const isActive = !node.directory && activeTabKey === node.path
      const isCutSource = fileClipboard?.cut && fileClipboard.path === node.path

      return (
        <div key={node.path}>
          {renamingPath === node.path ? (
            renderEditRow(depth, {
              directory: node.directory,
              initial: node.name,
              selectBaseName: !node.directory,
              onSubmit: (value) => void submitRename(node, value),
              onCancel: () => setRenamingPath(null),
            })
          ) : (
            <div
              className="w-full flex items-center justify-between px-2 py-1 text-xs transition-colors group cursor-pointer"
              style={{
                paddingLeft: `${8 + depth * 14}px`,
                color: isActive || isSelected ? colors.accent : colors.textSecondary,
                backgroundColor: isActive || isSelected ? `${colors.accent}15` : 'transparent',
                opacity: isCutSource ? 0.5 : 1,
              }}
              onMouseEnter={(e) => {
                if (!isActive && !isSelected) e.currentTarget.style.backgroundColor = 'rgba(255,255,255,0.05)'
              }}
              onMouseLeave={(e) => {
                if (!isActive && !isSelected) e.currentTarget.style.backgroundColor = 'transparent'
              }}
              onContextMenu={(e) => {
                e.preventDefault()
                e.stopPropagation()
                setContextMenu({ x: e.clientX, y: e.clientY, node })
              }}
              draggable
              onDragStart={(event) => {
                event.dataTransfer.effectAllowed = 'copyMove'
                event.dataTransfer.setData('application/x-shellmind-local-path', node.path)
                event.dataTransfer.setData('text/plain', node.path)
              }}
              title={node.path}
            >
              <div
                className="flex items-center gap-1.5 flex-1 min-w-0"
                onClick={() => {
                  if (node.directory) {
                    setSelectedPath(node.path)
                    void toggleDirectory(node.path)
                  } else {
                    void openFile(node.path)
                  }
                }}
              >
                {node.directory && (
                  <span className="text-[10px] w-3 flex items-center justify-center" style={{ color: colors.textDim }}>
                    <svg
                      className="w-3 h-3 transition-transform"
                      style={{ transform: isExpanded ? '' : 'rotate(-90deg)' }}
                      viewBox="0 0 24 24"
                      fill="none"
                      stroke="currentColor"
                      strokeWidth="2"
                    >
                      <polyline points="6 9 12 15 18 9"></polyline>
                    </svg>
                  </span>
                )}
                {!node.directory && <span className="w-3" />}
                <svg
                  className="w-4 h-4 shrink-0"
                  style={{
                    color: node.directory
                      ? colors.accent
                      : isActive
                        ? colors.accent
                        : colors.textDim,
                  }}
                  viewBox="0 0 24 24"
                  fill="none"
                  stroke="currentColor"
                  strokeWidth="2"
                >
                  {node.directory ? (
                    <path d="M22 19a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h5l2 3h9a2 2 0 0 1 2 2z"></path>
                  ) : (
                    <>
                      <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"></path>
                      <polyline points="14 2 14 8 20 8"></polyline>
                    </>
                  )}
                </svg>
                <span className={`truncate ${isActive || isSelected ? 'font-medium' : ''} ${depth === 0 && node.directory && !node.name.startsWith('.') ? 'font-bold' : ''}`} style={depth === 0 && node.directory && !node.name.startsWith('.') ? { color: colors.text } : undefined}>{node.name}</span>
              </div>

              <button
                className="opacity-0 group-hover:opacity-100 flex-shrink-0 w-5 h-5 flex items-center justify-center rounded hover:bg-black/20"
                style={{ color: colors.textDim }}
                title="Add to AI chat"
                onClick={(e) => {
                  e.stopPropagation()
                  void handleAddToChat(node)
                }}
              >
                <svg className="w-3.5 h-3.5" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                  <line x1="12" y1="5" x2="12" y2="19"></line>
                  <line x1="5" y1="12" x2="19" y2="12"></line>
                </svg>
              </button>
            </div>
          )}
          {node.directory && isExpanded && (
            <>
              {creating?.parentPath === node.path &&
                renderEditRow(depth + 1, {
                  directory: creating.kind === 'dir',
                  initial: '',
                  onSubmit: (value) => void submitCreate(value),
                  onCancel: () => setCreating(null),
                })}
              {node.children && renderTree(node.children, depth + 1)}
            </>
          )}
        </div>
      )
    })
  }

  if (!rootPath) {
    return (
      <div className="h-full flex flex-col items-center justify-center gap-3 px-4 py-8">
        <svg className="w-12 h-12 opacity-30" viewBox="0 0 24 24" fill="none" stroke={colors.textDim} strokeWidth="1.5">
          <path d="M22 19a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h5l2 3h9a2 2 0 0 1 2 2z"></path>
        </svg>
        <p className="text-sm" style={{ color: colors.textSecondary }}>
          Open local folder
        </p>
        <p className="text-xs text-center" style={{ color: colors.textDim }}>
          Choose a project folder to start coding
        </p>
        <button
          onClick={() => void openFolder()}
          className="mt-2 px-4 py-2 rounded-lg text-xs font-medium transition-colors"
          style={{
            backgroundColor: colors.accent,
            color: '#fff',
          }}
        >
          Choose folder
        </button>
      </div>
    )
  }

  return (
    <div className="h-full flex flex-col">
      {/* local project toolbar */}
      <div data-project-menu className="relative flex items-center gap-1 h-9 px-2 border-b flex-shrink-0" style={{ borderColor: colors.border }}>
        <button
          onClick={projects.length > 1 ? () => setProjectMenuOpen(!projectMenuOpen) : undefined}
          className="flex h-6 max-w-[125px] min-w-0 items-center gap-1 rounded-md px-1.5 text-[11px] leading-none transition-colors hover:bg-black/5"
          style={{ color: colors.text }}
          title={activeProject?.path}
        >
          <span className="truncate">{activeProject?.name}</span>
          {projects.length > 1 && (
            <span className="flex-shrink-0 rounded-full px-1 py-[1px] text-[9px]" style={{ backgroundColor: `${colors.textDim}12`, color: colors.textSecondary }}>
              {activeProjectIndex + 1}/{projects.length}
            </span>
          )}
          {projects.length > 1 && (
            <svg className="w-3 h-3 flex-shrink-0" style={{ color: colors.textDim }} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5"><polyline points="6 9 12 15 18 9" /></svg>
          )}
        </button>
        <div className="ml-auto flex items-center gap-0.5 shrink-0">
          <button
            onClick={() => void openFolder()}
            className="w-5 h-5 flex items-center justify-center rounded transition-colors hover:bg-black/10"
            style={{ color: colors.textDim }}
            title="Open project"
          >
            <svg className="w-3.5 h-3.5" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round"><path d="M12 5v14M5 12h14" /></svg>
          </button>
          <button
            onClick={async () => {
              const store = useLocalFileStore.getState()
              if (store.rootPath) {
                // refresh root directory file tree
                await store.refreshDirectory(store.rootPath)
                // reload the active tab file contents
                if (store.activeTabKey) {
                  const tab = store.openTabs.find((t) => t.key === store.activeTabKey)
                  if (tab) void store.reloadFileByPath(tab.path)
                }
              }
            }}
            className="w-5 h-5 flex items-center justify-center rounded transition-colors hover:bg-white/10"
            style={{ color: colors.textDim }}
            title="Refresh the file tree and editor"
          >
            <svg className="w-3.5 h-3.5" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
              <polyline points="23 4 23 10 17 10"></polyline>
              <path d="M20.49 15a9 9 0 1 1-2.12-9.36L23 10"></path>
            </svg>
          </button>
          <button
            onClick={() => activeProject && void closeProject(activeProject.id)}
            className="w-5 h-5 flex items-center justify-center rounded transition-colors hover:bg-white/10"
            style={{ color: colors.textDim }}
            title="Close current project"
          >
            <svg className="w-3.5 h-3.5" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
              <line x1="18" y1="6" x2="6" y2="18"></line>
              <line x1="6" y1="6" x2="18" y2="18"></line>
            </svg>
          </button>
        </div>
      </div>

      {projectMenuOpen && projects.length > 1 && (
        <div
          data-project-menu
          className="absolute left-2 right-2 top-10 z-[90] max-h-[220px] overflow-y-auto rounded-lg border shadow-xl py-1"
          style={{ backgroundColor: colors.bgPrimary, borderColor: colors.border }}
        >
          {projects.map(project => {
            const active = project.id === activeProjectId
            return (
              <button
                key={project.id}
                onClick={() => handleSwitchProject(project.id)}
                className="w-full flex items-center gap-2 px-3 py-1.5 text-left text-[11px] transition-colors hover:bg-black/5"
                style={{ color: active ? colors.accent : colors.text, backgroundColor: active ? `${colors.accent}10` : 'transparent' }}
                title={project.path}
              >
                <span className="truncate flex-1">{project.name}</span>
                {active && <svg className="w-3 h-3 shrink-0" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="3"><polyline points="20 6 9 17 4 12" /></svg>}
              </button>
            )
          })}
        </div>
      )}

      {/* file tree(empty white place right-click → so item goal root directory as goal mark) */}
      <div
        className="flex-1 overflow-y-auto"
        onContextMenu={(e) => {
          e.preventDefault()
          setContextMenu({ x: e.clientX, y: e.clientY, node: null })
        }}
      >
        {loading ? (
          <div className="flex items-center justify-center py-8 gap-2">
            <svg className="w-4 h-4 animate-spin" style={{ color: colors.accent }} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
              <path d="M23 4v6h-6M1 20v-6h6" />
              <path d="M3.51 9a9 9 0 0 1 14.85-3.36L23 10M1 14l4.64 4.36A9 9 0 0 0 20.49 15" />
            </svg>
            <span className="text-xs" style={{ color: colors.textSecondary }}>Loading...</span>
          </div>
        ) : error ? (
          <div className="px-3 py-4">
            <p className="text-xs" style={{ color: colors.red }}>{error}</p>
          </div>
        ) : (
          <div className="py-1 min-h-full">
            {creating && creating.parentPath === rootPath &&
              renderEditRow(0, {
                directory: creating.kind === 'dir',
                initial: '',
                onSubmit: (value) => void submitCreate(value),
                onCancel: () => setCreating(null),
              })}
            {renderTree(tree)}
          </div>
        )}
      </div>

      {/* context menu */}
      {contextMenu && (
        <div
          className="fixed z-[100] rounded-md shadow-lg border py-1 min-w-[180px]"
          style={{
            left: Math.min(contextMenu.x, window.innerWidth - 190),
            top: Math.min(contextMenu.y, window.innerHeight - 380),
            backgroundColor: colors.bgSecondary,
            borderColor: colors.border,
          }}
          onClick={(e) => e.stopPropagation()}
        >
          {buildMenuEntries().map(renderMenuEntry)}
        </div>
      )}

      {/* delete confirm */}
      {deleteTarget && (
        <div
          className="fixed inset-0 z-[100] flex items-center justify-center"
          style={{ backgroundColor: 'rgba(0,0,0,0.4)' }}
          onClick={() => setDeleteTarget(null)}
        >
          <div
            className="w-[320px] rounded-lg border shadow-xl p-4"
            style={{ backgroundColor: colors.bgSecondary, borderColor: colors.border }}
            onClick={(e) => e.stopPropagation()}
          >
            <p className="text-xs font-medium" style={{ color: colors.text }}>
              Delete {deleteTarget.directory ? 'Folder' : 'File'} “{deleteTarget.name}”?
            </p>
            <p className="mt-1.5 text-[11px] break-all" style={{ color: colors.textDim }}>
              {deleteTarget.path}
            </p>
            {deleteTarget.directory && (
              <p className="mt-2 text-[11px]" style={{ color: colors.red }}>
                The folder and everything in it will be deleted. This cannot be undone.
              </p>
            )}
            <div className="mt-4 flex justify-end gap-2">
              <button
                className="px-3 py-1.5 rounded-md text-xs transition-colors hover:bg-white/10"
                style={{ color: colors.textSecondary }}
                onClick={() => setDeleteTarget(null)}
              >
                Cancel
              </button>
              <button
                className="px-3 py-1.5 rounded-md text-xs font-medium transition-opacity hover:opacity-85"
                style={{ backgroundColor: colors.red, color: '#fff' }}
                onClick={() => void confirmDelete()}
              >
                Delete
              </button>
            </div>
          </div>
        </div>
      )}

      {/* action hint */}
      {notice && (
        <div
          className="fixed bottom-6 left-1/2 -translate-x-1/2 z-[110] px-3 py-1.5 rounded-md border shadow-lg text-xs"
          style={{ backgroundColor: colors.bgSecondary, borderColor: colors.border, color: colors.text }}
        >
          {notice}
        </div>
      )}
    </div>
  )
}
