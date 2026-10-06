import { useEffect, useMemo, useState } from 'react'
import { useThemeStore } from '../stores/themeStore'
import { useAgentStore } from '../stores/agentStore'
import { useLocalFileStore } from '../stores/localFileStore'
import type { AgentSession, ConversationProject } from '../types'

interface HistoryPanelProps {
  width?: number
}

interface MenuState { kind: 'project' | 'session'; id: string; x: number; y: number }
interface ProjectDialogState {
  mode: 'create' | 'edit' | 'add-projects'
  projectId?: string
  name: string
  localProjectId: string | null
}

export function HistoryPanel({ width = 320 }: HistoryPanelProps) {
  const { colors } = useThemeStore()
  const {
    sessions, currentSessionId, setCurrentSession, toggleHistoryPanel, currentAgentId, loadingSessionIds,
    newConversation, conversationProjects, activeConversationProjectId, setActiveConversationProject,
    createConversationProject, updateConversationProject, deleteConversationProject, toggleConversationProjectPinned, toggleSessionPinned,
  } = useAgentStore()
  const localProjects = useLocalFileStore(s => s.projects)
  const activeLocalProjectId = useLocalFileStore(s => s.activeLocalProjectId)
  const openFolder = useLocalFileStore(s => s.openFolder)

  const [expandedProjects, setExpandedProjects] = useState(new Set(['default']))
  const [menu, setMenu] = useState<MenuState | null>(null)
  const [dialog, setDialog] = useState<ProjectDialogState | null>(null)
  const [searchQuery, setSearchQuery] = useState('')
  const [viewMode, setViewMode] = useState<'chats' | 'projects'>('projects')
  const [openingFolder, setOpeningFolder] = useState(false)

  useEffect(() => {
    if (!menu) return
    const close = () => setMenu(null)
    window.addEventListener('pointerdown', close)
    return () => window.removeEventListener('pointerdown', close)
  }, [menu])

  const visibleConversationProjects = conversationProjects

  const sortedProjects = useMemo(() => [...visibleConversationProjects].sort((a, b) =>
    !!a.pinnedAt !== !!b.pinnedAt ? (a.pinnedAt ? -1 : 1) : a.createdAt - b.createdAt
  ), [visibleConversationProjects])

  const primaryLocalProject = (project: ConversationProject) => {
    return localProjects.find(item => item.id === project.localProjectId)
  }

  const filteredProjects = useMemo(() => {
    const query = searchQuery.trim().toLowerCase()
    if (!query) return sortedProjects
    return sortedProjects.filter(project => {
      const linked = primaryLocalProject(project)
      return project.name.toLowerCase().includes(query) || (linked?.name.toLowerCase().includes(query) ?? false)
    })
  }, [sortedProjects, searchQuery, localProjects, activeLocalProjectId])

  const title = (messages: AgentSession['messages']) =>
    messages.find(message => message.role === 'user')?.content?.replace(/<[^>]+>/g, '').slice(0, 44) || 'New chat'

  const pinnedSessions = useMemo(() => Array.from(sessions.values())
    .filter(session => session.pinnedAt && (!session.projectId || visibleConversationProjects.some(project => project.id === session.projectId)))
    .sort((a, b) => (b.pinnedAt || 0) - (a.pinnedAt || 0)), [sessions, visibleConversationProjects])

  const filteredPinnedSessions = useMemo(() => {
    const query = searchQuery.trim().toLowerCase()
    if (!query) return pinnedSessions
    return pinnedSessions.filter(session => title(session.messages).toLowerCase().includes(query))
  }, [pinnedSessions, searchQuery])

  const sessionsByProject = useMemo(() => {
    const groups = new Map<string, AgentSession[]>()
    visibleConversationProjects.forEach(project => groups.set(project.id, []))
    for (const session of sessions.values()) {
      const key = session.projectId || 'default'
      if (!groups.has(key)) groups.set(key, [])
      groups.get(key)!.push(session)
    }
    for (const list of groups.values()) {
      list.sort((a, b) => {
        if (!!a.pinnedAt !== !!b.pinnedAt) return a.pinnedAt ? -1 : 1
        const aTime = a.messages.at(-1)?.timestamp || a.createdAt
        const bTime = b.messages.at(-1)?.timestamp || b.createdAt
        return bTime - aTime
      })
    }
    return groups
  }, [visibleConversationProjects, sessions])

  const time = (timestamp?: number) => timestamp
    ? new Date(timestamp).toLocaleString('en-US', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' })
    : ''
  const openProject = (project: ConversationProject) => {
    setActiveConversationProject(project.id)
    setExpandedProjects(prev => new Set(prev).add(project.id))
    const linkedProject = primaryLocalProject(project)
    if (linkedProject) {
      window.dispatchEvent(new CustomEvent('open-local-project'))
    }
  }
  const openMenu = (event: React.MouseEvent, kind: MenuState['kind'], id: string) => {
    event.preventDefault(); event.stopPropagation()
    setMenu({ kind, id, x: event.clientX, y: event.clientY })
  }
  const submitDialog = () => {
    if (!dialog || !dialog.name.trim()) return
    if (dialog.mode === 'create') {
      const primaryProject = localProjects.find(item => item.id === dialog.localProjectId)
      const id = createConversationProject(dialog.name, primaryProject?.path || null, primaryProject?.id || null)
      setExpandedProjects(prev => new Set(prev).add(id))
    } else if (dialog.projectId) {
      const primaryProject = localProjects.find(item => item.id === dialog.localProjectId)
      updateConversationProject(dialog.projectId, {
        name: dialog.name,
        path: primaryProject?.path || null,
        localProjectId: primaryProject?.id || null,
      })
    }
    setDialog(null)
  }

  const selectLinkedProject = (projectId: string) => {
    if (!dialog) return
    setDialog({
      ...dialog,
      localProjectId: dialog.localProjectId === projectId ? null : projectId,
    })
  }

  const openLocalProjectDialog = async () => {
    if (!dialog || openingFolder) return
    setOpeningFolder(true)
    const beforeIds = new Set(useLocalFileStore.getState().projects.map(project => project.id))
    await openFolder()
    const newProject = useLocalFileStore.getState().projects.find(project => !beforeIds.has(project.id))
    if (newProject) {
      setDialog({
        ...dialog,
        localProjectId: newProject.id,
      })
    }
    setOpeningFolder(false)
  }

  return (
    <div className="flex flex-col h-full flex-shrink-0" style={{ width, backgroundColor: colors.bgPrimary, borderLeft: `1px solid ${colors.border}` }}>
      <div className="px-3 py-3 flex-shrink-0" style={{ borderBottom: `1px solid ${colors.border}` }}>
        <div className="flex items-center gap-2">
          <div className="relative flex-1">
            <svg className="absolute left-2 top-1/2 w-3.5 h-3.5 -translate-y-1/2 pointer-events-none" viewBox="0 0 24 24" fill="none" stroke={colors.textDim} strokeWidth="2"><circle cx="11" cy="11" r="7" /><path d="m20 20-3.5-3.5" /></svg>
            <input
              value={searchQuery}
              onChange={event => setSearchQuery(event.target.value)}
              className="w-full h-8 pl-7 pr-2 rounded-lg border text-[12px] outline-none"
              style={{ backgroundColor: colors.bgInput, borderColor: colors.border, color: colors.text }}
              placeholder="Search chats or projects"
            />
          </div>
          <button onClick={toggleHistoryPanel} className="p-1 rounded-md hover:bg-black/10 transition-colors" style={{ color: colors.textDim }}>
            <svg className="w-4 h-4" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2"><line x1="18" y1="6" x2="6" y2="18" /><line x1="6" y1="6" x2="18" y2="18" /></svg>
          </button>
        </div>
        <div className="mt-2 p-0.5 rounded-lg flex" style={{ backgroundColor: colors.bgTertiary }}>
          {([['chats', 'Chat'], ['projects', 'Project']] as const).map(([value, label]) => (
            <button
              key={value}
              onClick={() => setViewMode(value)}
              className={`flex-1 h-7 rounded-md text-[11px] font-medium transition-all ${viewMode === value ? 'shadow-sm' : ''}`}
              style={{ backgroundColor: viewMode === value ? colors.bgPrimary : 'transparent', color: viewMode === value ? colors.accent : colors.textDim }}
            >
              {label}
            </button>
          ))}
        </div>
      </div>

      {menu && (
        <div
          className="fixed z-[80] min-w-[160px] rounded-lg border shadow-xl py-1"
          style={{ left: Math.min(menu.x, window.innerWidth - 180), top: Math.min(menu.y, window.innerHeight - 220), backgroundColor: colors.bgPrimary, borderColor: colors.border }}
          onPointerDown={event => event.stopPropagation()}
        >
          {menu.kind === 'project' ? (
            <>
              <button className="w-full text-left px-3 py-1.5 text-[11px] hover:bg-black/5" style={{ color: colors.text }} onClick={() => {
                if (currentAgentId) void newConversation(currentAgentId, menu.id)
                setMenu(null)
              }}>New chat</button>
              <button className="w-full text-left px-3 py-1.5 text-[11px] hover:bg-black/5" style={{ color: colors.text }} onClick={() => {
                const project = conversationProjects.find(item => item.id === menu.id)
                if (project) setDialog({ mode: 'add-projects', projectId: project.id, name: project.name, localProjectId: project.localProjectId || null })
                setMenu(null)
              }}>Add project</button>
              <button className="w-full text-left px-3 py-1.5 text-[11px] hover:bg-black/5" style={{ color: colors.text }} onClick={() => {
                const project = conversationProjects.find(item => item.id === menu.id)
                if (project) setDialog({ mode: 'edit', projectId: project.id, name: project.name, localProjectId: project.localProjectId || null })
                setMenu(null)
              }}>Edit project</button>
              <button className="w-full text-left px-3 py-1.5 text-[11px] hover:bg-black/5" style={{ color: colors.text }} onClick={() => { deleteConversationProject(menu.id); setMenu(null) }}>Delete project</button>
              <button className="w-full text-left px-3 py-1.5 text-[11px] hover:bg-black/5" style={{ color: colors.text }} onClick={() => { toggleConversationProjectPinned(menu.id); setMenu(null) }}>
                {conversationProjects.find(item => item.id === menu.id)?.pinnedAt ? 'Unpin' : 'Pin'}
              </button>
            </>
          ) : (
            <>
              <button className="w-full text-left px-3 py-1.5 text-[11px] hover:bg-black/5" style={{ color: colors.text }} onClick={() => { toggleSessionPinned(menu.id); setMenu(null) }}>
                {sessions.get(menu.id)?.pinnedAt ? 'Unpin' : 'Pin'}
              </button>
              <button className="w-full text-left px-3 py-1.5 text-[11px] hover:bg-black/5" style={{ color: colors.text }} onClick={() => {
                const session = sessions.get(menu.id)
                if (session?.projectId) setActiveConversationProject(session.projectId)
                setMenu(null)
              }}>Set as current project</button>
            </>
          )}
        </div>
      )}

      <div className="flex-1 overflow-y-auto py-2">
        {viewMode === 'projects' && (
          <div className="flex items-center justify-between px-3 pt-1 pb-1">
            <span className="text-[10px] font-medium tracking-wide" style={{ color: colors.textDim }}>Projects · {filteredProjects.length}</span>
            <button
              onClick={() => setDialog({ mode: 'create', name: '', localProjectId: activeLocalProjectId || null })}
              className="w-5 h-5 rounded flex items-center justify-center hover:bg-black/10"
              style={{ color: colors.textDim }}
              title="New project"
            >
              <svg className="w-3.5 h-3.5" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5"><path d="M12 5v14M5 12h14" /></svg>
            </button>
          </div>
        )}
        <div className="px-3 pt-1 pb-1 text-[10px] font-medium tracking-wide" style={{ color: colors.textDim }}>Pinned · {filteredPinnedSessions.length}</div>
        {filteredPinnedSessions.length === 0 ? (
          <div className="px-3 pb-2 text-[10px]" style={{ color: colors.textDim }}>No pinned chats</div>
        ) : (
          <div className="mx-3 mb-2 space-y-1">
            {filteredPinnedSessions.map(session => {
              const active = session.id === currentSessionId
              return (
                <button
                  key={session.id}
                  onClick={() => { setCurrentSession(session.id); if (session.projectId) setActiveConversationProject(session.projectId) }}
                  className="w-full px-2.5 py-2 rounded-lg text-left transition-all"
                  style={{ backgroundColor: active ? `${colors.accent}10` : colors.bgSecondary, border: `1px solid ${active ? `${colors.accent}40` : colors.border}` }}
                >
                  <span className="text-[11px] font-medium truncate block" style={{ color: active ? colors.accent : colors.text }}>{title(session.messages)}</span>
                  {loadingSessionIds.includes(session.id) && <span className="text-[9px] mt-1 block" style={{ color: colors.accent }}>Running</span>}
                  <span className="text-[9px] mt-1 block" style={{ color: colors.textDim }}>{time(session.messages.at(-1)?.timestamp)} · {session.messages.length} msgs</span>
                </button>
              )
            })}
          </div>
        )}
        {viewMode === 'chats' && (
          <>
            <div className="px-3 pt-2 pb-1 text-[10px] font-medium tracking-wide" style={{ color: colors.textDim }}>All chats</div>
            {Array.from(sessions.values())
              .filter(session => visibleConversationProjects.some(project => project.id === (session.projectId || 'default')))
              .filter(session => !searchQuery.trim() || title(session.messages).toLowerCase().includes(searchQuery.trim().toLowerCase()))
              .map(session => {
                const active = session.id === currentSessionId
                return (
                  <button
                    key={session.id}
                    onClick={() => { setCurrentSession(session.id); if (session.projectId) setActiveConversationProject(session.projectId) }}
                    className="w-[calc(100%-24px)] mx-3 my-1 px-3 py-2.5 rounded-lg text-left transition-all"
                    style={{ backgroundColor: active ? `${colors.accent}10` : colors.bgSecondary, border: `1px solid ${active ? `${colors.accent}40` : colors.border}` }}
                  >
                    <span className="text-[12px] font-medium truncate block" style={{ color: active ? colors.accent : colors.text }}>{title(session.messages)}</span>
                    <span className="text-[10px] mt-1 block" style={{ color: colors.textDim }}>{time(session.messages.at(-1)?.timestamp)} · {session.messages.length} messages</span>
                  </button>
                )
              })}
          </>
        )}
        {viewMode === 'projects' && filteredProjects.map(project => {
            const expanded = expandedProjects.has(project.id)
            const activeProject = project.id === activeConversationProjectId
            const linkedProject = primaryLocalProject(project)
            const branch = linkedProject?.branch
            const list = sessionsByProject.get(project.id) || []

            return (
              <div key={project.id} className="mb-1.5">
                <div
                  className="group flex items-center gap-1.5 px-3 py-2 cursor-pointer transition-colors"
                  onClick={() => openProject(project)}
                  style={{ backgroundColor: activeProject ? `${colors.accent}08` : 'transparent' }}
                >
                  <svg className={`w-3 h-3 shrink-0 transition-transform ${expanded ? 'rotate-90' : ''}`} viewBox="0 0 24 24" fill="none" stroke={colors.textDim} strokeWidth="2.5"><polyline points="9 6 15 12 9 18" /></svg>
                  {project.pinnedAt && <span className="text-[10px] shrink-0">📌</span>}
                  <svg className="w-3.5 h-3.5 shrink-0" style={{ color: colors.accent }} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2"><path d="M3 7a2 2 0 0 1 2-2h4l2 2h8a2 2 0 0 1 2 2v9a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V7z" /></svg>
                  <div className="min-w-0 flex-1">
                    <div className="flex items-center gap-1.5">
                      <span className="text-[12px] font-medium truncate">{project.name}</span>
                      {linkedProject && (
                        <span className="rounded-full px-1.5 py-0.5 text-[9px] leading-none" style={{ backgroundColor: `${colors.accent}14`, color: colors.accent }}>
                          Project
                        </span>
                      )}
                    </div>
                    <div className="text-[10px] truncate" style={{ color: colors.textDim }}>
                      {linkedProject
                        ? `${branch || 'No branch'} · ${linkedProject.name}`
                        : 'No linked project'}
                    </div>
                  </div>
                  <button onClick={event => { event.stopPropagation(); if (currentAgentId) newConversation(currentAgentId, project.id) }} className="w-5 h-5 rounded flex items-center justify-center hover:bg-black/10 shrink-0" style={{ color: colors.textDim }} title="New chat in this project">
                    <svg className="w-3 h-3" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5"><path d="M12 5v14M5 12h14" /></svg>
                  </button>
                  <button onClick={event => openMenu(event, 'project', project.id)} className="w-5 h-5 rounded flex items-center justify-center hover:bg-black/10 shrink-0" style={{ color: colors.textDim }} title="Project actions">
                    <svg className="w-3.5 h-3.5" viewBox="0 0 24 24" fill="currentColor"><circle cx="5" cy="12" r="1.6" /><circle cx="12" cy="12" r="1.6" /><circle cx="19" cy="12" r="1.6" /></svg>
                  </button>
                </div>

                {expanded && (
                  <div className="pl-4 pr-2">
                    {list.length === 0 ? (
                      <div className="px-2 py-2 text-[10px]" style={{ color: colors.textDim }}>No chats</div>
                    ) : list.map(session => {
                      const active = session.id === currentSessionId
                      return (
                        <div
                          key={session.id}
                          onClick={() => { setCurrentSession(session.id); setActiveConversationProject(project.id) }}
                          className="mx-1 my-1 px-2.5 py-2 rounded-lg cursor-pointer transition-all group"
                          style={{ backgroundColor: active ? `${colors.accent}10` : colors.bgSecondary, border: `1px solid ${active ? `${colors.accent}36` : colors.border}` }}
                        >
                          <div className="flex items-center gap-1.5">
                            {session.pinnedAt && <span className="text-[10px]">📌</span>}
                            <span className="text-[11px] font-medium truncate flex-1" style={{ color: active ? colors.accent : colors.text }}>{title(session.messages)}</span>
                            {loadingSessionIds.includes(session.id) && (
                              <span className="w-1.5 h-1.5 rounded-full animate-pulse shrink-0" style={{ backgroundColor: colors.accent }} />
                            )}
                            <button onClick={event => openMenu(event, 'session', session.id)} className="w-4 h-4 rounded opacity-0 group-hover:opacity-100 flex items-center justify-center hover:bg-black/10" style={{ color: colors.textDim }}>
                              <svg className="w-3 h-3" viewBox="0 0 24 24" fill="currentColor"><circle cx="5" cy="12" r="1.5" /><circle cx="12" cy="12" r="1.5" /><circle cx="19" cy="12" r="1.5" /></svg>
                            </button>
                          </div>
                          <div className="text-[9px] mt-1" style={{ color: colors.textDim }}>{time(session.messages.at(-1)?.timestamp)} · {session.messages.length} msgs</div>
                        </div>
                      )
                    })}
                  </div>
                )}
              </div>
            )
          })}
      </div>

      {dialog && (
        <div className="fixed inset-0 z-[90] flex items-center justify-center bg-black/30" onPointerDown={() => setDialog(null)}>
          <div className="w-[320px] rounded-xl border p-4 shadow-2xl" style={{ backgroundColor: colors.bgPrimary, borderColor: colors.border }} onPointerDown={event => event.stopPropagation()}>
            <div className="text-[13px] font-semibold mb-3" style={{ color: colors.text }}>
              {dialog.mode === 'create' ? 'New project' : dialog.mode === 'add-projects' ? 'Add project' : 'Edit project'}
            </div>
            {dialog.mode !== 'add-projects' && (
              <>
                <label className="block text-[11px] mb-1" style={{ color: colors.textSecondary }}>Project name</label>
                <input
                  value={dialog.name}
                  onChange={event => setDialog({ ...dialog, name: event.target.value })}
                  className="w-full h-8 px-2.5 rounded-md border text-[12px] mb-3 outline-none"
                  style={{ backgroundColor: colors.bgInput, borderColor: colors.border, color: colors.text }}
                />
              </>
            )}
            <div className="flex items-center justify-between mb-1">
              <label className="text-[11px]" style={{ color: colors.textSecondary }}>Linked project</label>
              <button
                onClick={() => void openLocalProjectDialog()}
                disabled={openingFolder}
                className="h-6 px-2 rounded-md text-[10px] font-medium transition-colors disabled:opacity-60"
                style={{ backgroundColor: `${colors.accent}12`, color: colors.accent }}
              >
                {openingFolder ? 'Selecting...' : 'Open local project'}
              </button>
            </div>
            <div className="max-h-[220px] overflow-y-auto rounded-lg border p-1 space-y-1" style={{ borderColor: colors.border }}>
              {localProjects.length === 0 ? (
                <div className="px-2 py-3 text-[11px] text-center" style={{ color: colors.textDim }}>No local project. Open a folder first.</div>
              ) : localProjects.map(project => {
                const checked = dialog.localProjectId === project.id
                return (
                  <button
                    key={project.id}
                    onClick={() => selectLinkedProject(project.id)}
                    className="w-full flex items-center gap-2 px-2 py-1.5 rounded-md text-left transition-colors hover:bg-black/5"
                    style={{ backgroundColor: checked ? `${colors.accent}10` : 'transparent' }}
                  >
                    <span
                      className="w-3.5 h-3.5 rounded-full border flex items-center justify-center shrink-0"
                      style={{ borderColor: checked ? colors.accent : colors.border, backgroundColor: checked ? colors.accent : 'transparent' }}
                    >
                      {checked && <span className="w-1.5 h-1.5 rounded-full bg-white" />}
                    </span>
                    <span className="text-[11px] font-medium truncate flex-1" style={{ color: colors.text }}>{project.name}</span>
                    <span className="text-[10px] truncate max-w-[90px]" style={{ color: colors.textDim }}>{project.branch || 'No branch'}</span>
                  </button>
                )
              })}
            </div>
            <div className="flex justify-end gap-2 mt-4">
              <button onClick={() => setDialog(null)} className="h-7 px-3 rounded-md text-[11px]" style={{ backgroundColor: colors.bgTertiary, color: colors.textSecondary }}>Cancel</button>
              <button onClick={submitDialog} className="h-7 px-3 rounded-md text-[11px] font-medium" style={{ backgroundColor: colors.accent, color: '#fff' }}>Save</button>
            </div>
          </div>
        </div>
      )}
    </div>
  )
}
