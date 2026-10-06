import { create } from 'zustand'
import type { AgentMessage, ConversationProject } from '../types'
import * as agentApi from '../api/agent'
import type { AiAgentConfigDTO, ReActStep, ChangeSummary } from '../api/agent'
import { toolProgressStore } from '../components/ToolProgressBar'

interface AgentStore {
  // current session ID(value = server return sessionId)
  currentSessionId: string | null
  // session history
  sessions: Map<string, { id: string; name: string; agentId: string; projectId?: string; pinnedAt?: number; messages: AgentMessage[]; createdAt: number }>
  loadingSessionIds: string[]
  // input box content
  inputText: string
  // whether wait response
  isLoading: boolean
  // history panel whether expand
  showHistoryPanel: boolean
  toggleHistoryPanel: () => void

  // ===== chat project =====
  conversationProjects: ConversationProject[]
  activeConversationProjectId: string
  setActiveConversationProject: (id: string) => void
  createConversationProject: (name: string, path?: string | null, localProjectId?: string | null) => string
  updateConversationProject: (id: string, patch: Partial<Pick<ConversationProject, 'name' | 'path' | 'pinnedAt' | 'localProjectId'>>) => void
  deleteConversationProject: (id: string) => void
  toggleConversationProjectPinned: (id: string) => void
  setSessionProject: (sessionId: string, projectId: string) => void
  toggleSessionPinned: (sessionId: string) => void

  // ===== agent list =====
  agents: AiAgentConfigDTO[]
  currentAgentId: string | null
  fetchAgents: () => Promise<void>
  setCurrentAgentId: (id: string) => void

  // ===== session manage =====
  // create a server session and bind it to the current chat
  createServerSession: (agentId: string) => Promise<string>
  // settings current session
  setCurrentSession: (id: string | null) => void
  // add message
  addMessage: (sessionId: string, message: AgentMessage) => void
  // update message(for streaming follow add)
  updateMessage: (sessionId: string, messageId: string, content: string) => void
  // update message ReAct step
  updateMessageSteps: (sessionId: string, messageId: string, steps: ReActStep[]) => void
  // update message task split unbind
  updateMessageTaskBreakdown: (sessionId: string, messageId: string, breakdown: import('../api/agent').TaskBreakdownDTO) => void
  // update subtask status
  updateSubTaskStatus: (sessionId: string, messageId: string, subTaskIndex: number, status: string, result?: string) => void
  // update file change summary
  updateMessageChangeSummary: (sessionId: string, messageId: string, summary: import('../api/agent').ChangeSummary) => void
  // ── extra message stream manage ──
  // add tool call message
  addToolCallMessage: (sessionId: string, groupId: string, toolCallId: string, toolName: string, toolParams: string) => string
  // update tool message status(tool_result on return)
  updateToolMessageStatus: (sessionId: string, messageId: string, status: 'in_progress' | 'success' | 'failure', toolResult?: string) => void
  // add/ update AI text message(same groupId only one bar messageType=text message,onText update when)
  upsertTextMessage: (sessionId: string, groupId: string, content: string) => string
  // add collect total message
  addSummaryMessage: (sessionId: string, groupId: string, changeSummary?: ChangeSummary) => void
  // add thinking message
  addThinkingMessage: (sessionId: string, groupId: string, content: string) => string
  // replace same group most back one bar thinking message content(for update placeholder message)
  replaceLastThinkingMessage: (sessionId: string, groupId: string, content: string) => void
  // by ID update some bar process notes(reuse the same process segment while streaming)
  replaceThinkingMessageById: (sessionId: string, messageId: string, content: string) => void
  // pending when final answer(text) demote as process notes(thinking)-- on the next tool call, confirm the previous text was process notes
  demoteTextMessageToThinking: (sessionId: string, messageId: string) => void
  // remove same group all thinking message(receive text when clean placeholder)
  removeThinkingMessages: (sessionId: string, groupId: string) => void
  // only remove placeholder thinking message, keep real process segment
  removePlaceholderThinkingMessages: (sessionId: string, groupId: string) => void
  // add error message
  addErrorMessage: (sessionId: string, groupId: string, content: string) => void
  // stop when same group all in_progress tool message mark as failure
  markGroupInProgressAsFailure: (sessionId: string, groupId: string) => void

  // edit re- send: delete from messageId start all message, content fill in input box
  editAndRetry: (sessionId: string, messageId: string) => void
  // settings input box content
  setInputText: (text: string) => void
  // settings load status
  setLoading: (loading: boolean) => void
  setSessionLoading: (sessionId: string, loading: boolean) => void
  clearMessages: (sessionId: string) => void
  // new chat(click new when call this method)
  newConversation: (agentId: string, projectId?: string) => Promise<void>
}

const CONVERSATION_PROJECTS_KEY = 'shellmind-conversation-projects'

function loadConversationProjects(): ConversationProject[] {
  try {
    const raw = localStorage.getItem(CONVERSATION_PROJECTS_KEY)
    if (raw) {
      const parsed = JSON.parse(raw) as Array<ConversationProject & { localProjectIds?: string[] }>
      if (Array.isArray(parsed) && parsed.length > 0) {
        return parsed.map(({ localProjectIds, ...project }) => ({
          ...project,
          localProjectId: project.localProjectId ?? localProjectIds?.[0] ?? null,
        }))
      }
    }
  } catch { /* ignore */ }
  return [{
    id: 'default',
    name: 'Default project',
    path: null,
    createdAt: Date.now(),
  }]
}

function saveConversationProjects(projects: ConversationProject[]) {
  try {
    localStorage.setItem(CONVERSATION_PROJECTS_KEY, JSON.stringify(projects))
  } catch { /* ignore */ }
}

export const useAgentStore = create<AgentStore>((set, get) => ({
  currentSessionId: null,
  sessions: new Map(),
  loadingSessionIds: [],
  inputText: '',
  isLoading: false,
  showHistoryPanel: true,
  toggleHistoryPanel: () => set((s) => ({ showHistoryPanel: !s.showHistoryPanel })),

  conversationProjects: loadConversationProjects(),
  activeConversationProjectId: 'default',
  setActiveConversationProject: (id) =>
    set((state) => (
      state.conversationProjects.some(project => project.id === id)
        ? { activeConversationProjectId: id }
        : state
    )),
  createConversationProject: (name, path = null, localProjectId = null) => {
    const id = `project_${Date.now()}_${Math.random().toString(36).slice(2, 7)}`
    const project: ConversationProject = {
      id,
      name: name.trim() || 'Untitled project',
      path: path || null,
      localProjectId,
      createdAt: Date.now(),
    }
    set((state) => {
      const projects = [...state.conversationProjects, project]
      saveConversationProjects(projects)
      return { conversationProjects: projects, activeConversationProjectId: id }
    })
    return id
  },
  updateConversationProject: (id, patch) =>
    set((state) => {
      const projects = state.conversationProjects.map(project =>
        project.id === id ? { ...project, ...patch } : project
      )
      saveConversationProjects(projects)
      return { conversationProjects: projects }
    }),
  deleteConversationProject: (id) => {
    set((state) => {
      const projects = state.conversationProjects.filter(project => project.id !== id)
      const nextProjects = projects.length > 0 ? projects : [{
        id: 'default',
        name: 'Default project',
        path: null,
        localProjectId: null,
        createdAt: Date.now(),
      }]
      const sessions = new Map(state.sessions)
      for (const [sessionId, session] of sessions.entries()) {
        if (session.projectId === id) sessions.delete(sessionId)
      }
      const remainingSessions = Array.from(sessions.values())
        .sort((a, b) => (b.messages.at(-1)?.timestamp || b.createdAt) - (a.messages.at(-1)?.timestamp || a.createdAt))
      saveConversationProjects(nextProjects)
      return {
        conversationProjects: nextProjects,
        sessions,
        currentSessionId: remainingSessions[0]?.id || null,
        activeConversationProjectId: nextProjects[0].id,
      }
    })
  },
  toggleConversationProjectPinned: (id) =>
    set((state) => {
      const projects = state.conversationProjects.map(project =>
        project.id === id
          ? { ...project, pinnedAt: project.pinnedAt ? undefined : Date.now() }
          : project
      )
      saveConversationProjects(projects)
      return { conversationProjects: projects }
    }),
  setSessionProject: (sessionId, projectId) =>
    set((state) => {
      const sessions = new Map(state.sessions)
      const session = sessions.get(sessionId)
      if (!session) return {}
      sessions.set(sessionId, { ...session, projectId })
      return { sessions }
    }),
  toggleSessionPinned: (sessionId) =>
    set((state) => {
      const sessions = new Map(state.sessions)
      const session = sessions.get(sessionId)
      if (!session) return {}
      sessions.set(sessionId, {
        ...session,
        pinnedAt: session.pinnedAt ? undefined : Date.now(),
      })
      return { sessions }
    }),

  agents: [],
  currentAgentId: null,

  fetchAgents: async () => {
    const list = await agentApi.queryAgentList()
    set({ agents: list })
    // auto selected a
    if (list.length > 0 && !get().currentAgentId) {
      set({ currentAgentId: list[0].agentId })
    }
  },

  setCurrentAgentId: (id) => set({ currentAgentId: id }),

  createServerSession: async (agentId) => {
    const serverSessionId = await agentApi.createSession(agentId)
    if (!serverSessionId) throw new Error('Failed to create session')
    // clear leftover tool-progress state when starting a new session
    toolProgressStore.clear()
    const state = get()
    const newSession = {
      id: serverSessionId,
      agentId,
      name: `Session ${state.sessions.size + 1}`,
      projectId: state.activeConversationProjectId,
      messages: [] as AgentMessage[],
      createdAt: Date.now(),
    }
    set((s) => {
      const sessions = new Map(s.sessions)
      sessions.set(serverSessionId, newSession)
      return { sessions, currentSessionId: serverSessionId }
    })
    return serverSessionId
  },

  setCurrentSession: (id) => set({ currentSessionId: id }),

  addMessage: (sessionId, message) =>
    set((state) => {
      const sessions = new Map(state.sessions)
      const session = sessions.get(sessionId)
      if (session) {
        sessions.set(sessionId, {
          ...session,
          messages: [...session.messages, message],
        })
      }
      return { sessions }
    }),

  updateMessage: (sessionId, messageId, content) =>
    set((state) => {
      const sessions = new Map(state.sessions)
      const session = sessions.get(sessionId)
      if (session) {
        const messages = session.messages.map((m) =>
          m.id === messageId ? { ...m, content } : m
        )
        sessions.set(sessionId, { ...session, messages })
      }
      return { sessions }
    }),

  updateMessageSteps: (sessionId, messageId, steps) =>
    set((state) => {
      const sessions = new Map(state.sessions)
      const session = sessions.get(sessionId)
      if (session) {
        const messages = session.messages.map((m) =>
          m.id === messageId ? { ...m, steps: [...steps] } : m
        )
        sessions.set(sessionId, { ...session, messages })
      } else {
        console.warn('[updateMessageSteps] session not found: sessionId=', sessionId)
      }
      return { sessions }
    }),

  updateMessageTaskBreakdown: (sessionId, messageId, breakdown) =>
    set((state) => {
      const sessions = new Map(state.sessions)
      const session = sessions.get(sessionId)
      if (session) {
        const messages = session.messages.map((m) =>
          m.id === messageId ? { ...m, taskBreakdown: breakdown } : m
        )
        sessions.set(sessionId, { ...session, messages })
      }
      return { sessions }
    }),

  updateSubTaskStatus: (sessionId, messageId, subTaskIndex, status, result) =>
    set((state) => {
      const sessions = new Map(state.sessions)
      const session = sessions.get(sessionId)
      if (session) {
        const messages = session.messages.map((m) => {
          if (m.id !== messageId || !m.taskBreakdown) return m
          const updatedBreakdown = {
            ...m.taskBreakdown,
            subTasks: m.taskBreakdown.subTasks.map((st) => {
              if (st.index !== subTaskIndex) return st
              return { ...st, status: status as any, result: result ?? st.result }
            }),
          }
          return { ...m, taskBreakdown: updatedBreakdown }
        })
        sessions.set(sessionId, { ...session, messages })
      }
      return { sessions }
    }),

  updateMessageChangeSummary: (sessionId, messageId, summary) =>
    set((state) => {
      const sessions = new Map(state.sessions)
      const session = sessions.get(sessionId)
      if (session) {
        const messages = session.messages.map((m) =>
          m.id === messageId ? { ...m, changeSummary: summary } : m
        )
        sessions.set(sessionId, { ...session, messages })
      }
      return { sessions }
    }),

  setInputText: (text) => set({ inputText: text }),

  // ══════════════════════════════════════════════════════════
  // extra message stream real current
  // ══════════════════════════════════════════════════════════

  addToolCallMessage: (sessionId, groupId, toolCallId, toolName, toolParams) => {
    // use from add count widget avoid key duplicate(Date.now() at same ms inside ok can duplicate)
    const _tcSeq = ((globalThis as any).__toolCallSeq = ((globalThis as any).__toolCallSeq || 0) + 1)
    const msgId = `tool_${toolCallId}_${Date.now()}_${_tcSeq}`
    const msg: AgentMessage = {
      id: msgId,
      role: 'assistant',
      content: toolParams ? `Call ${toolName}: ${toolParams}` : `Call ${toolName}`,
      timestamp: Date.now(),
      messageType: 'tool_call',
      groupId,
      toolName,
      toolCallId,
      toolParams,
      status: 'in_progress',
    }
    set((state) => {
      const sessions = new Map(state.sessions)
      const session = sessions.get(sessionId)
      if (session) {
        sessions.set(sessionId, { ...session, messages: [...session.messages, msg] })
      }
      return { sessions }
    })
    return msgId
  },

  updateToolMessageStatus: (sessionId, messageId, status, toolResult) =>
    set((state) => {
      const sessions = new Map(state.sessions)
      const session = sessions.get(sessionId)
      if (session) {
        const messages = session.messages.map((m) =>
          m.id === messageId
            ? { ...m, status, toolResult: toolResult ?? m.toolResult, content: toolResult ?? m.content }
            : m
        )
        sessions.set(sessionId, { ...session, messages })
      }
      return { sessions }
    }),

  upsertTextMessage: (sessionId, groupId, content) => {
    const state = get()
    const session = state.sessions.get(sessionId)
    if (!session) return ''
    // find same groupId down already has assistant text message(row remove user message)
    const existing = session.messages.find(m => m.groupId === groupId && m.messageType === 'text' && m.role === 'assistant')
    if (existing) {
      // update
      set((s) => {
        const sessions = new Map(s.sessions)
        const sess = sessions.get(sessionId)
        if (sess) {
          const messages = sess.messages.map(m =>
            m.id === existing.id ? { ...m, content } : m
          )
          sessions.set(sessionId, { ...sess, messages })
        }
        return { sessions }
      })
      return existing.id
    } else {
      // add
      const msgId = `text_${Date.now()}`
      const msg: AgentMessage = {
        id: msgId,
        role: 'assistant',
        content,
        timestamp: Date.now(),
        messageType: 'text',
        groupId,
      }
      set((s) => {
        const sessions = new Map(s.sessions)
        const sess = sessions.get(sessionId)
        if (sess) {
          sessions.set(sessionId, { ...sess, messages: [...sess.messages, msg] })
        }
        return { sessions }
      })
      return msgId
    }
  },

  addSummaryMessage: (sessionId, groupId, changeSummary) => {
    const msgId = `summary_${Date.now()}`
    const msg: AgentMessage = {
      id: msgId,
      role: 'assistant',
      content: '',
      timestamp: Date.now(),
      messageType: 'summary',
      groupId,
      changeSummary,
    }
    set((state) => {
      const sessions = new Map(state.sessions)
      const session = sessions.get(sessionId)
      if (session) {
        sessions.set(sessionId, { ...session, messages: [...session.messages, msg] })
      }
      return { sessions }
    })
  },

  addThinkingMessage: (sessionId, groupId, content) => {
    const msgId = `think_${Date.now()}`
    const msg: AgentMessage = {
      id: msgId,
      role: 'assistant',
      content,
      timestamp: Date.now(),
      messageType: 'thinking',
      groupId,
    }
    set((state) => {
      const sessions = new Map(state.sessions)
      const session = sessions.get(sessionId)
      if (session) {
        sessions.set(sessionId, { ...session, messages: [...session.messages, msg] })
      }
      return { sessions }
    })
    return msgId
  },

  replaceLastThinkingMessage: (sessionId, groupId, content) =>
    set((state) => {
      const sessions = new Map(state.sessions)
      const session = sessions.get(sessionId)
      if (!session) return {}
      // find to same group most back one bar thinking message merge replace content
      const messages = [...session.messages]
      for (let i = messages.length - 1; i >= 0; i--) {
        if (messages[i].groupId === groupId && messages[i].messageType === 'thinking') {
          messages[i] = { ...messages[i], content }
          break
        }
      }
      sessions.set(sessionId, { ...session, messages })
      return { sessions }
    }),

  replaceThinkingMessageById: (sessionId, messageId, content) =>
    set((state) => {
      const sessions = new Map(state.sessions)
      const session = sessions.get(sessionId)
      if (!session) return {}
      const messages = session.messages.map(message =>
        message.id === messageId && message.messageType === 'thinking'
          ? { ...message, content }
          : message
      )
      sessions.set(sessionId, { ...session, messages })
      return { sessions }
    }),

  /**
   * pending when final answer(text) demote as process notes(thinking).
   * streaming text at down one round tool call start when confirm is" process notes" and non final answer.
   */
  demoteTextMessageToThinking: (sessionId, messageId) =>
    set((state) => {
      const sessions = new Map(state.sessions)
      const session = sessions.get(sessionId)
      if (!session) return {}
      const messages = session.messages.map(message =>
        message.id === messageId && message.messageType === 'text'
          ? { ...message, messageType: 'thinking' as const }
          : message
      )
      sessions.set(sessionId, { ...session, messages })
      return { sessions }
    }),

  removeThinkingMessages: (sessionId, groupId) =>
    set((state) => {
      const sessions = new Map(state.sessions)
      const session = sessions.get(sessionId)
      if (!session) return {}
      const messages = session.messages.filter(
        m => !(m.groupId === groupId && m.messageType === 'thinking')
      )
      sessions.set(sessionId, { ...session, messages })
      return { sessions }
    }),

  removePlaceholderThinkingMessages: (sessionId, groupId) =>
    set((state) => {
      const sessions = new Map(state.sessions)
      const session = sessions.get(sessionId)
      if (!session) return {}
      const messages = session.messages.filter(message => !(
        message.groupId === groupId
        && message.messageType === 'thinking'
        && (!message.content.trim() || message.content.trim() === 'Thinking...')
      ))
      sessions.set(sessionId, { ...session, messages })
      return { sessions }
    }),

  addErrorMessage: (sessionId, groupId, content) => {
    const msgId = `error_${Date.now()}`
    const msg: AgentMessage = {
      id: msgId,
      role: 'assistant',
      content,
      timestamp: Date.now(),
      messageType: 'error',
      groupId,
    }
    set((state) => {
      const sessions = new Map(state.sessions)
      const session = sessions.get(sessionId)
      if (session) {
        sessions.set(sessionId, { ...session, messages: [...session.messages, msg] })
      }
      return { sessions }
    })
  },

  markGroupInProgressAsFailure: (sessionId, groupId) =>
    set((state) => {
      const sessions = new Map(state.sessions)
      const session = sessions.get(sessionId)
      if (session) {
        const messages = session.messages.map((m) =>
          m.groupId === groupId && m.messageType === 'tool_call' && m.status === 'in_progress'
            ? { ...m, status: 'failure' as const, content: 'Cancelled by user' }
            : m
        )
        sessions.set(sessionId, { ...session, messages })
      }
      return { sessions }
    }),

  editAndRetry: (sessionId, messageId) =>
    set((state) => {
      const sessions = new Map(state.sessions)
      const session = sessions.get(sessionId)
      if (!session) return {}
      const msgIndex = session.messages.findIndex(m => m.id === messageId)
      if (msgIndex < 0) return {}
      const targetMsg = session.messages[msgIndex]
      // truncate break message list(keep msgIndex front message)
      const truncatedMessages = session.messages.slice(0, msgIndex)
      sessions.set(sessionId, { ...session, messages: truncatedMessages })
      return { sessions, inputText: targetMsg.content }
    }),

  setLoading: (loading) => set({ isLoading: loading }),

  setSessionLoading: (sessionId, loading) =>
    set((state) => {
      const loadingSessionIds = loading
        ? Array.from(new Set([...state.loadingSessionIds, sessionId]))
        : state.loadingSessionIds.filter(id => id !== sessionId)
      return { loadingSessionIds, isLoading: loadingSessionIds.length > 0 }
    }),

  clearMessages: (sessionId: string) => {
    // also clear leftover tool-progress when clearing messages
    toolProgressStore.clear()
    set((state) => {
      const sessions = new Map(state.sessions)
      const session = sessions.get(sessionId)
      if (!session) return {}
      sessions.set(sessionId, { ...session, messages: [] })
      return { sessions }
    })
  },

  newConversation: async (agentId, projectId) => {
    if (projectId) {
      get().setActiveConversationProject(projectId)
    }
    await get().createServerSession(agentId)
  },
}))
