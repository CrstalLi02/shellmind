import { create } from 'zustand'
import { getFileContent, getFileTree, saveFileContent, getFileContentChunk } from '../api/sshFile'
import { useAiPatchStore } from './aiPatchStore'

/** light amount level language push break(only for Diff Tab,Monaco not recognize other when will auto fallback to plaintext) */
function detectLanguage(filename: string): string {
  const ext = filename.split('.').pop()?.toLowerCase() || ''
  const map: Record<string, string> = {
    js: 'javascript', jsx: 'javascript', ts: 'typescript', tsx: 'typescript',
    json: 'json', html: 'html', htm: 'html', css: 'css', scss: 'scss', less: 'less',
    md: 'markdown', mdx: 'markdown', py: 'python', java: 'java', kt: 'kotlin',
    go: 'go', rs: 'rust', c: 'c', cpp: 'cpp', h: 'c', hpp: 'cpp',
    sh: 'shell', bash: 'shell', yml: 'yaml', yaml: 'yaml', xml: 'xml',
    sql: 'sql', vue: 'html', php: 'php', rb: 'ruby', swift: 'swift',
    toml: 'ini', ini: 'ini', conf: 'ini', properties: 'ini',
  }
  return map[ext] || 'plaintext'
}

/** format file size as a readable string */
export function formatFileSize(bytes: number | null | undefined): string {
  if (bytes == null || bytes === 0) return '-'
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  if (bytes < 1024 * 1024 * 1024) return `${(bytes / 1024 / 1024).toFixed(1)} MB`
  return `${(bytes / 1024 / 1024 / 1024).toFixed(2)} GB`
}

export interface FileNode {
  name: string
  path: string
  directory: boolean
  size: number | null
  modifiedAt: number | null
}

/** Diff tab: used in editor center compare AI fix edit front after content */
export interface DiffFileTab {
  key: string
  /** mark as diff type */
  kind: 'diff'
  /** linked AiPatchPreview id(used in AiPatchStore center find most new content) */
  previewId: string
  target: 'local' | 'remote'
  connectionId?: string
  path: string
  name: string
  language: string
  /** fix edit front original content */
  beforeContent: string
  /** fix edit after content */
  afterContent: string
  /** add ok number */
  addedLines: number
  /** delete ok number */
  removedLines: number
}

export type AnyOpenTab = OpenFileTab | DiffFileTab

export function isDiffTab(tab: AnyOpenTab | null | undefined): tab is DiffFileTab {
  return !!tab && (tab as DiffFileTab).kind === 'diff'
}

export interface OpenFileTab {
  key: string
  connectionId: string
  path: string
  name: string
  content: string
  loading: boolean
  binary: boolean
  truncated: boolean
  error?: string
  modified?: boolean
  size?: number
  /** parse type for binary files(like "java-class"), non empty table show content as parse after shape view image(read-only) */
  parsedType?: string
  /** each time external(AI) re- load content when increment, for strong make Monaco Editor refresh */
  contentVersion?: number
}

interface FileExplorerStore {
  activeConnectionId: string | null
  rootPathByConnection: Record<string, string>
  homePathByConnection: Record<string, string>
  currentPathByConnection: Record<string, string>
  selectedPathByConnection: Record<string, string>
  childrenByConnection: Record<string, Record<string, FileNode[]>>
  expandedByConnection: Record<string, string[]>
  loadingPathsByConnection: Record<string, string[]>
  loadingRootByConnection: Record<string, boolean>
  errorByConnection: Record<string, string | null>

  openTabs: AnyOpenTab[]
  activeTabKey: string | null

  /** follow add read large file back continue split piece */
  loadMoreContent: (key: string) => Promise<void>

  switchConnection: (connectionId: string) => Promise<void>
  navigateToPath: (connectionId: string, path: string) => Promise<void>
  toggleDirectory: (connectionId: string, path: string) => Promise<void>
  _preloadCondensedChain: (connectionId: string, dirPath: string) => Promise<void>
  refreshCurrentPath: (connectionId: string) => Promise<void>
  refreshDirectory: (connectionId: string, path: string) => Promise<void>
  reloadFileByPath: (connectionId: string, path: string) => Promise<string | null>
  /** read remote file content(do not open tab), for AI change back get afterContent */
  readRemoteFileContent: (connectionId: string, path: string) => Promise<string | null>
  restoreFileContent: (connectionId: string, path: string, content: string) => Promise<boolean>
  setSelectedPath: (connectionId: string, path: string) => void

  openFile: (connectionId: string, path: string, name: string) => Promise<void>
  /**
   * open Diff tab(used in editor center so diff mode check see AI edit action file).
   * if same preview already open, activate i.e. ok; otherwise new.
   */
  openDiffTab: (previewId: string) => void
  closeDiffTab: (previewId: string) => void
  updateFileContent: (key: string, content: string) => void
  saveFile: (key: string, useSudo?: boolean) => Promise<boolean>
  setActiveTab: (key: string) => void
  closeTab: (key: string) => void
  closeTabsToLeft: (key: string) => void
  closeTabsToRight: (key: string) => void
  closeOtherTabs: (key: string) => void
  closeAllTabs: () => void
}

function listIncludes(list: string[] | undefined, target: string): boolean {
  return !!list?.includes(target)
}

function listAdd(list: string[] | undefined, target: string): string[] {
  const safe = list ?? []
  if (safe.includes(target)) return safe
  return [...safe, target]
}

function listRemove(list: string[] | undefined, target: string): string[] {
  return (list ?? []).filter((item) => item !== target)
}

function tabKeyOf(connectionId: string, path: string): string {
  return `${connectionId}:${path}`
}

export const useFileExplorerStore = create<FileExplorerStore>((set, get) => ({
  activeConnectionId: null,
  rootPathByConnection: {},
  homePathByConnection: {},
  currentPathByConnection: {},
  selectedPathByConnection: {},
  childrenByConnection: {},
  expandedByConnection: {},
  loadingPathsByConnection: {},
  loadingRootByConnection: {},
  errorByConnection: {},

  openTabs: [],
  activeTabKey: null,

  switchConnection: async (connectionId) => {
    set((state) => ({ activeConnectionId: connectionId, errorByConnection: { ...state.errorByConnection, [connectionId]: null } }))
    const currentPath = get().currentPathByConnection[connectionId]
    await get().navigateToPath(connectionId, currentPath || '')
  },

  navigateToPath: async (connectionId, path) => {
    if (get().loadingRootByConnection[connectionId]) {
      return
    }

    set((state) => ({
      loadingRootByConnection: { ...state.loadingRootByConnection, [connectionId]: true },
      loadingPathsByConnection: {
        ...state.loadingPathsByConnection,
        [connectionId]: listAdd(state.loadingPathsByConnection[connectionId], path || '__home__'),
      },
      errorByConnection: { ...state.errorByConnection, [connectionId]: null },
    }))

    const res = await getFileTree(connectionId, path)
    if (res.code !== '0000' || !res.data) {
      set((state) => ({
        loadingRootByConnection: { ...state.loadingRootByConnection, [connectionId]: false },
        loadingPathsByConnection: {
          ...state.loadingPathsByConnection,
          [connectionId]: listRemove(state.loadingPathsByConnection[connectionId], path || '__home__'),
        },
        errorByConnection: { ...state.errorByConnection, [connectionId]: res.info || 'Failed to read directory' },
      }))
      return
    }

    const data = res.data
    set((state) => {
      const connectionChildren = { ...(state.childrenByConnection[connectionId] || {}) }
      connectionChildren[data.currentPath] = data.items

      return {
        rootPathByConnection: { ...state.rootPathByConnection, [connectionId]: data.rootPath || '/' },
        homePathByConnection: { ...state.homePathByConnection, [connectionId]: data.homePath || '/' },
        currentPathByConnection: { ...state.currentPathByConnection, [connectionId]: data.currentPath || '/' },
        selectedPathByConnection: { ...state.selectedPathByConnection, [connectionId]: state.selectedPathByConnection[connectionId] || data.currentPath || '/' },
        childrenByConnection: { ...state.childrenByConnection, [connectionId]: connectionChildren },
        expandedByConnection: {
          ...state.expandedByConnection,
          [connectionId]: listAdd(state.expandedByConnection[connectionId], data.currentPath || '/'),
        },
        loadingRootByConnection: { ...state.loadingRootByConnection, [connectionId]: false },
        loadingPathsByConnection: {
          ...state.loadingPathsByConnection,
          [connectionId]: listRemove(state.loadingPathsByConnection[connectionId], path || '__home__'),
        },
      }
    })
  },

  /**
   * recursive pre- load Java source code path center form directory chain.
   * only in src/{scope}/java internal trigger, meet to extra directory/ has file/ keyword directory when stop.
   * purpose: let buildCondensedNode can at first expand when then get deep layer children data.
   */
  _preloadCondensedChain: async (connectionId: string, dirPath: string): Promise<void> => {
    const { childrenByConnection } = get()
    const children = childrenByConnection[connectionId]?.[dirPath]
    if (!children || children.length === 0) return

    const subDirs = children.filter(c => c.directory)
    const files = children.filter(c => !c.directory)
    // has file or multiple directory → not need need continue pre- load
    if (files.length > 0 || subDirs.length !== 1) return

    const onlyChild = subDirs[0]
    // already via load via → recursive check down one level
    if (childrenByConnection[connectionId]?.[onlyChild.path]) {
      await get()._preloadCondensedChain(connectionId, onlyChild.path)
      return
    }

    // load down one level
    const res = await getFileTree(connectionId, onlyChild.path)
    if (res.code === '0000' && res.data) {
      set((state) => {
        const connectionChildren = { ...(state.childrenByConnection[connectionId] || {}) }
        connectionChildren[onlyChild.path] = res.data!.items
        return {
          childrenByConnection: { ...state.childrenByConnection, [connectionId]: connectionChildren },
        }
      })
      // recursive pre- load
      await get()._preloadCondensedChain(connectionId, onlyChild.path)
    }
  },

  toggleDirectory: async (connectionId, path) => {
    const loadingPaths = get().loadingPathsByConnection[connectionId] || []
    if (loadingPaths.includes(path)) {
      return
    }

    const expanded = get().expandedByConnection[connectionId] || []
    const isExpanded = expanded.includes(path)
    if (isExpanded) {
      set((state) => ({
        expandedByConnection: {
          ...state.expandedByConnection,
          [connectionId]: listRemove(state.expandedByConnection[connectionId], path),
        },
      }))
      return
    }

    set((state) => ({
      expandedByConnection: {
        ...state.expandedByConnection,
        [connectionId]: listAdd(state.expandedByConnection[connectionId], path),
      },
    }))

    const hasLoaded = !!get().childrenByConnection[connectionId]?.[path]
    if (!hasLoaded) {
      set((state) => ({
        loadingPathsByConnection: {
          ...state.loadingPathsByConnection,
          [connectionId]: listAdd(state.loadingPathsByConnection[connectionId], path),
        },
      }))

      const res = await getFileTree(connectionId, path)
      if (res.code === '0000' && res.data) {
        set((state) => {
          const connectionChildren = { ...(state.childrenByConnection[connectionId] || {}) }
          connectionChildren[path] = res.data!.items
          return {
            childrenByConnection: { ...state.childrenByConnection, [connectionId]: connectionChildren },
            loadingPathsByConnection: {
              ...state.loadingPathsByConnection,
              [connectionId]: listRemove(state.loadingPathsByConnection[connectionId], path),
            },
          }
        })

        // Java source code path: recursive pre- load form directory chain, so package collapsing applies on first expand
        await get()._preloadCondensedChain(connectionId, path)
      } else {
        set((state) => ({
          loadingPathsByConnection: {
            ...state.loadingPathsByConnection,
            [connectionId]: listRemove(state.loadingPathsByConnection[connectionId], path),
          },
          errorByConnection: { ...state.errorByConnection, [connectionId]: res.info || 'Failed to read directory' },
        }))
      }
    }
  },

  refreshCurrentPath: async (connectionId) => {
    const currentPath = get().currentPathByConnection[connectionId] || ''
    await get().navigateToPath(connectionId, currentPath)
  },

  refreshDirectory: async (connectionId, path) => {
    await get().navigateToPath(connectionId, path)
  },

  reloadFileByPath: async (connectionId, path) => {
    const key = tabKeyOf(connectionId, path)
    const tab = get().openTabs.find((item) => item.key === key)
    if (!tab || isDiffTab(tab)) return null

    const res = await getFileContent(connectionId, path)
    if (res.code !== '0000' || !res.data) {
      set((state) => ({
        openTabs: state.openTabs.map((item) =>
          !isDiffTab(item) && item.key === key ? { ...item, loading: false, error: res.info || 'Failed to read file' } : item
        ),
      }))
      return null
    }

    const content = res.data.content || ''
    set((state) => ({
      openTabs: state.openTabs.map((item) =>
        !isDiffTab(item) && item.key === key
          ? {
              ...item,
              loading: false,
              content,
              binary: !!res.data!.binary,
              truncated: !!res.data!.truncated,
              modified: false,
              size: res.data!.size,
              parsedType: res.data!.parsedType ?? undefined,
              error: undefined,
              contentVersion: (item.contentVersion ?? 0) + 1,
            }
          : item
      ),
    }))

    return content
  },

  readRemoteFileContent: async (connectionId, path) => {
    try {
      const res = await getFileContent(connectionId, path)
      if (res.code !== '0000' || !res.data) return null
      return res.data.content || ''
    } catch (err: any) {
      console.error('[fileExplorerStore] readRemoteFileContent error:', connectionId, path, err)
      return null
    }
  },

  restoreFileContent: async (connectionId, path, content) => {
    const key = tabKeyOf(connectionId, path)
    const res = await saveFileContent(connectionId, path, content, false)
    if (res.code !== '0000') {
      set((state) => ({
        openTabs: state.openTabs.map((item) =>
          !isDiffTab(item) && item.key === key ? { ...item, error: res.info || 'Failed to restore file' } : item
        ),
      }))
      return false
    }

    set((state) => ({
      openTabs: state.openTabs.map((item) =>
        !isDiffTab(item) && item.key === key
          ? { ...item, content, loading: false, modified: false, error: undefined }
          : item
      ),
    }))

    return true
  },

  setSelectedPath: (connectionId, path) => {
    set((state) => ({
      selectedPathByConnection: {
        ...state.selectedPathByConnection,
        [connectionId]: path,
      },
    }))
  },

  openFile: async (connectionId, path, name) => {
    const key = tabKeyOf(connectionId, path)
    const existing = get().openTabs.find((tab) => tab.key === key)
    if (existing) {
      get().setActiveTab(key)
      return
    }

    set((state) => ({
      openTabs: [...state.openTabs, {
        key,
        connectionId,
        path,
        name,
        content: '',
        loading: true,
        binary: false,
        truncated: false,
        modified: false,
        size: undefined,
      }],
    }))
    get().setActiveTab(key)

    const res = await getFileContent(connectionId, path)
    if (res.code !== '0000' || !res.data) {
      set((state) => ({
        openTabs: state.openTabs.map((tab) => !isDiffTab(tab) && tab.key === key
          ? { ...tab, loading: false, error: res.info || 'Failed to read file' }
          : tab),
      }))
      return
    }

    set((state) => ({
      openTabs: state.openTabs.map((tab) => !isDiffTab(tab) && tab.key === key
        ? {
            ...tab,
            loading: false,
            content: res.data!.content || '',
            binary: !!res.data!.binary,
            truncated: !!res.data!.truncated,
            modified: false,
            size: res.data!.size,
            parsedType: res.data!.parsedType ?? undefined,
            error: undefined,
          }
        : tab),
    }))
  },


  /**
   * open Diff tab.
   * via AiPatchPreview get before/after content, language, generate special special tab.
   * if already save at same preview diff tab, activate i.e. ok.
   */
  openDiffTab: (previewId) => {
    const preview = useAiPatchStore.getState().previews.find(p => p.id === previewId)
    if (!preview) return

    const key = `diff:${previewId}`
    const existing = get().openTabs.find(t => t.key === key)
    if (existing) {
      get().setActiveTab(key)
      return
    }

    const sep = preview.path.lastIndexOf('/')
    const name = sep >= 0 ? preview.path.substring(sep + 1) : preview.path
    const language = detectLanguage(name)

    const newTab: DiffFileTab = {
      kind: 'diff',
      key,
      previewId: preview.id,
      target: preview.target,
      connectionId: preview.connectionId,
      path: preview.path,
      name,
      language,
      beforeContent: preview.beforeContent,
      afterContent: preview.afterContent,
      addedLines: preview.addedLines,
      removedLines: preview.removedLines,
    }

    set((state) => ({ openTabs: [...state.openTabs, newTab] }))
    get().setActiveTab(key)
  },

  /**
   * close point set previewId Diff Tab(not will affect raw file tab).
   */
  closeDiffTab: (previewId) => {
    const key = `diff:${previewId}`
    get().closeTab(key)
  },


  loadMoreContent: async (key) => {
    const tab = get().openTabs.find(t => t.key === key)
    if (!tab || isDiffTab(tab) || tab.binary || !tab.truncated) return

    set((state) => ({
      openTabs: state.openTabs.map((t) => t.key === key ? { ...t, loading: true } : t)
    }))

    const CHUNK = 256 * 1024
    const currentOffset = tab.content.length
    const res = await getFileContentChunk(tab.connectionId, tab.path, currentOffset, CHUNK)
    if (res.code === '0000' && res.data) {
      set((state) => ({
        openTabs: state.openTabs.map((t) => (!isDiffTab(t) && t.key === key)
          ? {
              ...t,
              loading: false,
              content: t.content + (res.data!.content || ''),
              truncated: !!res.data!.truncated,
            }
          : t)
      }))
    } else {
      set((state) => ({
        openTabs: state.openTabs.map((t) => t.key === key ? { ...t, loading: false } : t)
      }))
    }
  },

  updateFileContent: (key, content) => {
    set((state) => ({
      openTabs: state.openTabs.map((tab) => {
        if (!isDiffTab(tab) && tab.key === key && tab.content !== content) {
          return { ...tab, content, modified: true }
        }
        return tab
      })
    }))
  },

  saveFile: async (key, useSudo = false) => {
    const tab = get().openTabs.find(t => t.key === key)
    if (!tab || isDiffTab(tab)) return false

    set((state) => ({
      openTabs: state.openTabs.map((t) => t.key === key ? { ...t, loading: true } : t)
    }))

    const res = await saveFileContent(tab.connectionId, tab.path, tab.content, useSudo)
    
    if (res.code === '0000') {
      set((state) => ({
        openTabs: state.openTabs.map((t) => t.key === key ? { ...t, loading: false, modified: false } : t)
      }))
      return true
    } else {
      set((state) => ({
        openTabs: state.openTabs.map((t) => t.key === key ? { ...t, loading: false, error: res.info || 'Save failed' } : t)
      }))
      return false
    }
  },

  setActiveTab: (key) => {
    set((state) => {
      // Diff Tab not go directory expand logic edit
      if (key.startsWith('diff:')) {
        return { activeTabKey: key }
      }
      // extract connectionId and path
      const colonIdx = key.indexOf(':')
      if (colonIdx > 0) {
        const connectionId = key.substring(0, colonIdx)
        const path = key.substring(colonIdx + 1)

        // auto-expand all parent directories of the file
        const parts = path.split('/').filter(Boolean)
        const toExpand: string[] = ['/']
        let cursor = ''
        for (let i = 0; i < parts.length - 1; i++) { // not include file name this body
          cursor += `/${parts[i]}`
          toExpand.push(cursor)
        }

        const currentExpanded = state.expandedByConnection[connectionId] || []
        const newExpanded = [...new Set([...currentExpanded, ...toExpand])]

        return {
          activeTabKey: key,
          expandedByConnection: {
            ...state.expandedByConnection,
            [connectionId]: newExpanded
          }
        }
      }
      return { activeTabKey: key }
    })
  },

  closeTab: (key) => {
    set((state) => {
      const tabs = state.openTabs.filter((tab) => tab.key !== key)
      let nextActiveKey = state.activeTabKey
      if (state.activeTabKey === key) {
        nextActiveKey = tabs.length ? tabs[tabs.length - 1].key : null
      }
      return { openTabs: tabs, activeTabKey: nextActiveKey }
    })
  },

  closeTabsToLeft: (key) => {
    set((state) => {
      const idx = state.openTabs.findIndex(tab => tab.key === key)
      if (idx <= 0) return state
      const tabs = state.openTabs.slice(idx)
      let nextActiveKey = state.activeTabKey
      if (state.activeTabKey && !tabs.find(tab => tab.key === state.activeTabKey)) {
        nextActiveKey = key
      }
      return { openTabs: tabs, activeTabKey: nextActiveKey }
    })
  },

  closeTabsToRight: (key) => {
    set((state) => {
      const idx = state.openTabs.findIndex(tab => tab.key === key)
      if (idx < 0 || idx === state.openTabs.length - 1) return state
      const tabs = state.openTabs.slice(0, idx + 1)
      let nextActiveKey = state.activeTabKey
      if (state.activeTabKey && !tabs.find(tab => tab.key === state.activeTabKey)) {
        nextActiveKey = key
      }
      return { openTabs: tabs, activeTabKey: nextActiveKey }
    })
  },

  closeOtherTabs: (key) => {
    set((state) => ({
      openTabs: state.openTabs.filter(tab => tab.key === key),
      activeTabKey: key
    }))
  },

  closeAllTabs: () => {
    set(() => ({ openTabs: [], activeTabKey: null }))
  },
}))

export function isPathLoading(store: FileExplorerStore, connectionId: string, path: string): boolean {
  return listIncludes(store.loadingPathsByConnection[connectionId], path)
}
