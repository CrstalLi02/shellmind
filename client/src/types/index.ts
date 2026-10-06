// SSH connection info(frontend local template type)
export interface SSHConnection {
  id: string
  name: string
  host: string
  port: number
  username: string
  password?: string
  privateKey?: string
  authType: number       // 1- password, 2- private key
  status: number         // 0- disconnected, 1- connected, 2- connecting, 3- connection fail
  createdAt: number
  updatedAt: number
}

import type { ReActStep, ChangeSummary, TaskBreakdownDTO } from '../api/agent'

/** message type(extra message stream architecture) */
export type AgentMessageType =
  | 'text'         // AI text reply(Markdown)
  | 'tool_call'    // tool call(ok collapse card)
  | 'tool_result'  // tool execute result
  | 'thinking'     // thinking/ progress hint
  | 'summary'      // most end collect total(contains changeSummary)
  | 'error'        // error message

export type MessageContextTagKind =
  | 'file'
  | 'directory'
  | 'project'
  | 'image'
  | 'terminal'
  | 'connection'
  | 'custom'

export interface MessageContextTag {
  label: string
  kind: MessageContextTagKind
  imageDataUrl?: string
  fullContent?: string
}

// Agent session message
export interface AgentMessage {
  id: string
  role: 'user' | 'assistant' | 'system'
  content: string
  timestamp: number

  // ── extra message stream char segment ──
  /** message type */
  messageType: AgentMessageType
  /** same nth chat round group ID(user message + AI extra bar reply share same groupId) */
  groupId: string

  // ── tool relative off(messageType=tool_call/tool_result has value when) ──
  /** tool name name */
  toolName?: string
  /** tool call ID(off link tool_call and tool_result) */
  toolCallId?: string
  /** tool params */
  toolParams?: string
  /** tool execute result */
  toolResult?: string
  /** tool execute status */
  status?: 'in_progress' | 'success' | 'failure'

  // ── compatible old char segment(each step deprecated) ──
  /** @deprecated extra message stream mode down not again use */
  steps?: ReActStep[]
  /** task split unbind square case */
  taskBreakdown?: TaskBreakdownDTO
  /** file change summary(done event center carry bar) */
  changeSummary?: ChangeSummary
  /** context chips sent with the user message(for tight assemble display) */
  contextTags?: MessageContextTag[]
}

// Agent session
export interface AgentSession {
  id: string
  name: string
  connectionId?: string
  projectId?: string
  pinnedAt?: number
  messages: AgentMessage[]
  createdAt: number
}

export interface ConversationProject {
  id: string
  name: string
  path?: string | null
  localProjectId?: string | null
  createdAt: number
  pinnedAt?: number
}

// server status
export interface ServerStatus {
  connected: boolean
  url: string
  version?: string
}

// ===== SSH connection status item raise =====
export const ConnectionStatus = {
  DISCONNECTED: 0,
  CONNECTED: 1,
  CONNECTING: 2,
  FAILED: 3,
} as const

export const AuthType = {
  PASSWORD: 1,
  PRIVATE_KEY: 2,
} as const
