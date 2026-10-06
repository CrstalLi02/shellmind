import { create } from 'zustand'
import { open as openDialog } from '@tauri-apps/plugin-dialog'
import { invoke } from '@tauri-apps/api/core'
import {
  readDir,
  readFile,
  readTextFile,
  writeTextFile,
  type DirEntry,
} from '@tauri-apps/plugin-fs'
import { useAiPatchStore } from './aiPatchStore'
import { parseClassFile, MAX_CLASS_PARSE_BYTES } from '../api/fileParse'

/** local file node */
export interface LocalFileNode {
  name: string
  path: string
  directory: boolean
  size: number | null
  children?: LocalFileNode[]
  /** whether already load via child node */
  loaded: boolean
  branch?: string | null
}

/** open file tab */
export interface LocalOpenTab {
  key: string
  path: string
  name: string
  content: string
  loading: boolean
  modified: boolean
  error?: string
  language: string
  /** parse type for binary files(like "java-class"), non empty table show content as parse after shape view image(read-only) */
  parsedType?: string
  /** each time external(AI) re- load content when increment, for strong make Monaco Editor refresh */
  contentVersion?: number
}

/** Diff tab: used in editor center compare AI fix edit front after content(local file) */
export interface LocalDiffTab {
  key: string
  /** mark as diff type */
  kind: 'diff'
  /** linked AiPatchPreview id */
  previewId: string
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

export type LocalAnyTab = LocalOpenTab | LocalDiffTab

/** already open local project */
export interface LocalProject {
  id: string
  path: string
  name: string
  branch: string | null
}

export function isLocalDiffTab(tab: LocalAnyTab | null | undefined): tab is LocalDiffTab {
  return !!tab && (tab as LocalDiffTab).kind === 'diff'
}

/** based on extension name push break language */
export function getLanguage(filename: string): string {
  const ext = filename.split('.').pop()?.toLowerCase()
  switch (ext) {
    case 'js': return 'javascript'
    case 'jsx': return 'javascript'
    case 'ts': return 'typescript'
    case 'tsx': return 'typescript'
    case 'json': return 'json'
    case 'html': return 'html'
    case 'css': return 'css'
    case 'scss': return 'scss'
    case 'less': return 'less'
    case 'md': return 'markdown'
    case 'py': return 'python'
    case 'java': return 'java'
    case 'class': return 'java'
    case 'kt': return 'kotlin'
    case 'go': return 'go'
    case 'rs': return 'rust'
    case 'c': return 'c'
    case 'cpp': return 'cpp'
    case 'h': return 'c'
    case 'sh': return 'shell'
    case 'bash': return 'shell'
    case 'yml': return 'yaml'
    case 'yaml': return 'yaml'
    case 'xml': return 'xml'
    case 'sql': return 'sql'
    case 'vue': return 'html'
    case 'php': return 'php'
    case 'rb': return 'ruby'
    case 'swift': return 'swift'
    case 'toml': return 'ini'
    case 'ini': return 'ini'
    case 'conf': return 'ini'
    default: return 'plaintext'
  }
}

/** ignore directory name */
const IGNORED_DIRS = new Set([
  'node_modules', '.git', 'dist', 'build', '.next', '.nuxt',
  'target', '.cache', '__pycache__', '.idea', '.vscode',
  '.gradle', '.mvn', 'venv', '.venv', 'env',
])

/** Maven/Gradle standard Java source set scope */
const JAVA_SOURCE_SCOPES = ['main', 'test', 'integrationTest', 'it', 'e2e']

/** ignore file */
const IGNORED_FILES = new Set([
  '.DS_Store', 'Thumbs.db',
])

/**
 * concat path, handle macOS/Linux path
 */
function joinPath(parent: string, child: string): string {
  if (parent.endsWith('/')) return `${parent}${child}`
  return `${parent}/${child}`
}

function getPathSegments(targetPath: string): string[] {
  return targetPath.replace(/\\/g, '/').replace(/^\/+|\/+$/g, '').split('/').filter(Boolean)
}

function getPathBasename(targetPath: string): string {
  const normalized = targetPath.replace(/\\/g, '/').replace(/\/+$/g, '')
  const segments = normalized.split('/').filter(Boolean)
  return segments[segments.length - 1] || normalized
}

function findJavaSourcePattern(segments: string[]): number {
  for (let i = 0; i <= segments.length - 3; i++) {
    if (
      segments[i] === 'src' &&
      JAVA_SOURCE_SCOPES.includes(segments[i + 1]) &&
      segments[i + 2] === 'java'
    ) {
      return i + 2
    }
  }
  return -1
}

function isInsideJavaSourcePath(dirPath: string): boolean {
  const segments = getPathSegments(dirPath)
  const javaIdx = findJavaSourcePattern(segments)
  if (javaIdx === -1) return false
  return segments.length > javaIdx + 1
}

function filterVisibleEntries(entries: DirEntry[]): DirEntry[] {
  return entries.filter((entry) => {
    if (IGNORED_FILES.has(entry.name)) return false
    if (entry.isDirectory && IGNORED_DIRS.has(entry.name)) return false
    return true
  })
}

async function readVisibleEntries(dirPath: string): Promise<DirEntry[]> {
  const entries = await readDir(dirPath)
  return filterVisibleEntries(entries)
}

/**
 * read file for preview:.class file go byte code parse(server /api/v1/file/parse-class),
 * other remaining file by UTF-8 text read.
 */
async function readFileForPreview(path: string): Promise<{ content: string; parsedType?: string }> {
  const name = getPathBasename(path)
  if (name.toLowerCase().endsWith('.class')) {
    const bytes = await readFile(path)
    if (bytes.length > MAX_CLASS_PARSE_BYTES) {
      throw new Error(`class file too large (${(bytes.length / 1024 / 1024).toFixed(1)}MB > 16MB); preview not supported`)
    }
    const res = await parseClassFile(name, bytes)
    if (res.code === '0000' && res.data?.content) {
      return { content: res.data.content, parsedType: res.data.parsedType ?? 'java-class' }
    }
    throw new Error(res.info || 'Failed to parse class file')
  }
  return { content: await readTextFile(path) }
}

interface LocalFileStore {
  rootPath: string | null
  gitBranch: string | null
  projects: LocalProject[]
  activeProjectId: string | null
  activeLocalProjectId: string | null
  tree: LocalFileNode[]
  expandedPaths: Set<string>
  selectedPath: string | null
  loading: boolean
  error: string | null

  openTabs: LocalAnyTab[]
  activeTabKey: string | null

  openFolder: () => Promise<void>
  closeProject: (projectId: string) => Promise<void>
  switchProject: (projectId: string) => Promise<void>
  clearActiveProject: () => void
  refreshGitBranch: (path?: string) => Promise<void>
  listProjectBranches: (projectId: string) => Promise<string[]>
  switchProjectBranch: (projectId: string, branch: string) => Promise<boolean>
  readDirectory: (dirPath: string, maxDepth?: number) => Promise<LocalFileNode[]>
  toggleDirectory: (path: string) => Promise<void>
  expandDirectory: (path: string) => Promise<void>
  openFile: (path: string) => Promise<void>
  updateFileContent: (key: string, content: string) => void
  saveFile: (key: string) => Promise<boolean>
  setActiveTab: (key: string) => void
  closeTab: (key: string) => void
  closeAllTabs: () => void
  closeOtherTabs: (key: string) => void
  /** close all tabs to the left of this one */
  closeTabsToLeft: (key: string) => void
  /** close all tabs to the right of this one */
  closeTabsToRight: (key: string) => void
  refreshDirectory: (path: string) => Promise<void>
  /** force-reload the active tab file contents(AI fix edit file back refresh editor) */
  reloadActiveFile: () => Promise<void>
  /** reload a tab by file path, return re- load after text */
  reloadFileByPath: (path: string) => Promise<string | null>
  /** read file content(do not open tab), for AI change back get afterContent */
  readFileContent: (path: string) => Promise<string | null>
  /** by file path restore point set content, merge down disk save */
  restoreFileContent: (path: string, content: string) => Promise<boolean>
  setSelectedPath: (path: string | null) => void
  closeFolder: () => void
  restoreFolder: () => Promise<boolean>
  /** open local file Diff tab */
  openDiffTab: (previewId: string) => void
  /** close local file Diff tab */
  closeDiffTab: (previewId: string) => void
  /** expand file tree to point set path directory(ensure file at tree center ok see) */
  expandPathTo: (filePath: string) => Promise<void>
}

const STORAGE_KEY = 'shellmind-local-projects'

interface SavedProjects {
  projects: LocalProject[]
  activeProjectId: string | null
  activeLocalProjectId: string | null
}

/** from localStorage restore the last opened folder */
function loadSavedProjects(): SavedProjects | null {
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    if (!raw) {
      const legacyPath = localStorage.getItem('shellmind-local-folder')
      if (!legacyPath) return null
      return {
        projects: [{
          id: legacyPath,
          path: legacyPath,
          name: getPathBasename(legacyPath),
          branch: null,
        }],
        activeProjectId: legacyPath,
        activeLocalProjectId: legacyPath,
      }
    }

    const parsed = JSON.parse(raw) as Partial<SavedProjects>
    if (!Array.isArray(parsed.projects) || parsed.projects.length === 0) return null
    const projects = parsed.projects
      .filter(project => typeof project?.path === 'string' && project.path)
      .map(project => ({
        id: project.id || project.path!,
        path: project.path!,
        name: project.name || getPathBasename(project.path!),
        branch: project.branch ?? null,
      }))
    if (projects.length === 0) return null
    return {
      projects,
      activeProjectId: projects.some(p => p.id === parsed.activeProjectId)
        ? parsed.activeProjectId!
        : projects[0].id,
      activeLocalProjectId: projects.some(p => p.id === parsed.activeProjectId)
        ? parsed.activeProjectId!
        : projects[0].id,
    }
  } catch {
    return null
  }
}

/** save folder path to localStorage */
function saveProjects(projects: LocalProject[], activeProjectId: string | null) {
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify({ projects, activeProjectId }))
    localStorage.removeItem('shellmind-local-folder')
  } catch { /* ignore */ }
}

/** clear remove save folder */
function clearSavedFolder() {
  try {
    localStorage.removeItem(STORAGE_KEY)
    localStorage.removeItem('shellmind-local-folder')
  } catch { /* ignore */ }
}

async function readGitBranch(projectPath: string): Promise<string | null> {
  let currentPath = projectPath.replace(/\/+$/, '')

  try {
    const result = await invoke<{ success: boolean; stdout: string; stderr: string }>(
      'execute_shell_cmd',
      {
        command: 'git rev-parse --abbrev-ref HEAD',
        cwd: currentPath,
        timeoutMs: 2000,
        autoBackground: false,
      },
    )
    const branch = result.stdout.trim()
    if (result.success && branch && branch !== 'HEAD') return branch
  } catch { /* fall back to .git/HEAD below */ }

  for (let depth = 0; depth < 6; depth += 1) {
    try {
      const head = (await readTextFile(joinPath(currentPath, '.git/HEAD'))).trim()
      const branchMatch = head.match(/^ref:\s+refs\/heads\/(.+)$/)

      if (branchMatch) return branchMatch[1]
      return head.replace(/^refs\//, '') || null
    } catch {
      const parentPath = currentPath.split('/').slice(0, -1).join('/')
      if (!parentPath || parentPath === currentPath) return null
      currentPath = parentPath
    }
  }

  return null
}

export const useLocalFileStore = create<LocalFileStore>((set, get) => ({
  rootPath: null,
  gitBranch: null,
  projects: [],
  activeProjectId: null,
  activeLocalProjectId: null,
  tree: [],
  expandedPaths: new Set(),
  selectedPath: null,
  loading: false,
  error: null,
  openTabs: [],
  activeTabKey: null,

  openFolder: async () => {
    try {
      const selected = await openDialog({
        directory: true,
        multiple: false,
        title: 'Choose folder',
      })

      if (!selected || typeof selected !== 'string') return

      set({ loading: true, error: null })

      const tree = await get().readDirectory(selected, 1)
      const gitBranch = await readGitBranch(selected)
      const project: LocalProject = {
        id: selected,
        path: selected,
        name: getPathBasename(selected),
        branch: gitBranch,
      }
      const existingProjects = get().projects
      const projects = existingProjects.some(p => p.id === selected)
        ? existingProjects.map(p => p.id === selected ? project : p)
        : [...existingProjects, project]

      set({
        rootPath: selected,
        gitBranch,
        projects,
        activeProjectId: selected,
        activeLocalProjectId: selected,
        tree,
        expandedPaths: new Set([selected]),
        loading: false,
        openTabs: [],
        activeTabKey: null,
        selectedPath: null,
      })
      saveProjects(projects, selected)
    } catch (err: any) {
      set({ loading: false, error: err?.message || 'Failed to open folder' })
    }
  },

  closeProject: async (projectId) => {
    const { projects, activeProjectId } = get()
    if (!projects.some(item => item.id === projectId)) return

    const remainingProjects = projects.filter(item => item.id !== projectId)
    const nextActiveProjectId = activeProjectId === projectId ? remainingProjects[0]?.id ?? null : activeProjectId
    saveProjects(remainingProjects, nextActiveProjectId)
    set({ projects: remainingProjects, activeProjectId: nextActiveProjectId })

    if (remainingProjects.length === 0) {
      set({
        rootPath: null,
        gitBranch: null,
        activeProjectId: null,
        activeLocalProjectId: null,
        tree: [],
        expandedPaths: new Set(),
        selectedPath: null,
        openTabs: [],
        activeTabKey: null,
      })
      return
    }

    if (activeProjectId === projectId) {
      if (nextActiveProjectId) await get().switchProject(nextActiveProjectId)
    }
  },

  switchProject: async (projectId) => {
    const project = get().projects.find(p => p.id === projectId)
    if (!project || project.id === get().activeProjectId) return

    try {
      set({ loading: true, error: null })
      const [tree, gitBranch] = await Promise.all([
        get().readDirectory(project.path, 1),
        readGitBranch(project.path),
      ])
      const nextProject = { ...project, branch: gitBranch }

      set({
        rootPath: project.path,
        gitBranch,
        activeProjectId: project.id,
        activeLocalProjectId: project.id,
        projects: get().projects.map(p => p.id === project.id ? nextProject : p),
        tree,
        expandedPaths: new Set([project.path]),
        loading: false,
        openTabs: [],
        activeTabKey: null,
        selectedPath: null,
      })
      saveProjects(get().projects, project.id)
    } catch (err: any) {
      set({ loading: false, error: err?.message || 'Failed to switch project' })
    }
  },

  clearActiveProject: () => {
    set({
      rootPath: null,
      gitBranch: null,
      activeProjectId: null,
      activeLocalProjectId: null,
      tree: [],
      expandedPaths: new Set(),
      selectedPath: null,
      openTabs: [],
      activeTabKey: null,
    })
  },

  readDirectory: async (dirPath: string, maxDepth = 1): Promise<LocalFileNode[]> => {
    const result: LocalFileNode[] = []

    try {
      const entries = await readVisibleEntries(dirPath)

      for (const entry of entries) {
        const entryPath = joinPath(dirPath, entry.name)
        if (!entry.isDirectory) {
          result.push({
            name: entry.name,
            path: entryPath,
            directory: false,
            size: null,
            loaded: false,
          })
          continue
        }

        if (isInsideJavaSourcePath(entryPath)) {
          let currentPath = entryPath
          const mergedSegments = [getPathBasename(entryPath)]

          try {
            while (true) {
              const childEntries = await readVisibleEntries(currentPath)
              const childDirs = childEntries.filter((item) => item.isDirectory)
              const childFiles = childEntries.filter((item) => !item.isDirectory)
              if (childFiles.length > 0 || childDirs.length !== 1) break

              const onlyChild = childDirs[0]
              currentPath = joinPath(currentPath, onlyChild.name)
              mergedSegments.push(onlyChild.name)
            }
          } catch {
            // keep the current level if directory scan fails, avoid whole tree render center break
          }

          const node: LocalFileNode = {
            name: mergedSegments.join('.'),
            path: currentPath,
            directory: true,
            size: null,
            loaded: false,
          }

          if (maxDepth > 0) {
            try {
              node.children = await get().readDirectory(currentPath, maxDepth - 1)
              node.loaded = true
            } catch {
              node.children = []
              node.loaded = false
            }
          }

          result.push(node)
          continue
        }

        const node: LocalFileNode = {
          name: entry.name,
          path: entryPath,
          directory: true,
          size: null,
          loaded: false,
        }

        if (maxDepth > 0) {
          try {
            node.children = await get().readDirectory(entryPath, maxDepth - 1)
            node.loaded = true
          } catch {
            node.children = []
            node.loaded = false
          }
        }

        result.push(node)
      }
    } catch (err) {
      console.error('readDirectory error:', dirPath, err)
    }

    // directory at front, file at back
    result.sort((a, b) => {
      if (a.directory !== b.directory) return a.directory ? -1 : 1
      return a.name.localeCompare(b.name)
    })

    return result
  },

  refreshGitBranch: async (path) => {
    const projectPath = path ?? get().rootPath
    if (!projectPath) return

    const gitBranch = await readGitBranch(projectPath)
    set((state) => {
      const projects = state.projects.map(project =>
        project.path === projectPath ? { ...project, branch: gitBranch } : project
      )
      saveProjects(projects, state.activeProjectId)
      return { gitBranch, projects }
    })
  },

  listProjectBranches: async (projectId) => {
    const project = get().projects.find(item => item.id === projectId)
    if (!project) return []

    try {
      const result = await invoke<{ success: boolean; stdout: string; stderr: string }>(
        'execute_shell_cmd',
        {
          command: "git branch --format='%(refname:short)'",
          cwd: project.path,
          timeoutMs: 2000,
          autoBackground: false,
        },
      )
      if (!result.success) return []
      const branches = result.stdout
        .split('\n')
        .map(branch => branch.trim())
        .filter(Boolean)
      return project.branch && !branches.includes(project.branch) ? [project.branch, ...branches] : branches
    } catch {
      return project.branch ? [project.branch] : []
    }
  },

  switchProjectBranch: async (projectId, branch) => {
    if (!/^[A-Za-z0-9][A-Za-z0-9._/-]*$/.test(branch)) return false
    const project = get().projects.find(item => item.id === projectId)
    if (!project) return false

    try {
      const result = await invoke<{ success: boolean; stdout: string; stderr: string }>(
        'execute_shell_cmd',
        {
          command: `git checkout ${branch}`,
          cwd: project.path,
          timeoutMs: 10000,
          autoBackground: false,
        },
      )
      if (!result.success) return false

      const gitBranch = await readGitBranch(project.path)
      set(state => ({
        gitBranch: state.activeProjectId === projectId ? gitBranch : state.gitBranch,
        projects: state.projects.map(item => item.id === projectId ? { ...item, branch: gitBranch } : item),
      }))
      saveProjects(get().projects, get().activeProjectId)
      if (get().rootPath === project.path) {
        await get().refreshDirectory(project.path)
      }
      return true
    } catch {
      return false
    }
  },

  toggleDirectory: async (path: string) => {
    const expanded = new Set(get().expandedPaths)
    if (expanded.has(path)) {
      expanded.delete(path)
      set({ expandedPaths: expanded })
    } else {
      expanded.add(path)
      set({ expandedPaths: expanded })
      // load directory content
      await get().expandDirectory(path)
    }
  },

  expandDirectory: async (path: string) => {
    /**
     * at tree center find to goal mark directory node merge load other children.
     * use not ok change new ensure React detect to change.
     */
    const loadChildren = (nodes: LocalFileNode[]): LocalFileNode[] => {
      return nodes.map((node) => {
        if (node.path === path && node.directory && !node.loaded) {
          // async load child node
          get().readDirectory(path, 1).then((children) => {
            set((state) => ({
              tree: updateNodeInTree(state.tree, path, { children, loaded: true }),
            }))
          }).catch(() => {})
          return { ...node, children: [], loaded: false } // placeholder, actual by up side set update
        }
        if (node.children && node.children.length > 0) {
          return { ...node, children: loadChildren(node.children) }
        }
        return node
      })
    }

    const newTree = loadChildren(get().tree)
    // if has change just update(avoid not must need render)
    set({ tree: newTree })
  },

  openFile: async (path: string) => {
    const key = path
    const existing = get().openTabs.find((t) => t.key === key)
    if (existing) {
      // file already open: activate tab merge set position to file tree
      set({ activeTabKey: key, selectedPath: path })
      // expand the file tree to the file directory
      await get().expandPathTo(path)
      return
    }

    const name = path.split('/').pop() || path
    const language = getLanguage(name)

    set((state) => ({
      openTabs: [...state.openTabs, {
        key,
        path,
        name,
        content: '',
        loading: true,
        modified: false,
        language,
      }],
      activeTabKey: key,
      selectedPath: path,
    }))
    // expand the file tree to the file directory
    await get().expandPathTo(path)

    try {
      const { content, parsedType } = await readFileForPreview(path)
      set((state) => ({
        openTabs: state.openTabs.map((t) =>
          t.key === key ? { ...t, content, parsedType, loading: false } : t
        ),
      }))
    } catch (err: any) {
      console.error('readFileForPreview error:', path, err)
      set((state) => ({
        openTabs: state.openTabs.map((t) =>
          t.key === key ? { ...t, loading: false, error: err?.message || 'Unable to read file' } : t
        ),
      }))
    }
  },

  updateFileContent: (key, content) => {
    set((state) => ({
      openTabs: state.openTabs.map((t) =>
        !isLocalDiffTab(t) && t.key === key && t.content !== content
          ? { ...t, content, modified: true }
          : t
      ),
    }))
  },

  saveFile: async (key: string) => {
    const tab = get().openTabs.find((t) => !isLocalDiffTab(t) && t.key === key) as LocalOpenTab | undefined
    if (!tab) return false
    // parse view image(like class byte code shape) read-only, forbid stop down disk overwrite two in make file
    if (tab.parsedType) {
      console.warn('[localFileStore] saveFile blocked: parsed readonly view', tab.path)
      return false
    }

    try {
      await writeTextFile(tab.path, tab.content)
      set((state) => ({
        openTabs: state.openTabs.map((t) =>
          t.key === key ? { ...t, modified: false } : t
        ),
      }))
      return true
    } catch (err: any) {
      set((state) => ({
        openTabs: state.openTabs.map((t) =>
          t.key === key ? { ...t, error: err?.message || 'Save failed' } : t
        ),
      }))
      return false
    }
  },

  setActiveTab: (key) => {
    const tab = get().openTabs.find((t) => t.key === key)
    set({ activeTabKey: key, selectedPath: tab?.path || null })
  },

  closeTab: (key) => {
    set((state) => {
      const tabs = state.openTabs.filter((t) => t.key !== key)
      let nextKey = state.activeTabKey
      if (state.activeTabKey === key) {
        nextKey = tabs.length ? tabs[tabs.length - 1].key : null
      }
      return { openTabs: tabs, activeTabKey: nextKey }
    })
  },

  closeAllTabs: () => set({ openTabs: [], activeTabKey: null }),

  closeOtherTabs: (key) =>
    set((state) => ({
      openTabs: state.openTabs.filter((t) => t.key === key),
      activeTabKey: key,
    })),

  closeTabsToLeft: (key) =>
    set((state) => {
      const idx = state.openTabs.findIndex((t) => t.key === key)
      if (idx <= 0) return {}
      const openTabs = state.openTabs.slice(idx)
      const activeTabKey =
        state.activeTabKey && openTabs.some((t) => t.key === state.activeTabKey)
          ? state.activeTabKey
          : key
      return { openTabs, activeTabKey }
    }),

  closeTabsToRight: (key) =>
    set((state) => {
      const idx = state.openTabs.findIndex((t) => t.key === key)
      if (idx === -1 || idx === state.openTabs.length - 1) return {}
      const openTabs = state.openTabs.slice(0, idx + 1)
      const activeTabKey =
        state.activeTabKey && openTabs.some((t) => t.key === state.activeTabKey)
          ? state.activeTabKey
          : key
      return { openTabs, activeTabKey }
    }),

  refreshDirectory: async (path: string) => {
    const { readDirectory, rootPath, expandedPaths } = get()

    if (path === rootPath) {
      // tree save is root directory children(without rootPath node this body),
      // updateNodeInTree always far match not to root node → must whole body replace
      const children = await readDirectory(path, 1)
      const tree = await refreshExpandedNodes(children, expandedPaths, readDirectory)
      const gitBranch = await readGitBranch(path)
      set({ tree, gitBranch })
      return
    }

    const children = await readDirectory(path, 1)
    const refreshedChildren = await refreshExpandedNodes(children, expandedPaths, readDirectory)
    set((state) => ({
      tree: updateNodeInTree(state.tree, path, { children: refreshedChildren, loaded: true }),
    }))
  },

  reloadActiveFile: async () => {
    const { activeTabKey, openTabs } = get()
    if (!activeTabKey) return
    const tab = openTabs.find((t) => t.key === activeTabKey)
    if (!tab) return
    try {
      const { content, parsedType } = await readFileForPreview(tab.path)
      set((state) => ({
        openTabs: state.openTabs.map((t) =>
          t.key === activeTabKey ? { ...t, content, parsedType, loading: false, modified: false, contentVersion: ((t as LocalOpenTab).contentVersion ?? 0) + 1 } : t
        ),
      }))
    } catch (err: any) {
      console.error('[localFileStore] reloadActiveFile error:', tab.path, err)
    }
  },

  reloadFileByPath: async (path: string) => {
    const tab = get().openTabs.find((t) => t.path === path)
    if (!tab) return null
    try {
      const { content, parsedType } = await readFileForPreview(path)
      set((state) => ({
        openTabs: state.openTabs.map((t) =>
          t.path === path ? { ...t, content, parsedType, loading: false, modified: false, error: undefined, contentVersion: ((t as LocalOpenTab).contentVersion ?? 0) + 1 } : t
        ),
      }))
      return content
    } catch (err: any) {
      console.error('[localFileStore] reloadFileByPath error:', path, err)
      return null
    }
  },

  readFileContent: async (path: string) => {
    try {
      const { content } = await readFileForPreview(path)
      return content
    } catch (err: any) {
      console.error('[localFileStore] readFileContent error:', path, err)
      return null
    }
  },

  restoreFileContent: async (path: string, content: string) => {
    try {
      await writeTextFile(path, content)
      set((state) => ({
        openTabs: state.openTabs.map((t) =>
          t.path === path ? { ...t, content, loading: false, modified: false, error: undefined } : t
        ),
      }))
      return true
    } catch (err: any) {
      console.error('[localFileStore] restoreFileContent error:', path, err)
      set((state) => ({
        openTabs: state.openTabs.map((t) =>
          t.path === path ? { ...t, error: err?.message || 'Failed to restore file' } : t
        ),
      }))
      return false
    }
  },

  setSelectedPath: (path) => set({ selectedPath: path }),

  closeFolder: async () => {
    const { projects, activeProjectId } = get()
    const activeProject = projects.find(project => project.id === activeProjectId)
    const remainingProjects = activeProject
      ? projects.filter(project => project.id !== activeProject.id)
      : projects

    if (remainingProjects.length === 0) {
      clearSavedFolder()
      set({
        rootPath: null,
        gitBranch: null,
        projects: [],
        activeProjectId: null,
        tree: [],
        expandedPaths: new Set(),
        selectedPath: null,
        openTabs: [],
        activeTabKey: null,
      })
      return
    }

    await get().switchProject(remainingProjects[0].id)
    saveProjects(get().projects, get().activeProjectId)
  },

  restoreFolder: async (): Promise<boolean> => {
    const saved = loadSavedProjects()
    if (!saved) return false

      const projectsWithBranches = await Promise.all(saved.projects.map(async project => ({
        ...project,
        branch: await readGitBranch(project.path),
      })))
      const activeProject = projectsWithBranches.find(project => project.id === saved.activeProjectId) || projectsWithBranches[0]

    try {
      set({ loading: true, error: null })
      const tree = await get().readDirectory(activeProject.path, 1)
      const gitBranch = await readGitBranch(activeProject.path)
      const activeProjectWithBranch = { ...activeProject, branch: gitBranch }
      const projects = projectsWithBranches.map(project =>
        project.id === activeProjectWithBranch.id ? activeProjectWithBranch : project
      )
      set({
        rootPath: activeProject.path,
        gitBranch,
        projects,
        activeProjectId: activeProjectWithBranch.id,
        tree,
        expandedPaths: new Set([activeProject.path]),
        loading: false,
      })
      saveProjects(projects, activeProjectWithBranch.id)
      return true
    } catch (err: any) {
      // save path ok can not save at, clear it
      clearSavedFolder()
      set({ loading: false, error: null })
      return false
    }
  },

  /** open local file Diff tab: via AiPatchPreview id find preview, create LocalDiffTab */
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
    const language = getLanguage(name)

    const newTab: LocalDiffTab = {
      kind: 'diff',
      key,
      previewId: preview.id,
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

  /** close local file Diff tab */
  closeDiffTab: (previewId) => {
    const key = `diff:${previewId}`
    get().closeTab(key)
  },

  /** expand the file tree to the directory of the given path(ensure the file is visible in the tree and selected) */
  expandPathTo: async (filePath: string) => {
    const { rootPath, expandedPaths } = get()
    if (!rootPath) return

    // from root directory start, each level expand to file at directory
    const normalized = filePath.replace(/\\/g, '/')
    const segments = normalized.replace(rootPath + '/', '').replace(rootPath, '').split('/').filter(Boolean)
    // file name at most back, directory path is remove most back one segment so outside all
    const dirSegments = segments.slice(0, -1)

    const newExpanded = new Set(expandedPaths)
    let currentPath = rootPath
    let needsTreeUpdate = false

    for (const seg of dirSegments) {
      currentPath = joinPath(currentPath, seg)
      if (!newExpanded.has(currentPath)) {
        newExpanded.add(currentPath)
        needsTreeUpdate = true
        // ensure the directory already load child node
        await get().expandDirectory(currentPath)
      }
    }

    if (needsTreeUpdate) {
      set({ expandedPaths: newExpanded, selectedPath: filePath })
    } else {
      set({ selectedPath: filePath })
    }
  },
}))

/**
 * not ok change new: find the node at the given path and merge new properties
 */
function updateNodeInTree(
  nodes: LocalFileNode[],
  targetPath: string,
  updates: Partial<LocalFileNode>,
): LocalFileNode[] {
  return nodes.map((node) => {
    if (node.path === targetPath) {
      return { ...node, ...updates }
    }
    if (node.children) {
      return { ...node, children: updateNodeInTree(node.children, targetPath, updates) }
    }
    return node
  })
}

/**
 * recursive refresh already expand(or already load via children) directory,
 * return refresh after node list. file/ deleted directories disappear from the tree naturally.
 */
async function refreshExpandedNodes(
  nodes: LocalFileNode[],
  expandedPaths: Set<string>,
  readDir: (p: string, maxDepth?: number) => Promise<LocalFileNode[]>,
): Promise<LocalFileNode[]> {
  return Promise.all(nodes.map(async (node) => {
    if (!node.directory || !(node.loaded || expandedPaths.has(node.path))) return node
    try {
      const children = await readDir(node.path, 1)
      const refreshedChildren = await refreshExpandedNodes(children, expandedPaths, readDir)
      return { ...node, children: refreshedChildren, loaded: true }
    } catch {
      // directory read fail(ok can already delete): keep raw node, whether clear lose by parent refresh result decide set
      return node
    }
  }))
}
