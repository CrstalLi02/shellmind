import React, { useRef, useEffect, useState, useMemo } from 'react'
import { getCurrentWindow } from '@tauri-apps/api/window'
import { stat } from '@tauri-apps/plugin-fs'
import { useThemeStore } from '../stores/themeStore'
import { useAgentStore } from '../stores/agentStore'
import { useConnectionStore } from '../stores/connectionStore'
import { useSshAgentStore, type InputTag } from '../stores/sshAgentStore'
import { useFileExplorerStore, isDiffTab } from '../stores/fileExplorerStore'
import { useLocalFileStore, isLocalDiffTab, type LocalOpenTab } from '../stores/localFileStore'
import { useAiPatchStore } from '../stores/aiPatchStore'
import { useOutputStore } from '../stores/outputStore'
import { usePermissionStore } from '../stores/permissionStore'
import { useStreamStore, type StreamStatus } from '../stores/streamStore'
import { useModelStore } from '../stores/modelStore'
import { useRuntimeStore } from '../stores/runtimeStore'
import { playCompleteSound, playErrorSound } from '../utils/sound'
import * as agentApi from '../api/agent'
import type { ReActStep, TaskBreakdownDTO } from '../api/agent'
import { ConnectionStatus } from '../types'
import type { AgentMessage } from '../types'
import { MessageBubble } from './MessageBubble'
import { PermissionConfirmModal } from './PermissionConfirmModal'
import { StreamStatusBar } from './StreamStatusBar'
import { ErrorRecoveryCard, type ErrorRecovery } from './ErrorRecoveryCard'
import { TopicDivider, shouldInsertTopicDivider } from './TopicDivider'
import { SessionSummaryCard } from './SessionSummaryCard'
import { ArtifactSummaryPanel } from './ArtifactSummaryPanel'
import { CommandMenu, useCommandMenu, type MenuItem } from './CommandMenu'
import { toolProgressStore } from './ToolProgressBar'
import { ShortcutHelp } from './ShortcutHelp'
import { MarkdownContent, splitThinkTags, getToolIconInfo, ContextTagChip } from './MessageBubbleShared'
import { ChatExport } from './ChatExport'
import { EmptyState } from './EmptyState'

// tool time axis ok summary
function extractToolSummaryGlobal(toolName: string, toolParams?: string): string {
  if (!toolParams) return ''
  const lower = toolName.toLowerCase()
  if (lower.includes('exec') || lower.includes('command') || lower.includes('ssh')) {
    const cmd = toolParams.trim().split('\n')[0]
    return cmd.length > 50 ? cmd.substring(0, 50) + '...' : cmd
  }
  const pathMatch = toolParams.match(/(\/?[\w./-]+\.[\w]+)/)
  if (pathMatch) return pathMatch[1]
  return toolParams.length > 40 ? toolParams.substring(0, 40) + '...' : toolParams
}

function ProcessRow({ content, active }: {
  content: string
  active: boolean
}) {
  const { colors } = useThemeStore()
  const display = content || (active ? 'Analyzing the request, project context, and available tools...' : 'Done')
  return (
    <div className="py-0.5 text-[12px] leading-relaxed" style={{ color: colors.textSecondary }}>
      <MarkdownContent content={display} colors={colors} />
    </div>
  )
}

function ToolActivityRow({ message }: { message: AgentMessage }) {
  const { colors } = useThemeStore()
  const active = message.status === 'in_progress'
  const failed = message.status === 'failure'
  const [open, setOpen] = useState(active)
  const info = getToolIconInfo(message.toolName || '')
  const summary = extractToolSummaryGlobal(message.toolName || '', message.toolParams)

  useEffect(() => {
    setOpen(message.status === 'in_progress')
  }, [message.status])

  const formatDetail = (value?: string) => {
    if (!value?.trim()) return ''
    try {
      return JSON.stringify(JSON.parse(value), null, 2)
    } catch {
      return value
    }
  }
  const params = formatDetail(message.toolParams)
  const result = formatDetail(message.toolResult || message.content)

  return (
    <div className="overflow-hidden">
      <button type="button" onClick={() => setOpen(!open)}
              className="flex w-full items-center gap-2 px-2 py-1.5 text-left transition-colors hover:bg-black/[0.03]">
        <span className="shrink-0" style={{ color: colors.textDim }}>
          <svg className="h-3 w-3" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">
            <rect x="4" y="4" width="16" height="16" rx="4" />
            <path d="M8.5 9.5 11 12l-2.5 2.5" />
            <path d="M13 15h3" />
          </svg>
        </span>
        <span className="shrink-0 text-[12px] font-medium" style={{ color: colors.textDim }}>{info.label}</span>
        {summary && <span className="min-w-0 flex-1 truncate text-[11px]" style={{ color: colors.textSecondary }}>{summary}</span>}
        {failed && <span className="ml-auto shrink-0 text-[10px]" style={{ color: colors.red }}>Failed</span>}
        <svg className={`h-3 w-3 shrink-0 transition-transform ${open ? 'rotate-90' : ''}`} style={{ color: colors.textDim }}
             viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5">
          <polyline points="6 9 12 15 18 9" />
        </svg>
      </button>
      {open && (params || result) && (
        <div className="space-y-2 px-2 pb-2">
          {params && (
            <div>
              <div className="mb-1 text-[10px] font-medium" style={{ color: colors.textDim }}>Input</div>
              <pre className="max-h-36 overflow-auto rounded-lg px-2 py-1.5 text-[10px] leading-relaxed"
                   style={{ backgroundColor: 'transparent', color: colors.textSecondary }}>{params}</pre>
            </div>
          )}
          {result && (
            <div>
              <div className="mb-1 text-[10px] font-medium" style={{ color: colors.textDim }}>Output</div>
              <pre className="max-h-44 overflow-auto rounded-lg px-2 py-1.5 text-[10px] leading-relaxed"
                   style={{ backgroundColor: 'transparent', color: colors.textSecondary }}>{result}</pre>
            </div>
          )}
        </div>
      )}
    </div>
  )
}

type TurnTimelineItem =
  | { kind: 'message'; message: AgentMessage }
  | { kind: 'activity'; key: string; messages: AgentMessage[] }

function normalizeProcessContent(content: string) {
  return content
    .replace(/([\s\S]*?<\/think>|<think[^>]*>[\s\S]*?)(<\/think>|$)/g, '')
    .replace(/\s+/g, ' ')
    .trim()
}

function ToolActivityGroup({ messages }: { messages: AgentMessage[] }) {
  const { colors } = useThemeStore()
  const running = messages.some(message => message.status === 'in_progress')
  const failedCount = messages.filter(message => message.status === 'failure').length
  const [open, setOpen] = useState(false)

  const counts = messages.reduce((map, message) => {
    const key = message.toolName || 'tool'
    map.set(key, (map.get(key) || 0) + 1)
    return map
  }, new Map<string, number>())
  const primaryInfo = getToolIconInfo(messages[0]?.toolName || '')
  const summary = [...counts.entries()]
    .map(([name, count]) => `${getToolIconInfo(name).label}${count > 1 ? ` ×${count}` : ''}`)
    .join(' · ')
  const title = running
    ? `Running ${messages.length} steps`
    : messages.length > 1 ? `Ran ${messages.length} steps` : primaryInfo.label

  return (
    <div className="overflow-hidden">
      <button type="button" onClick={() => setOpen(!open)}
              className="flex w-full min-w-0 items-center gap-2 px-0 py-1 text-left transition-colors hover:bg-black/[0.02]">
        <span className="shrink-0" style={{ color: failedCount > 0 ? colors.red : primaryInfo.color }}>{primaryInfo.icon}</span>
        <span className="shrink-0 text-[12px]" style={{ color: colors.textSecondary }}>{title}</span>
        <span className="min-w-0 flex-1 truncate text-[11px]" style={{ color: colors.textDim }}>{summary}</span>
        <svg className={`h-3 w-3 shrink-0 transition-transform ${open ? 'rotate-90' : ''}`} style={{ color: `${colors.textDim}80` }}
             viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5">
          <polyline points="6 9 12 15 18 9" />
        </svg>
      </button>
      {open && (
        <div className="ml-[13px] space-y-0.5 border-l pl-2.5 pb-1.5" style={{ borderColor: `${colors.border}60` }}>
          {messages.map(message => <ToolActivityRow key={message.id} message={message} />)}
        </div>
      )}
    </div>
  )
}

function FinalAnswer({ content, active }: { content: string; active: boolean }) {
  const { colors } = useThemeStore()
  const parts = content ? splitThinkTags(content) : []
  return (
    content ? (
      <div className="py-0.5">
        <div className={`text-[13px] leading-relaxed ${active ? 'opacity-90' : 'opacity-100'}`}>
          {parts.map((part, index) => {
            if (part.type === 'think') return <ProcessRow key={index} content={part.content} active={part.isStreaming || false} />
            if (!part.content.trim()) return null
            return <MarkdownContent key={index} content={part.content} colors={colors} />
          })}
        </div>
      </div>
    ) : (
      <div className="text-[12px]" style={{ color: colors.textSecondary }}>Organizing results...</div>
    )
  )
}

function formatTurnDuration(totalSeconds: number) {
  const seconds = Math.max(1, Math.round(totalSeconds))
  const hours = Math.floor(seconds / 3600)
  const minutes = Math.floor((seconds % 3600) / 60)
  const remainingSeconds = seconds % 60
  if (hours > 0) return `${hours}h ${minutes}m ${remainingSeconds}s`
  if (minutes > 0) return `${minutes}m ${remainingSeconds}s`
  return `${remainingSeconds}s`
}

function AiTurnBlock({ msgs, colors, isLoading, streamStatus, onRetry }: {
  msgs: AgentMessage[]
  colors: ReturnType<typeof useThemeStore.getState>['colors']
  isLoading: boolean
  streamStatus: StreamStatus
  onRetry?: () => void
}) {
  const thinkingMsgs = msgs.filter(message => message.messageType === 'thinking')
  const textMessages = msgs.filter(message => message.messageType === 'text')
  const errorMessages = msgs.filter(message => message.messageType === 'error')
  const orderedMessages = [...msgs].sort((left, right) => left.timestamp - right.timestamp)
  const [now, setNow] = useState(Date.now())

  useEffect(() => {
    if (!isLoading) return
    const timer = window.setInterval(() => setNow(Date.now()), 1000)
    return () => window.clearInterval(timer)
  }, [isLoading])

  const finalTextMessage = [...textMessages].reverse().find(message => message.content.trim())
  const normalizedFinalAnswer = finalTextMessage?.content
    ?.replace(/<think>[\s\S]*?<\/think>/g, '')
    ?.trim() || ''
  const finalAnswerSignature = normalizeProcessContent(normalizedFinalAnswer)
  // ── root fix duplicate display(status machine square case)──
  // streaming text current at so" pending when final answer" identity directly in text message, identity by status machine decide set:
  // - back continue appear tool call → message is demote as thinking(process notes), content only one copy
  // - no tool call → done then turn align same bar text message
  // because at this point middle line inside not save at" process text + final answer" two copy relative same content, not again need need content relative like dedupe.
  // only keep a fallback:thinking hide when content matches the final answer exactly(prevent handle old session data/ error stream).
  const isDuplicateProcess = (content: string) => {
    const normalized = normalizeProcessContent(content)
    if (!normalized) return true
    return normalized === finalAnswerSignature
  }
  const finalDisplayContent = normalizedFinalAnswer
  const latestThinkingMessage = [...thinkingMsgs].reverse().find(message => {
    const content = message.content.trim()
    return content && content !== 'Thinking...' && !content.startsWith('Step')
  })
  const placeholderThinking = isLoading && thinkingMsgs.length > 0 && thinkingMsgs.every(message => message.content === 'Thinking...')
  const reconnecting = placeholderThinking && streamStatus === 'reconnecting'
  const disconnected = placeholderThinking && (streamStatus === 'disconnected' || streamStatus === 'error')
  const timelineItems: TurnTimelineItem[] = []
  for (const message of orderedMessages) {
    if (message.messageType === 'tool_call') {
      const previous = timelineItems[timelineItems.length - 1]
      if (previous?.kind === 'activity') {
        previous.messages.push(message)
      } else {
        timelineItems.push({ kind: 'activity', key: `activity-${message.id}`, messages: [message] })
      }
      continue
    }
    if (message.messageType === 'thinking' && isDuplicateProcess(message.content)) continue
    if (message.messageType === 'text' && message.role === 'assistant') {
      if (message.id === finalTextMessage?.id) continue
      const normalized = normalizeProcessContent(message.content)
      if (!normalized || normalized === finalAnswerSignature) continue
      timelineItems.push({ kind: 'message', message })
      continue
    }
    timelineItems.push({ kind: 'message', message })
  }

  const timestamp = msgs[0]?.timestamp || Date.now()
  const turnTime = new Date(timestamp).toLocaleString('en-US', {
    month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hour12: false,
  })
  const hasProcess = timelineItems.length > 0
  const elapsedEndTime = isLoading
    ? now
    : Math.max(timestamp, finalTextMessage?.timestamp || 0, ...msgs.map(message => message.timestamp))
  const elapsedSeconds = Math.max(0, (elapsedEndTime - timestamp) / 1000)

  return (
    <div className="space-y-0">
      <div className="flex gap-2 px-3 py-1.5">
        <div className="mt-[3px] flex h-6 w-6 shrink-0 items-center justify-center rounded-full"
             style={{ backgroundColor: `${colors.accent}14` }}>
          <svg className="h-3.5 w-3.5" style={{ color: colors.accent }} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">
            <path d="M12 3v4M8 5h8M6 10h12a2 2 0 0 1 2 2v6a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2v-6a2 2 0 0 1 2-2Z" />
            <path d="M9.5 14h.01M14.5 14h.01" />
          </svg>
        </div>
        <div className="flex min-w-0 flex-1 flex-col gap-1" style={{ maxWidth: 'calc(100% - 32px)' }}>
          <div className="flex items-center gap-1.5">
            <span className="text-[11px] font-medium" style={{ color: colors.textSecondary }}>ShellMind</span>
            <span className="text-[10px] opacity-70" style={{ color: colors.textDim }}>{turnTime}</span>
          </div>
          {(reconnecting || disconnected) && (
            <ProcessRow
              content={disconnected ? 'Connection lost. Waiting to recover.' : 'Reconnecting to the Agent service...'}
              active={reconnecting}
            />
          )}
          {timelineItems.map((item) => {
            if (item.kind === 'activity') return <ToolActivityGroup key={item.key} messages={item.messages} />
            const message = item.message
            if (message.messageType === 'thinking') {
              if (message.content === 'Thinking...' || message.content.startsWith('Step')) return null
              return <ProcessRow key={message.id} content={message.content} active={isLoading && !finalTextMessage && message.id === latestThinkingMessage?.id} />
            }
            if (message.messageType === 'text' && message.role === 'assistant') {
              return <ProcessRow key={message.id} content={message.content} active={isLoading && message.id === finalTextMessage?.id} />
            }
            if (message.messageType === 'summary') {
              return message.changeSummary ? <SessionSummaryCard key={message.id} summary={message.changeSummary} /> : null
            }
            if (message.messageType === 'error') {
              return (
                <div key={message.id} className="rounded-lg px-3 py-2 text-[12px] leading-relaxed"
                     style={{ backgroundColor: `${colors.red}10`, border: `1px solid ${colors.red}25`, color: colors.red }}>
                  {message.content}
                </div>
              )
            }
            return null
          })}
          {isLoading && !finalTextMessage && !timelineItems.some(item =>
            item.kind === 'activity' && item.messages.some(message => message.status === 'in_progress')
          ) && (
            <WaitingIndicator />
          )}
          {(hasProcess || finalTextMessage) && finalTextMessage && (
            <div className="pt-1.5">
              <div className="text-[10px] tabular-nums" style={{ color: colors.textDim }}>
                Elapsed {formatTurnDuration(elapsedSeconds)}
              </div>
              <div className="mt-2 h-px w-full" style={{ backgroundColor: `${colors.border}70` }} />
            </div>
          )}
          {finalTextMessage && (
            <FinalAnswer
              content={finalDisplayContent}
              active={isLoading}
            />
          )}
        </div>
      </div>

      {!isLoading && errorMessages.length > 0 && (
        <div className="flex gap-2 px-3 py-1.5">
          <div className="h-6 w-6 shrink-0" />
          <div className="flex items-center gap-2 rounded-lg px-3 py-2"
               style={{ backgroundColor: 'rgba(239, 68, 68, 0.08)', border: '1px solid rgba(239, 68, 68, 0.2)' }}>
            <span className="text-[13px]">⚠️</span>
            <span className="text-[11px]" style={{ color: '#f87171' }}>
              {errorMessages[errorMessages.length - 1]?.content || 'Chat interrupted'}
            </span>
            {onRetry && (
              <button className="rounded px-2.5 py-0.5 text-[10px] font-medium transition-all hover:opacity-80"
                      style={{ backgroundColor: colors.accent, color: '#fff' }} onClick={onRetry}>
                Continue chat
              </button>
            )}
          </div>
        </div>
      )}
    </div>
  )
}

function WaitingIndicator() {
  const { colors } = useThemeStore()
  return (
    <div className="flex min-w-0 items-center py-1">
      <span className="flex items-center gap-1">
        {[0, 160, 320].map((delay, index) => (
          <span key={index} className="h-1.5 w-1.5 rounded-full animate-bounce"
                style={{ backgroundColor: colors.textDim, animationDelay: `${delay}ms` }} />
        ))}
      </span>
    </div>
  )
}

function AgentActionCapsule({ messages, streamStatus }: {
  messages: AgentMessage[]
  streamStatus: StreamStatus
}) {
  const { colors } = useThemeStore()
  const [elapsed, setElapsed] = useState(0)
  const groupId = messages[messages.length - 1]?.groupId
  const turnMessages = groupId ? messages.filter(message => message.groupId === groupId) : messages
  const toolMessages = turnMessages.filter(message => message.messageType === 'tool_call')
  const activeTool = [...toolMessages].reverse().find(message => message.status === 'in_progress')
  const finalText = [...turnMessages].reverse().find(message => message.messageType === 'text' && message.role === 'assistant' && message.content.trim())
  const startedAt = turnMessages[0]?.timestamp || Date.now()

  useEffect(() => {
    const timer = window.setInterval(() => {
      setElapsed(Math.max(0, Math.round((Date.now() - startedAt) / 1000)))
    }, 1000)
    return () => window.clearInterval(timer)
  }, [startedAt])

  const failedCount = toolMessages.filter(message => message.status === 'failure').length
  const action = activeTool
    ? {
        label: getToolIconInfo(activeTool.toolName || '').label,
        detail: extractToolSummaryGlobal(activeTool.toolName || '', activeTool.toolParams),
      }
    : finalText
      ? { label: 'Organizing results', detail: '' }
      : streamStatus === 'reconnecting'
        ? { label: 'Reconnecting', detail: '' }
        : streamStatus === 'disconnected' || streamStatus === 'error'
          ? { label: 'Connection error', detail: '' }
          : toolMessages.length > 0
            ? { label: 'Planning the next step', detail: `Completed ${toolMessages.length - failedCount}/${toolMessages.length} steps` }
            : { label: 'Analyzing', detail: '' }
  const actionColor = streamStatus === 'disconnected' || streamStatus === 'error' ? colors.red : colors.accent

  return (
    <div className="flex min-w-0 max-w-[420px] items-center gap-2 rounded-full px-2.5 py-1 shadow-sm"
         style={{
           backgroundColor: `${actionColor}10`,
           border: `1px solid ${actionColor}22`,
           color: actionColor,
         }}>
      <svg className="h-3 w-3 shrink-0 animate-spin" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5">
        <path d="M21 12a9 9 0 1 1-6.219-8.56" />
      </svg>
      <span className="shrink-0 text-[11px] font-medium">{action.label}</span>
      {action.detail && <span className="min-w-0 flex-1 truncate text-[10px]" style={{ color: colors.textSecondary }}>{action.detail}</span>}
      <span className="shrink-0 text-[10px] tabular-nums" style={{ color: colors.textDim }}>{elapsed}s</span>
    </div>
  )
}
function parseToolResultPayload(raw?: string): Record<string, any> | null {
  if (!raw) return null
  try {
    return JSON.parse(raw)
  } catch {
    const match = raw.match(/"path"\s*:\s*"([^"]+)"/)
    return match ? { path: match[1] } : null
  }
}

/**
 * extract a user-friendly summary from the raw error.
 * Java different always stack stack,Caused by equal info will filter, only keep core error description.
 */
function extractErrorMessage(raw: string): string {
  if (!raw) return 'Unknown error'
  const s = raw.trim()

  // 1. extract Caused by center core message(like "Server error: 500 Internal Server Error from POST...")
  const causedByMatch = s.match(/Caused by:\s*(.+?)(?:\s+at\s|\s*\.{3}\s+\d+)/s)
  if (causedByMatch) {
    let msg = causedByMatch[1].trim()
    // truncate break via long URL
    msg = msg.replace(/(POST|GET|PUT|DELETE)\s+(https?:\/\/[^\s]+)(\s|$)/, '$1 <URL> ')
    if (msg.length > 200) msg = msg.substring(0, 200) + '...'
    return msg
  }

  // 2. extract one ok has meaning meaning content(skip pure stack stack ok)
  const lines = s.split('\n')
  for (const line of lines) {
    const trimmed = line.trim()
    if (!trimmed || trimmed.startsWith('at ') || trimmed.startsWith('...')) continue
    // skip pure different always class name ok(like "java.lang.RuntimeException:..." but keep colon after content)
    const colonIdx = trimmed.indexOf(':')
    if (colonIdx > 0 && colonIdx < 60) {
      const afterColon = trimmed.substring(colonIdx + 1).trim()
      if (afterColon) {
        let msg = afterColon.replace(/(POST|GET|PUT|DELETE)\s+(https?:\/\/[^\s]+)/, '$1 <URL>')
        if (msg.length > 200) msg = msg.substring(0, 200) + '...'
        return msg
      }
    }
    // non stack stack ok, directly return
    if (trimmed.length < 300) return trimmed
  }

  // 3. fallback: truncate break to merge handle long
  return s.length > 200 ? s.substring(0, 200) + '...' : s
}

interface RightSidebarProps {
  width?: number
  activeTerminalSessionId?: string | null
  expanded?: boolean
  onToggleExpanded?: () => void
}

/**
 * clean paste text center Markdown format symbol, turn as pure text
 */
function stripMarkdownForPaste(text: string): string {
  if (!text) return text
  return text
    // title: ## title → title
    .replace(/^#{1,6}\s+/gm, '')
    // bold: **text** or __text__ → text
    .replace(/\*\*(.+?)\*\*/g, '$1')
    .replace(/__(.+?)__/g, '$1')
    // italic: *text* or _text_ → text(avoid error match list mark)
    .replace(/(?<!\w)\*([^*]+?)\*(?!\w)/g, '$1')
    .replace(/(?<!\w)_([^_]+?)_(?!\w)/g, '$1')
    // inline code: `text` → text
    .replace(/`([^`]+?)`/g, '$1')
    // link: [text](url) → text
    .replace(/\[([^\]]+?)\]\([^)]+?\)/g, '$1')
    // image:![alt](url) → alt
    .replace(/!\[([^\]]*?)\]\([^)]+?\)/g, '$1')
    // no order list: - item / * item / + item → item
    .replace(/^\s*[-*+]\s+/gm, '')
    // has order list: 1. item → item
    .replace(/^\s*\d+\.\s+/gm, '')
    // quote: > text → text
    .replace(/^>\s*/gm, '')
    // divider: --- or *** → blank line
    .replace(/^[-*_]{3,}\s*$/gm, '')
    // code block mark: ``` → remove
    .replace(/^```\w*$/gm, '')
}

const streamAbortHandlers = new Map<string, () => void>()
const streamActivityAt = new Map<string, number>()

export function RightSidebar({ width = 400, activeTerminalSessionId, expanded, onToggleExpanded }: RightSidebarProps) {
  const { colors } = useThemeStore()
  const models = useModelStore((s) => s.models)
  const selectedModelId = useModelStore((s) => s.selectedModelId)
  const selectModel = useModelStore((s) => s.selectModel)
  const runtimeStatus = useRuntimeStore((s) => s.status)
  const runtimeStarting = useRuntimeStore((s) => s.starting)
  const runtimeError = useRuntimeStore((s) => s.error)
  const runtimeReady = runtimeStatus.running && runtimeStatus.port > 0 && !!runtimeStatus.token
  const {
    sessions,
    currentSessionId,
    setCurrentSession,
    inputText,
    setInputText,
    addMessage,
    addToolCallMessage,
    updateToolMessageStatus,
    upsertTextMessage,
    addSummaryMessage,
    addThinkingMessage,
    replaceThinkingMessageById,
    demoteTextMessageToThinking,
    removePlaceholderThinkingMessages,
    addErrorMessage,
    markGroupInProgressAsFailure,
    editAndRetry,
    clearMessages,
    loadingSessionIds,
    setSessionLoading,
    newConversation,
    currentAgentId,
    fetchAgents,
    // setCurrentAgentId not again use(unify Agent back no need switch)
    createServerSession,
  } = useAgentStore()
  const isLoading = !!currentSessionId && loadingSessionIds.includes(currentSessionId)
  const runningSessions = useMemo(
    () => loadingSessionIds
      .map(id => sessions.get(id))
      .filter((session): session is NonNullable<ReturnType<typeof sessions.get>> => !!session),
    [loadingSessionIds, sessions],
  )
  const allConversationProjects = useAgentStore(s => s.conversationProjects)
  const activeConversationProjectId = useAgentStore(s => s.activeConversationProjectId)
  const setActiveConversationProject = useAgentStore(s => s.setActiveConversationProject)

  const { connections, currentConnectionId } = useConnectionStore()
  const streamStatus = useStreamStore(s => s.status)
  const localProjects = useLocalFileStore(s => s.projects)
  const conversationProjects = allConversationProjects
  const {
    activeBinding,
    bindTerminal,
    inputTags,
    addInputTag,
    removeInputTag,
    getInputTagsContent,
    clearInputTags,
    getTerminalSessionByConnection,
  } = useSshAgentStore()

  // unify Agent back: initial start when auto settings currentAgentId as 200000
  useEffect(() => {
    fetchAgents()
    // if currentAgentId is empty or not is 200000, auto switch
    if (!currentAgentId) {
      useAgentStore.getState().setCurrentAgentId('200000')
    }
  }, [fetchAgents])

  useEffect(() => {
    if (runtimeReady) {
      void useModelStore.getState().fetchModels({ silent: true })
    }
  }, [runtimeReady])

  useEffect(() => {
    if (!runtimeReady && !runtimeStarting && !runtimeError) {
      void useRuntimeStore.getState().start(useLocalFileStore.getState().rootPath ?? undefined)
    }
  }, [runtimeReady, runtimeStarting, runtimeError])

  const currentSession = currentSessionId ? sessions.get(currentSessionId) : null
  const messagesEndRef = useRef<HTMLDivElement>(null)
  const messagesContainerRef = useRef<HTMLDivElement>(null)
  const inputRef = useRef<HTMLDivElement>(null)
  const [isManualScroll, setIsManualScroll] = useState(false)
  const inputHtmlRef = useRef<string>('')
  const lastRangeRef = useRef<Range | null>(null)
  const [isFocused, setIsFocused] = useState(false)
  const [isDragOverInput, setIsDragOverInput] = useState(false)

  // drag file/ terminal selected/ code snippet equal add in context back, auto restore input box focus(caret place than end end)
  const prevTagCountRef = useRef(0)
  useEffect(() => {
    const added = inputTags.length > prevTagCountRef.current
    prevTagCountRef.current = inputTags.length
    if (!added) return
    // equal React render complete new tag back again round focus, do not leave focus on the clicked button or drag source
    requestAnimationFrame(() => {
      const el = inputRef.current
      if (!el) return
      el.focus({ preventScroll: true })
      const selection = window.getSelection()
      if (selection) {
        const range = document.createRange()
        range.selectNodeContents(el)
        range.collapse(false)
        selection.removeAllRanges()
        selection.addRange(range)
      }
    })
  }, [inputTags])
  const [sendOnEnter, setSendOnEnter] = useState(() => {
    return localStorage.getItem('sendOnEnter') !== 'false'
  })
  const [showSendModeDropdown, setShowSendModeDropdown] = useState(false)
  const [showAttachmentMenu, setShowAttachmentMenu] = useState(false)
  const [showProjectSelector, setShowProjectSelector] = useState(false)
  const [showBranchSelector, setShowBranchSelector] = useState(false)
  const [projectBranches, setProjectBranches] = useState<string[]>([])
  const [branchLoading, setBranchLoading] = useState(false)
  const [branchSwitching, setBranchSwitching] = useState(false)
  const [branchError, setBranchError] = useState<string | null>(null)
  const [inputKey, setInputKey] = useState(0)
  const [errorRecovery, setErrorRecovery] = useState<ErrorRecovery | null>(null)

  // --- P2: shortcut panel & lead out panel ---
  const [showShortcutHelp, setShowShortcutHelp] = useState(false)
  const [showChatExport, setShowChatExport] = useState(false)
  // --- history note record panel ---
  const { showHistoryPanel, toggleHistoryPanel } = useAgentStore()

  // --- CommandMenu status ---
  const [cmdMenuTrigger, setCmdMenuTrigger] = useState<'/' | '@' | null>(null)
  const [cmdMenuIndex, setCmdMenuIndex] = useState(-1)
  const [cmdMenuQuery, setCmdMenuQuery] = useState('')
  const activeConversationProject = conversationProjects.find(project => project.id === activeConversationProjectId)
  const linkedProject = localProjects.find(project => project.id === activeConversationProject?.localProjectId)

  // available @ mention list(action state generate: project(current off link + other local + chat item goal)+ connected SSH server + pinned item)
  const mentionItems: MenuItem[] = useMemo(() => {
    const projectDesc = (p: { path: string; branch?: string | null }) =>
      p.branch ? `${p.branch} · ${p.path}` : p.path

    // current project: session already linked local project; if not yet off link local project but session this body has path, then off link session item goal
    const currentProjectItems: MenuItem[] = linkedProject
      ? [{
          id: `project-${linkedProject.id}`,
          label: linkedProject.name,
          description: projectDesc(linkedProject),
          icon: '🗂️',
          insertText: `@${linkedProject.name}`,
          category: 'Current project',
        }]
      : activeConversationProject && activeConversationProject.path
        ? [{
            id: `conv-project-${activeConversationProject.id}`,
            label: activeConversationProject.name,
            description: projectDesc({ path: activeConversationProject.path }),
            icon: '🗂️',
            insertText: `@${activeConversationProject.name}`,
            category: 'Current project',
          }]
        : []

    // other local project: exclude the currently linked project from opened local folders
    const otherLocalItems: MenuItem[] = localProjects
      .filter(p => p.id !== linkedProject?.id)
      .map(p => ({
        id: `project-${p.id}`,
        label: p.name,
        description: projectDesc(p),
        icon: '📦',
        insertText: `@${p.name}`,
        category: 'Other projects',
      }))

    // chat item goal: session project not linked to any local workspace(row remove current session, avoid up side duplicate)
    const conversationOnlyItems: MenuItem[] = conversationProjects
      .filter(cp => !cp.localProjectId && cp.id !== activeConversationProjectId && cp.path)
      .map(cp => ({
        id: `conv-project-${cp.id}`,
        label: cp.name,
        description: projectDesc({ path: cp.path! }),
        icon: '💬',
        insertText: `@${cp.name}`,
        category: 'Chat projects',
      }))

    const serverItems: MenuItem[] = connections
      .filter(c => c.status === ConnectionStatus.CONNECTED)
      .map(c => ({
        id: `server-${c.id}`,
        label: c.name,
        description: `${c.username}@${c.host}:${c.port}`,
        icon: '🖥️',
        insertText: `@${c.name}`,
        category: 'Server',
      }))
    const fixedItems: MenuItem[] = [
      { id: 'current-file', label: 'Current file', description: 'Insert the current open file', icon: '📄', insertText: '@current-file', category: 'Context' },
      { id: 'current-folder', label: 'Current folder', description: 'Insert the current working folder', icon: '📁', insertText: '@current-folder', category: 'Context' },
      { id: 'terminal', label: 'Terminal', description: 'Insert terminal selection', icon: '💻', insertText: '@terminal', category: 'Context' },
    ]
    // project group row most front, other nth server, most back fixed context item
    return [...currentProjectItems, ...otherLocalItems, ...conversationOnlyItems, ...serverItems, ...fixedItems]
  }, [connections, linkedProject, localProjects, conversationProjects, activeConversationProject, activeConversationProjectId])

  // --- SSE heartbeat timeout detect ---
  useEffect(() => {
    const interval = setInterval(() => {
      const store = useStreamStore.getState()
      // only in streaming/reconnecting status down detect heartbeat timeout
      if ((store.status === 'streaming' || store.status === 'reconnecting') && store.isHeartbeatStale()) {
        console.warn('[SSE] heartbeat stale, stream may be dead')
        // center break current fetch, let agent.ts catch handle reconnect
        if (currentSessionId && streamAbortHandlers.has(currentSessionId)) {
          streamAbortHandlers.get(currentSessionId)?.()
          streamAbortHandlers.delete(currentSessionId)
          // settings reconnect status, let agent.ts retry logic edit receive manage
          store.setRetrying(store.retryCount + 1)
          setSessionLoading(currentSessionId, true)
        }
        // if already via at reconnecting and exceed max retry, show error
        if (store.status === 'reconnecting' && store.retryCount >= store.maxRetries) {
          store.setError('SSE timed out; no heartbeat')
          store.reset()
          if (currentSessionId) setSessionLoading(currentSessionId, false)
        }
      }
    }, 10_000) // each 10s check one nth
    return () => clearInterval(interval)
  }, [currentSessionId, setSessionLoading])

  // --- input history lead navigate(support text + tab restore) ---
  interface HistoryEntry {
    text: string
    tags: Array<Pick<InputTag, 'label' | 'fullContent' | 'type' | 'connectionInfo'>>
  }
  const historyRef = useRef<HistoryEntry[]>([])
  const historyIndexRef = useRef<number>(-1) // -1 = current input
  const savedInputRef = useRef<string>('') // lead navigate front current input
  const savedInputTagsRef = useRef<Array<Pick<InputTag, 'label' | 'fullContent' | 'type' | 'connectionInfo'>>>([]) // lead navigate front current tab

  // from localStorage load history
  useEffect(() => {
    try {
      const stored = localStorage.getItem('chatInputHistory')
      if (stored) historyRef.current = JSON.parse(stored)
    } catch {}
  }, [])

  // save history to localStorage
  const saveHistory = (history: HistoryEntry[]) => {
    try {
      // most extra keep 200 bar
      const trimmed = history.slice(-200)
      localStorage.setItem('chatInputHistory', JSON.stringify(trimmed))
      historyRef.current = trimmed
    } catch {}
  }

  // add one bar history note record(send when call)
  const pushHistory = (text: string, tags: Array<Pick<InputTag, 'label' | 'fullContent' | 'type' | 'connectionInfo'>>) => {
    if (!text.trim() && tags.length === 0) return
    const history = [...historyRef.current]
    // avoid consecutive duplicate(than than text+ tab count)
    const last = history[history.length - 1]
    if (last?.text !== text || last?.tags.length !== tags.length) {
      history.push({ text, tags })
      saveHistory(history)
    }
    historyIndexRef.current = -1
  }

  // to up lead navigate(more old history)
  const navigateHistoryUp = () => {
    const history = historyRef.current
    if (history.length === 0) return
    if (historyIndexRef.current === -1) {
      // from current input start lead navigate
      savedInputRef.current = inputRef.current?.innerText || ''
      savedInputTagsRef.current = inputTags.map(t => ({ label: t.label, fullContent: t.fullContent, type: t.type }))
      historyIndexRef.current = history.length - 1
    } else if (historyIndexRef.current > 0) {
      historyIndexRef.current--
    }
    const entry = history[historyIndexRef.current]
    if (inputRef.current) {
      inputRef.current.innerText = entry.text
      // restore tab
      clearInputTags()
      for (const tag of entry.tags) {
        addInputTag(tag)
      }
      // caret move to most back
      const range = document.createRange()
      range.selectNodeContents(inputRef.current)
      range.collapse(false)
      const sel = window.getSelection()
      sel?.removeAllRanges()
      sel?.addRange(range)
    }
  }

  // to down lead navigate(update history)
  const navigateHistoryDown = () => {
    const history = historyRef.current
    if (historyIndexRef.current === -1) return // already via at current input
    if (historyIndexRef.current < history.length - 1) {
      historyIndexRef.current++
      const entry = history[historyIndexRef.current]
      if (inputRef.current) inputRef.current.innerText = entry.text
      // restore tab
      clearInputTags()
      for (const tag of entry.tags) {
        addInputTag(tag)
      }
    } else {
      // round to current input
      historyIndexRef.current = -1
      if (inputRef.current) inputRef.current.innerText = savedInputRef.current
      // restore front save tab
      clearInputTags()
      for (const tag of savedInputTagsRef.current) {
        addInputTag(tag)
      }
    }
    // caret move to most back
    if (inputRef.current) {
      const range = document.createRange()
      range.selectNodeContents(inputRef.current)
      range.collapse(false)
      const sel = window.getSelection()
      sel?.removeAllRanges()
      sel?.addRange(range)
    }
  }

  const { openTabs, activeTabKey, activeConnectionId, currentPathByConnection } = useFileExplorerStore()
  const isMac = typeof navigator !== 'undefined' && /Mac|iPhone|iPad|iPod/.test(navigator.platform)
  const currentKeyLabel = isMac ? 'Command + Enter' : 'Ctrl + Enter'

  const inputPlaceholder = () => {
    if (runtimeStarting || (runtimeStatus.running && runtimeStatus.port === 0)) {
      return 'Starting local Agent, please wait...'
    }
    if (!runtimeReady) {
      return runtimeError || 'Local Agent is disconnected; cannot send yet'
    }
    if (sendOnEnter) {
      return isMac
        ? 'Ask ShellMind... (Enter to send · Command + Enter for newline)'
        : 'Ask ShellMind... (Enter to send · Ctrl + Enter for newline)'
    }
    return `Ask ShellMind... (${currentKeyLabel} to send · Enter for newline)`
  }

  // auto scroll to bottom(only in non manual scroll mode)
  useEffect(() => {
    if (!isManualScroll) {
      messagesEndRef.current?.scrollIntoView({ behavior: 'smooth' })
    }
  }, [currentSession?.messages, isLoading, isManualScroll])

  const syncInputTextFromDom = () => {
    if (!inputRef.current) return
    const text = inputRef.current.innerText.replace(/\u00a0/g, ' ')
    setInputText(text)
    inputHtmlRef.current = inputRef.current.innerHTML
  }

  useEffect(() => {
    const handleSelectionChange = () => {
      const selection = window.getSelection()
      if (selection && selection.rangeCount > 0 && inputRef.current && inputRef.current.contains(selection.anchorNode)) {
        lastRangeRef.current = selection.getRangeAt(0).cloneRange()
      }
    }
    document.addEventListener('selectionchange', handleSelectionChange)
    return () => document.removeEventListener('selectionchange', handleSelectionChange)
  }, [])

  useEffect(() => {
    const handleInsertPhrase = (e: Event) => {
      const phrase = (e as CustomEvent<string>).detail
      if (!phrase || !inputRef.current) return
      const textNode = document.createTextNode(phrase)
      const selection = window.getSelection()
      const range = lastRangeRef.current && inputRef.current.contains(lastRangeRef.current.commonAncestorContainer)
        ? lastRangeRef.current
        : selection && selection.rangeCount > 0 && inputRef.current.contains(selection.anchorNode)
          ? selection.getRangeAt(0)
          : null

      if (range) {
        range.deleteContents()
        range.insertNode(textNode)
        range.setStartAfter(textNode)
        range.setEndAfter(textNode)
        selection?.removeAllRanges()
        selection?.addRange(range)
      } else {
        inputRef.current.appendChild(textNode)
      }
      inputRef.current.focus()
      syncInputTextFromDom()
    }

    window.addEventListener('shellmind-insert-phrase', handleInsertPhrase as EventListener)
    return () => window.removeEventListener('shellmind-insert-phrase', handleInsertPhrase as EventListener)
  }, [])

  useEffect(() => {
    const closeDropdown = () => setShowAttachmentMenu(false)
    if (showAttachmentMenu) {
      document.addEventListener('click', closeDropdown)
      return () => document.removeEventListener('click', closeDropdown)
    }
  }, [showAttachmentMenu])

  useEffect(() => {
    if (!showProjectSelector && !showBranchSelector) return
    const close = () => {
      setShowProjectSelector(false)
      setShowBranchSelector(false)
    }
    document.addEventListener('pointerdown', close)
    return () => document.removeEventListener('pointerdown', close)
  }, [showProjectSelector, showBranchSelector])

  useEffect(() => {
    if (!inputRef.current) return
    if (inputHtmlRef.current === inputRef.current.innerHTML) return
    if (inputText) return
    inputRef.current.innerHTML = ''
    inputHtmlRef.current = ''
  }, [inputText])

  useEffect(() => {
    if (!inputRef.current) return
    const html = inputRef.current.innerHTML
    if (html.trim()) {
      inputHtmlRef.current = html
      return
    }
    setInputText('')
    inputHtmlRef.current = ''
  }, [inputKey])

  // --- global shortcut:? open help panel ---
  useEffect(() => {
    const handleGlobalKeyDown = (e: KeyboardEvent) => {
      // input box inside not trigger, avoid do interrupt align always input
      const tag = (e.target as HTMLElement)?.tagName
      if (tag === 'INPUT' || tag === 'TEXTAREA' || (e.target as HTMLElement)?.isContentEditable) return
      if (e.key === '?') {
        e.preventDefault()
        setShowShortcutHelp(prev => !prev)
      }
      if (e.key === 'Escape') {
        setShowShortcutHelp(false)
        setShowChatExport(false)
        if (useAgentStore.getState().showHistoryPanel) useAgentStore.getState().toggleHistoryPanel()
      }
    }
    window.addEventListener('keydown', handleGlobalKeyDown)
    return () => window.removeEventListener('keydown', handleGlobalKeyDown)
  }, [])

  useEffect(() => {
    const autoBindCurrentConnection = async () => {
      if (!activeTerminalSessionId) return
      if (activeBinding?.terminalSessionId === activeTerminalSessionId) return
      const connection = currentConnectionId
        ? connections.find((c) => c.id === currentConnectionId)
        : connections.find((c) => c.status === ConnectionStatus.CONNECTED)
      if (!connection || connection.status !== ConnectionStatus.CONNECTED) return
      if (!currentSessionId && currentAgentId) {
        await createServerSession(currentAgentId)
      }
      const sessionId = useAgentStore.getState().currentSessionId
      if (!sessionId) return
      const success = await bindTerminal(
        sessionId,
        activeTerminalSessionId,
        {
          connectionId: connection.id,
          connectionName: connection.name,
          host: connection.host,
          port: connection.port,
          username: connection.username,
        }
      )
      if (success) {
      }
    }

    autoBindCurrentConnection()
  }, [
    activeTerminalSessionId,
    activeBinding,
    currentConnectionId,
    connections,
    currentAgentId,
    bindTerminal,
    createServerSession,
  ])

  const insertTagAtCursor = (tag: { id: string; label: string; type: 'terminal-selection' | 'file' | 'directory' | 'custom' | 'connection'; fullContent: string }) => {
    if (!inputRef.current) return
    // only write Zustand store(store render layer fail responsible show tab, avoid DOM insert cause duplicate)
    addInputTag(tag)
    inputRef.current.focus()
  }

  const handleAddSentContextTag = (tag: NonNullable<AgentMessage['contextTags']>[number]) => {
    if (!tag.fullContent?.trim()) return
    insertTagAtCursor({
      id: `sent_${Date.now()}`,
      label: tag.label,
      fullContent: tag.fullContent,
      type: tag.kind === 'terminal'
        ? 'terminal-selection'
        : tag.kind === 'file' || tag.kind === 'directory' || tag.kind === 'connection'
          ? tag.kind
          : 'custom',
    })
  }

  const handleAddCurrentFile = () => {
    // first check local file
    const localTab = useLocalFileStore.getState().openTabs.find(
      (t) => !isLocalDiffTab(t) && t.key === useLocalFileStore.getState().activeTabKey
    ) as LocalOpenTab | undefined
    if (localTab && localTab.content) {
      insertTagAtCursor({
        id: `file_${Date.now()}`,
        label: `File: ${localTab.name}`,
        fullContent: `Local file: ${localTab.path}\n\n\`\`\`\n${localTab.content}\n\`\`\``,
        type: 'file',
      })
      setShowAttachmentMenu(false)
      return
    }
    // remote file
    if (!activeTabKey) return
    const tab = openTabs.find(t => t.key === activeTabKey)
    if (!tab || isDiffTab(tab) || !tab.content) return
    insertTagAtCursor({
      id: `file_${Date.now()}`,
      label: `File: ${tab.name}`,
      fullContent: `File path: ${tab.path}\n\n${tab.content}`,
      type: 'file',
    })
    setShowAttachmentMenu(false)
  }

  const handleAddCurrentFolder = () => {
    if (!activeConnectionId) return
    const cwd = currentPathByConnection[activeConnectionId] || '/'
    const children = useFileExplorerStore.getState().childrenByConnection[activeConnectionId]?.[cwd]
    let filesList = ''
    if (children && children.length > 0) {
      filesList = `\nDirectory preview:\n` + children.map(c => `  ${c.directory ? '📁' : '📄'} ${c.name}`).join('\n')
    }
    insertTagAtCursor({
      id: `folder_${Date.now()}`,
      label: `Folder: ${cwd}`,
      fullContent: `Working directory: ${cwd}${filesList}`,
      type: 'directory',
    })
    setShowAttachmentMenu(false)
  }

  const handleAddSelectedText = () => {
    // @ts-ignore
    const editor = window.__activeMonacoEditor
    if (editor) {
      const selection = editor.getSelection()
      const text = editor.getModel()?.getValueInRange(selection)
      if (text) {
        // check whether the file is local or remote
        const localTab = useLocalFileStore.getState().openTabs.find(
          (t) => t.key === useLocalFileStore.getState().activeTabKey
        )
        const remoteTab = useFileExplorerStore.getState().openTabs.find(
          (t) => t.key === useFileExplorerStore.getState().activeTabKey
        )
        
        const filePath = localTab?.path || remoteTab?.path || 'unknown'
        const fileName = localTab?.name || remoteTab?.name || 'unknown'
        const prefix = localTab ? 'Local file' : 'Remote file'
        
        const startLine = selection.startLineNumber
        const endLine = selection.endLineNumber
        const lineRange = startLine === endLine ? `line ${startLine}` : `lines ${startLine}-${endLine}`
        
        insertTagAtCursor({
          id: `sel_${Date.now()}`,
          label: `Selected: ${fileName} (${lineRange})`,
          fullContent: `${prefix}: ${filePath} (${lineRange})\nSelected code/text:\n\`\`\`\n${text}\n\`\`\``,
          type: 'terminal-selection',
        })
      }
    }
    setShowAttachmentMenu(false)
  }

  const addDroppedPath = async (path: string) => {
    try {
      const fileInfo = await stat(path)
      const name = path.replace(/\\/g, '/').split('/').filter(Boolean).pop() || path

      if (fileInfo.isDirectory) {
        const children = await useLocalFileStore.getState().readDirectory(path, 1)
        const preview = children
          .slice(0, 30)
          .map(child => `${child.directory ? '📁' : '📄'} ${child.name}`)
          .join('\n')
        addInputTag({
          label: `Folder: ${name}`,
          fullContent: `Local folder: ${path}\n\nFolder contents:\n${preview || '(empty folder)'}${children.length > 30 ? '\n...' : ''}`,
          type: 'directory',
        })
        return
      }

      const content = await useLocalFileStore.getState().readFileContent(path)
      const safeContent = content == null
        ? '(Unable to read file)'
        : content.length > 200_000
          ? `${content.slice(0, 200_000)}\n\n[File truncated]`
          : content
      addInputTag({
        label: `File: ${name}`,
        fullContent: `Local file: ${path}\n\n\`\`\`\n${safeContent}\n\`\`\``,
        type: 'file',
      })
    } catch (error) {
      console.error('[RightSidebar] dropped file read failed:', path, error)
      addInputTag({
        label: `File: ${path.split('/').pop() || path}`,
        fullContent: `Local file: ${path}\n\n(Unable to read file)`,
        type: 'file',
      })
    }
  }

  const readNativeEntry = async (entry: any, depth = 0): Promise<{ label: string; isDirectory: boolean }[]> => {
    if (!entry) return []
    if (entry.isFile) {
      return [{ label: `📄 ${entry.name || 'File'}`, isDirectory: false }]
    }
    if (entry.isDirectory && depth < 1) {
      const reader = entry.createReader()
      const entries = await new Promise<any[]>((resolve, reject) => {
        const all: any[] = []
        const readBatch = () => reader.readEntries((batch: any[]) => {
          if (!batch.length) return resolve(all)
          all.push(...batch)
          readBatch()
        }, reject)
        readBatch()
      })
      const result = await Promise.all(entries.map(child => readNativeEntry(child, depth + 1)))
      return result.flat()
    }
    if (entry.isDirectory) return [{ label: `📁 ${entry.name || 'Folder'}`, isDirectory: true }]
    return []
  }

  const handleNativeDrop = async (event: React.DragEvent<HTMLDivElement>) => {
    event.preventDefault()
    event.stopPropagation()
    setIsDragOverInput(false)
    const localPaths = event.dataTransfer.getData('application/x-shellmind-local-path')
      || event.dataTransfer.getData('text/plain')
    if (localPaths) {
      const paths = localPaths.split(/\r?\n/).map(path => path.trim()).filter(Boolean)
      if (paths.length) {
        await Promise.all(paths.map(path => addDroppedPath(path)))
        return
      }
    }
    const items = Array.from(event.dataTransfer.items)
    if (!items.some(item => item.kind === 'file')) {
      const text = event.dataTransfer.getData('text/plain')
      if (text) {
        addInputTag({
          label: `Text: ${text.slice(0, 40)}`,
          fullContent: text,
          type: 'custom',
        })
      }
      return
    }
    const entries = items
      .map(item => (item as any).webkitGetAsEntry?.())
      .filter(Boolean)
    if (!entries.length) return
    await Promise.all(entries.map(async entry => {
      if (entry.isFile) {
        const file: File = await new Promise((resolve, reject) => entry.file(resolve, reject))
        const text = await file.text()
        addInputTag({
          label: `File: ${entry.name || file.name}`,
          fullContent: `Local file: ${entry.fullPath || file.name}\n\n\`\`\`\n${text.slice(0, 200_000)}${text.length > 200_000 ? '\n\n[File truncated]' : ''}\n\`\`\``,
          type: 'file',
        })
        return
      }
      const children = await readNativeEntry(entry)
      const preview = children
        .slice(0, 30)
        .map(child => child.label)
        .join('\n')
      addInputTag({
        label: `Folder: ${entry.name}`,
        fullContent: `Local folder: ${entry.fullPath || entry.name}\n\nFolder contents:\n${preview || '(empty folder)'}${children.length > 30 ? '\n...' : ''}`,
        type: 'directory',
      })
    }))
  }

  const switchConversationProject = async (projectId: string) => {
    const project = conversationProjects.find(item => item.id === projectId)
    if (!project) return

    setActiveConversationProject(projectId)
    const linkedProject = localProjects.find(item => item.id === project.localProjectId)
    if (linkedProject) {
      window.dispatchEvent(new CustomEvent('open-local-project'))
    }

    const projectSessions = Array.from(useAgentStore.getState().sessions.values())
      .filter(session => session.projectId === projectId)
      .sort((a, b) => (b.messages.at(-1)?.timestamp || b.createdAt) - (a.messages.at(-1)?.timestamp || a.createdAt))
    const latestSession = projectSessions[0]

    if (latestSession) {
      setCurrentSession(latestSession.id)
    } else if (currentAgentId) {
      await useAgentStore.getState().newConversation(currentAgentId, projectId)
    }
  }

  const toggleBranchSelector = async () => {
    const nextValue = !showBranchSelector
    setShowBranchSelector(nextValue)
    setShowProjectSelector(false)
    if (!nextValue || !linkedProject) return

    setBranchLoading(true)
    setBranchError(null)
    const projectId = linkedProject.id
    try {
      await useLocalFileStore.getState().refreshGitBranch(linkedProject.path)
      const branches = await useLocalFileStore.getState().listProjectBranches(projectId)
      const projectExists = useLocalFileStore.getState().projects.some(item => item.id === projectId)
      if (projectExists) setProjectBranches(branches)
    } catch {
      setProjectBranches([])
      setBranchError('Failed to load branches')
    } finally {
      setBranchLoading(false)
    }
  }

  const switchProjectBranch = async (branch: string) => {
    if (!linkedProject || branchSwitching || branch === linkedProject.branch) return
    setBranchSwitching(true)
    setBranchError(null)
    const success = await useLocalFileStore.getState().switchProjectBranch(linkedProject.id, branch)
    setBranchSwitching(false)
    if (success) {
      setShowBranchSelector(false)
      setProjectBranches([])
    } else {
      setBranchError('Switch failed; check uncommitted changes')
    }
  }

  useEffect(() => {
    let disposed = false
    let unlisten: (() => void) | null = null

    void getCurrentWindow().onDragDropEvent((event) => {
      const rect = inputRef.current?.getBoundingClientRect()
      const scale = window.devicePixelRatio || 1
      const position = 'position' in event.payload ? event.payload.position : null
      const inside = rect && position
        ? position.x / scale >= rect.left
          && position.x / scale <= rect.right
          && position.y / scale >= rect.top
          && position.y / scale <= rect.bottom
        : false

      if (event.payload.type === 'enter' || event.payload.type === 'over') {
        setIsDragOverInput(inside)
        return
      }
      if (event.payload.type === 'drop') {
        setIsDragOverInput(false)
        if (inside && !disposed) {
          void Promise.all(event.payload.paths.map(path => addDroppedPath(path)))
        }
        return
      }
      setIsDragOverInput(false)
    }).then((dispose) => {
      if (disposed) dispose()
      else unlisten = dispose
    })

    return () => {
      disposed = true
      unlisten?.()
    }
  }, [])

  const handleSend = async () => {
    if (isLoading || !currentAgentId || !inputRef.current) return
    // cancel manual scroll when sending a new message, auto scroll to bottom
    setIsManualScroll(false)
    // clear remove front error restore card
    setErrorRecovery(null)
    const plainText = inputRef.current.innerText.replace(/\u00a0/g, ' ').trim()
    if ((!plainText && inputTags.length === 0) || isLoading) return

    if (!runtimeReady) {
      await useRuntimeStore.getState().start(useLocalFileStore.getState().rootPath ?? undefined)
      const readyStatus = useRuntimeStore.getState().status
      if (!(readyStatus.running && readyStatus.port > 0 && readyStatus.token)) {
        setErrorRecovery({
          type: 'unknown',
          title: 'Local Agent disconnected',
          message: useRuntimeStore.getState().error || 'Restart ShellMind or check runtime status.',
        })
        return
      }
    }

    // save to input history(text + tab)
    if (plainText || inputTags.length > 0) {
      pushHistory(plainText, inputTags.map(t => ({ label: t.label, fullContent: t.fullContent, type: t.type })))
    }

    if (!currentSessionId) {
      await createServerSession(currentAgentId)
    }
    const sessionId = useAgentStore.getState().currentSessionId
    if (!sessionId) return

    let messageContent = plainText
    // displayContent start end use pure text/Markdown format, do not use domHtml(original HTML contains <span> tab will cause render different always)
    let displayContent = plainText

    // extract image inlineDatas(pass to backend AI template type extra template state data)
    const inlineDatas: { data: string; mimeType: string }[] = []
    inputTags.forEach(tag => {
      const dataUrlMatch = tag.fullContent.match(/data:image\/([a-zA-Z]+);base64,([A-Za-z0-9+/=]+)/)
      if (dataUrlMatch) {
        inlineDatas.push({
          mimeType: `image/${dataUrlMatch[1]}`,
          data: dataUrlMatch[2],
        })
      }
    })

    const tagsContent = getInputTagsContent()
    if (tagsContent) {
      // messageContent send to backend: by tab type shape note in, avoid historical context misleading the current intent
      const serverTagParts: string[] = []
      inputTags.forEach(tag => {
        const dataUrlMatch = tag.fullContent.match(/(data:image\/[a-zA-Z]+;base64,[A-Za-z0-9+/=]+)/)
        if (dataUrlMatch) {
          serverTagParts.push(`[User uploaded image: ${tag.label}]`)
          return
        }
        // by tab type clear confirm mark note corner color, let AI distinguish" action goal mark" and" see info"
        if (tag.type === 'directory') {
          serverTagParts.push(`[Target directory: ${tag.label}]\n${tag.fullContent}`)
        } else if (tag.type === 'file') {
          serverTagParts.push(`[Target file: ${tag.label}]\n${tag.fullContent}`)
        } else if (tag.type === 'connection') {
          serverTagParts.push(`[Current SSH connection: ${tag.label}]\n${tag.fullContent}`)
        } else if (tag.type === 'project') {
          serverTagParts.push(`[Target project: ${tag.label}]\n${tag.fullContent}`)
        } else if (tag.type === 'terminal-selection') {
          serverTagParts.push(`[Terminal selection: ${tag.label}]\n${tag.fullContent}`)
        } else {
          serverTagParts.push(tag.fullContent)
        }
      })
      const formattedServerTags = serverTagParts.join('\n\n---\n\n').split('\n').map(line => `> ${line}`).join('\n')
      // clear confirm notify know AI: user current meaning image by text decide set, tab only extract for action goal mark/ context
      const intentHint = plainText
        ? `${plainText}\n\n**Current targets and context (follow the user's text):**\n${formattedServerTags}`
        : `**Current targets and context:**\n${formattedServerTags}`
      messageContent = intentHint

      displayContent = plainText
    }

    // SSH server context: preferred first use user @ select server tab, otherwise auto find already bind/ connected connection
    const connectionTag = inputTags.find(t => t.type === 'connection')
    let sshContextConn: { connectionId: string; connectionName: string; host: string; port: number; username: string } | null = null
    if (connectionTag?.connectionInfo) {
      sshContextConn = connectionTag.connectionInfo
    } else {
      const selectedConn = activeBinding
        ? connections.find((c) => c.id === activeBinding.connectionId)
        : connections.find((c) => c.id === currentConnectionId && c.status === ConnectionStatus.CONNECTED)
      if (selectedConn) {
        sshContextConn = {
          connectionId: selectedConn.id,
          connectionName: selectedConn.name,
          host: selectedConn.host,
          port: selectedConn.port,
          username: selectedConn.username,
        }
      }
    }
    if (sshContextConn) {
      const serverContext = `Current server: ${sshContextConn.connectionName} (${sshContextConn.username}@${sshContextConn.host}:${sshContextConn.port})`
      messageContent = `${serverContext}\n\n${messageContent}`
    }

    const selectedModelId = useModelStore.getState().selectedModelId

    const groupId = `group_${Date.now()}`
    const contextTags = inputTags.map(tag => {
      const imageDataUrl = tag.fullContent.match(/(data:image\/[a-zA-Z]+;base64,[A-Za-z0-9+/=]+)/)?.[1]
      return {
        label: tag.label,
        kind: tag.type === 'directory'
          ? 'directory' as const
          : tag.type === 'file'
            ? 'file' as const
            : tag.type === 'connection'
              ? 'connection' as const
              : tag.type === 'project'
                ? 'project' as const
              : imageDataUrl
                ? 'image' as const
                : tag.label.includes('Terminal')
                  ? 'terminal' as const
                  : 'custom' as const,
        imageDataUrl,
        fullContent: tag.fullContent,
      }
    })
    const userMessage: AgentMessage = {
      id: `msg_${Date.now()}`,
      role: 'user',
      content: displayContent,
      timestamp: Date.now(),
      messageType: 'text',
      groupId,
      contextTags,
    }
    addMessage(sessionId, userMessage)
    setInputText('')
    setSessionLoading(sessionId, true)

    // clear input box content(package include file tab)
    clearInputTags()
    if (inputRef.current) {
      inputRef.current.innerHTML = ''
      inputHtmlRef.current = ''
      setInputKey((k) => k + 1)
    }

    // extra message stream mode: not need need pre- create assistant message, each message type is created dynamically by callbacks
    // but need need set i.e. create a" Thinking" placeholder message, avoid user so as dead machine
    addThinkingMessage(sessionId, groupId, 'Thinking...')
    let textMsgId = '' // same groupId down only one bar text message,onText when upsert
    const toolCallMsgMap = new Map<string, string>() // toolCallId → msgId map
    const processSegmentMessages = new Map<number, string>()

    // update SSE stream status
    useStreamStore.getState().setStatus('connecting')
    useStreamStore.getState().touchActivity()
    streamActivityAt.set(sessionId, Date.now())

    streamAbortHandlers.set(sessionId, agentApi.reactChatStream(
      currentAgentId,
      'default',
      sessionId,
      messageContent,
      (step: ReActStep) => {
        // ── extra message stream: each step generate alone set message ──
        if (step.stepType === 'thinking') {
          // demote signal number: this front streaming render" pending when final answer" confirm is process notes → text message turn as thinking identity
          if (step.demotePendingText) {
            if (textMsgId) {
              demoteTextMessageToThinking(sessionId, textMsgId)
              // demoted messages become finished process segments, down one segment text other up new provisional answer
              textMsgId = ''
            }
            return
          }
          // pending when final answer:text event streaming push(truncate to current all text), directly upsert to text message real when render;
          // identity not yet set-- back continue if no tool call,done then turn align; if any, first demote as thinking
          if (step.isFinalText) {
            const content = step.content?.trim()
            if (!content) return
            textMsgId = upsertTextMessage(sessionId, groupId, content)
          } else {
            const content = step.content?.trim()
            if (!content || content === 'Thinking...' || content.startsWith('Step')) return
            const existingMessageId = processSegmentMessages.get(step.stepIndex)
            if (existingMessageId) {
              replaceThinkingMessageById(sessionId, existingMessageId, content)
            } else {
              const messageId = addThinkingMessage(sessionId, groupId, content)
              processSegmentMessages.set(step.stepIndex, messageId)
            }
          }
          useStreamStore.getState().setStatus('streaming')
          useStreamStore.getState().touchActivity()
          streamActivityAt.set(sessionId, Date.now())
        }

        else if (step.stepType === 'tool_call') {
          if (step.status === 'in_progress') {
            const msgId = addToolCallMessage(
              sessionId, groupId,
              step.toolCallId || `tc_${Date.now()}`,
              step.toolName || 'unknown',
              step.toolParams || ''
            )
            if (step.toolCallId) toolCallMsgMap.set(step.toolCallId, msgId)

            // command execute tool → write output panel
            const commandExecTools = ['executeLocalCommand', 'compileProject', 'compileTests', 'runUnitTests', 'executeSshCommand']
            if (step.toolName && commandExecTools.includes(step.toolName)) {
              useOutputStore.getState().addEntry({
                sessionId: `step-${step.stepIndex}`, command: step.toolParams || '',
                status: 'running', stdout: '', stderr: '', exitCode: null, durationMs: null,
              })
            }
          } else if (step.status === 'success' || step.status === 'failure') {
            // update tool message status: first press toolCallId match, find not to then by toolName match most back one bar in_progress message
            const tcId = step.toolCallId || ''
            let msgId = tcId ? toolCallMsgMap.get(tcId) : undefined
            if (!msgId && step.toolName) {
              const session = useAgentStore.getState().sessions.get(sessionId)
              if (session) {
                const match = [...session.messages]
                  .reverse()
                  .find(m => m.messageType === 'tool_call' && m.toolName === step.toolName && m.status === 'in_progress' && m.groupId === groupId)
                if (match) msgId = match.id
              }
            }
            if (msgId) updateToolMessageStatus(sessionId, msgId, step.status, step.toolResult)

            // command execute tool done → update output panel
            const commandExecTools = ['executeLocalCommand', 'compileProject', 'compileTests', 'runUnitTests', 'executeSshCommand']
            if (step.toolName && commandExecTools.includes(step.toolName)) {
              const outputStore = useOutputStore.getState()
              const entrySessionId = `step-${step.stepIndex}`
              let stdout = '', stderr = '', exitCode = -1, durationMs = 0
              if (step.toolResult) {
                try { const r = JSON.parse(step.toolResult); stdout = r.output || r.stdout || ''; stderr = r.stderr || ''; exitCode = r.exitCode ?? -1; durationMs = r.timeoutMs || 0 }
                catch { stdout = step.toolResult }
              }
              if (outputStore.entries.find(e => e.sessionId === entrySessionId)) {
                outputStore.updateEntry(entrySessionId, { status: step.status === 'success' ? 'success' : 'failed', stdout, stderr, exitCode, durationMs })
              } else {
                outputStore.addEntry({ sessionId: entrySessionId, command: step.toolParams || '', status: step.status === 'success' ? 'success' : 'failed', stdout, stderr: '', exitCode, durationMs: 0 })
              }
            }

            // local command execute done → ok can via rm/mv/ build produce object equal way edit action file → refresh local file tree
            const localCommandTools = ['executeLocalCommand', 'compileProject', 'compileTests', 'runUnitTests']
            if (step.toolName && localCommandTools.includes(step.toolName)) {
              const localStore = useLocalFileStore.getState()
              if (localStore.rootPath) localStore.refreshDirectory(localStore.rootPath).catch(() => {})
            }

            // file action tool done → refresh file tree + re- load editor
            if (step.toolName) {
              const fileWriteTools = ['writeLocalFile', 'createLocalFile', 'deleteLocalFile', 'writeFile', 'createFile', 'deleteFile', 'editLocalFile', 'editFile', 'editRemoteFile', 'writeRemoteFile', 'CodeEditTool', 'CodeEdit', 'applyEdit', 'applyEditTool']
              const isFileWriteTool = fileWriteTools.includes(step.toolName) || step.toolName.toLowerCase().includes('edit') || step.toolName.toLowerCase().includes('write')
              if (isFileWriteTool) {
                const payload = parseToolResultPayload(step.toolResult)
                let changedPath = (payload?.path as string | undefined)
                  || (step.toolParams && /^\//.test(step.toolParams.trim()) ? step.toolParams.trim() : step.toolParams?.match(/(\/\w[\w./-]+\.[\w]+)/)?.[1])
                  || step.toolResult?.match(/([\/][\w./-]+\.[\w]+)/)?.[1]

                const isLocalTool = step.toolName.includes('Local')
                const isDeleteOp = step.toolName === 'deleteLocalFile' || step.toolName === 'deleteFile'

                if (isLocalTool) {
                  const localStore = useLocalFileStore.getState()
                  if (changedPath && !isDeleteOp) {
                    const localTab = localStore.openTabs.find(t => !isLocalDiffTab(t) && t.path === changedPath) as LocalOpenTab | undefined
                    const before = localTab?.content ?? ''
                    if (localTab) {
                      // file already open → reload update tab content + create preview
                      localStore.reloadFileByPath(changedPath).then(after => {
                        if (after != null && after !== before) useAiPatchStore.getState().upsertPreview({ target: 'local', path: changedPath!, toolName: step.toolName!, beforeContent: before, afterContent: after })
                      }).catch(() => {})
                    } else {
                      // file not yet open → directly read file content create preview(beforeContent is empty)
                      localStore.readFileContent(changedPath).then(after => {
                        if (after != null) useAiPatchStore.getState().upsertPreview({ target: 'local', path: changedPath!, toolName: step.toolName!, beforeContent: '', afterContent: after })
                      }).catch(() => {})
                    }
                  }
                  if (!changedPath) {
                    const activeTab = localStore.openTabs.find(t => t.key === localStore.activeTabKey)
                    if (activeTab && !isDeleteOp) localStore.reloadFileByPath(activeTab.path).catch(() => {})
                  }
                  const dir = changedPath ? changedPath.substring(0, changedPath.lastIndexOf('/')) : null
                  if (dir) localStore.refreshDirectory(dir).catch(() => {})
                  else if (localStore.rootPath) localStore.refreshDirectory(localStore.rootPath).catch(() => {})
                }

                if (!isLocalTool) {
                  const connId = activeBinding?.connectionId || currentConnectionId
                  if (connId) {
                    const fileStore = useFileExplorerStore.getState()
                    if (changedPath && !isDeleteOp) {
                      const remoteTab = fileStore.openTabs.find(t => !isDiffTab(t) && t.connectionId === connId && t.path === changedPath)
                      const before = remoteTab && !isDiffTab(remoteTab) ? remoteTab.content : ''
                      if (remoteTab && !isDiffTab(remoteTab)) {
                        // remote file already open → reload update tab content + create preview
                        fileStore.reloadFileByPath(connId, changedPath).then(after => {
                          if (after != null && after !== before) useAiPatchStore.getState().upsertPreview({ target: 'remote', path: changedPath!, connectionId: connId, toolName: step.toolName!, beforeContent: before, afterContent: after })
                        }).catch(() => {})
                      } else {
                        // remote file not yet open → via API read file content create preview(beforeContent is empty)
                        fileStore.readRemoteFileContent(connId, changedPath).then(after => {
                          if (after != null) useAiPatchStore.getState().upsertPreview({ target: 'remote', path: changedPath!, connectionId: connId, toolName: step.toolName!, beforeContent: '', afterContent: after })
                        }).catch(() => {})
                      }
                    }
                    if (!changedPath) {
                      const activeTab = fileStore.openTabs.find(t => t.connectionId === connId && t.key === fileStore.activeTabKey)
                      if (activeTab && !isDeleteOp) fileStore.reloadFileByPath(connId, activeTab.path).catch(() => {})
                    }
                    const dir = changedPath ? changedPath.substring(0, changedPath.lastIndexOf('/')) : null
                    if (dir) fileStore.refreshDirectory(connId, dir).catch(() => {})
                  }
                }
              }
            }
          }
        }
      },
      (fullText: string) => {
        // compatible non streaming demote; main pipeline process notes already via thinking split segment push
        textMsgId = textMsgId
        if (fullText) useStreamStore.getState().touchActivity()
        useStreamStore.getState().setStatus('streaming')
        streamActivityAt.set(sessionId, Date.now())
      },
      (finalContent: string) => {
        // only clear drop placeholder; real process segments stay as dim collapsible history
        removePlaceholderThinkingMessages(sessionId, groupId)
        if (finalContent && textMsgId) {
          upsertTextMessage(sessionId, groupId, finalContent)
        } else if (finalContent && !textMsgId) {
          upsertTextMessage(sessionId, groupId, finalContent)
        }
        streamAbortHandlers.delete(sessionId)
        setSessionLoading(sessionId, false)
        // only in no error when reset streamStore(partial cross pay when onError already first trigger)
        const streamState = useStreamStore.getState()
        if (streamState.status !== 'error') {
          streamState.reset()
          playCompleteSound()
        }
        const outputStore = useOutputStore.getState()
        outputStore.entries.forEach((entry) => {
          if (entry.status === 'running' && entry.sessionId.startsWith('tool-')) {
            outputStore.updateEntry(entry.sessionId, { status: 'success' })
          }
        })
      },
      (err: string) => {
        console.error('[reactChatStream] error:', err)
        streamAbortHandlers.delete(sessionId)
        setSessionLoading(sessionId, false)
        useStreamStore.getState().setError(err)
        playErrorSound()

        // error when clear remove placeholder thinking message
        removePlaceholderThinkingMessages(sessionId, groupId)

        const userFriendlyMsg = extractErrorMessage(err)
        addErrorMessage(sessionId, groupId, `Request failed: ${userFriendlyMsg}`)

        let errorType: ErrorRecovery['type'] = 'unknown'
        let errorTitle = 'Chat interrupted'
        if (err.includes('network') || err.includes('Failed to fetch') || err.includes('fetch') || err.includes('NetworkError') || err.includes('Failed')) {
          errorType = 'network'
          errorTitle = 'Network connection error'
        } else if (err.includes('413') || err.includes('too large') || err.includes('payload')) {
          errorType = 'context_limit'
          errorTitle = 'Request too large'
        } else if (/timeout|超时/i.test(err)) {
          errorType = 'network'
          errorTitle = 'Connection timed out'
        } else if (err.includes('500') || err.includes('502') || err.includes('503') || err.includes('504')) {
          errorType = 'network'
          errorTitle = 'Service temporarily unavailable'
        }
        setErrorRecovery({
          type: errorType, title: errorTitle, message: userFriendlyMsg,
          details: userFriendlyMsg !== err ? err : undefined,
        })
      },
      // terminalSessionId: preferred first use @ server tab corresponds to terminal session
      (() => {
        if (connectionTag?.connectionInfo) {
          const tsId = getTerminalSessionByConnection(connectionTag.connectionInfo.connectionId)
          if (tsId) return tsId
        }
        return activeTerminalSessionId || undefined
      })(),
      // onTaskBreakdown
      (_breakdown: TaskBreakdownDTO) => {
        // TODO: extra message stream mode down need need new task_breakdown type message
      },
      // onTaskProgress
      (_progress) => {
      },
      // onSubAgent
      (_subAgentInfo) => {
      },
      // onChangeSummary
      (changeSummary) => {
        addSummaryMessage(sessionId, groupId, changeSummary)

        const changedFiles = [...(changeSummary.modified || []), ...(changeSummary.created || [])]
        if (changedFiles.length === 0) return

        const localStore = useLocalFileStore.getState()
        const patchStore = useAiPatchStore.getState()
        const connId = activeBinding?.connectionId || currentConnectionId

        for (const file of changedFiles) {
          // ── local file change → create AiPatchPreview ──
          const localTab = localStore.openTabs.find(t => !isLocalDiffTab(t) && t.path === file.path) as LocalOpenTab | undefined
          if (localTab) {
            const before = localTab.content ?? ''
            localStore.reloadFileByPath(file.path).then((after) => {
              if (after != null && after !== before) {
                patchStore.upsertPreview({
                  target: 'local',
                  path: file.path,
                  toolName: file.kind === 'create' ? 'createLocalFile' : 'writeLocalFile',
                  beforeContent: before,
                  afterContent: after,
                })
              }
            }).catch(() => {})
          } else if (localStore.rootPath) {
            // file not yet at editor open → read current content as afterContent
            const fullPath = file.path.startsWith('/') ? file.path : `${localStore.rootPath}/${file.path}`
            localStore.readFileContent(fullPath).then((after) => {
              if (after != null) {
                patchStore.upsertPreview({
                  target: 'local',
                  path: fullPath,
                  toolName: file.kind === 'create' ? 'createLocalFile' : 'writeLocalFile',
                  beforeContent: '',  // not yet open file no beforeContent
                  afterContent: after,
                })
              }
            }).catch(() => {})
          }

          // ── remote file change → create AiPatchPreview ──
          if (connId) {
            const fileStore = useFileExplorerStore.getState()
            const remoteTab = fileStore.openTabs.find(t => !isDiffTab(t) && t.connectionId === connId && t.path === file.path)
            if (remoteTab && !isDiffTab(remoteTab)) {
              const before = remoteTab.content ?? ''
              fileStore.reloadFileByPath(connId, file.path).then((after) => {
                if (after != null && after !== before) {
                  patchStore.upsertPreview({
                    target: 'remote',
                    path: file.path,
                    connectionId: connId,
                    toolName: file.kind === 'create' ? 'createFile' : 'writeFile',
                    beforeContent: before,
                    afterContent: after,
                  })
                }
              }).catch(() => {})
            } else {
              // remote file not yet at editor open → via API read afterContent
              fileStore.readRemoteFileContent(connId, file.path).then((after) => {
                if (after != null) {
                  patchStore.upsertPreview({
                    target: 'remote',
                    path: file.path,
                    connectionId: connId,
                    toolName: file.kind === 'create' ? 'createFile' : 'writeFile',
                    beforeContent: '',
                    afterContent: after,
                  })
                }
              }).catch(() => {})
            }
          }
        }
      },
      // projectContext: note in current open project info(local folder + remote SSH)
      (() => {
        const localStore = useLocalFileStore.getState()
        const agentStore = useAgentStore.getState()
        const conversationProject = agentStore.conversationProjects.find(item => item.id === agentStore.activeConversationProjectId)
        const activeLocalProject = localStore.projects.find(item => item.id === localStore.activeLocalProjectId)
        const linkedLocalProject = localStore.projects.find(item => item.id === conversationProject?.localProjectId)
        const selectedLocalProject = linkedLocalProject || activeLocalProject

        if (selectedLocalProject?.path) {
          return {
            name: conversationProject?.name || selectedLocalProject.name,
            rootPath: selectedLocalProject.path,
            branch: selectedLocalProject.branch || null,
          }
        }

        // fallback: take remote SSH file tree current working directory
        const remoteCwd = currentPathByConnection[activeConnectionId || '']
        if (remoteCwd) {
          const name = remoteCwd.split('/').filter(Boolean).pop() || ''
          return name ? { name, rootPath: remoteCwd } : null
        }
        return null
      })(),
      // ── add SSE event callback ──
      // onPermissionConfirm: permission confirm request → push permissionStore
      (permissionData) => {
        usePermissionStore.getState().pushConfirmation({
          ...permissionData,
          arrivedAt: Date.now(),
        })
      },
      // onToolOutput: tool real when output snippet → update output panel
      (toolCallId, outputChunk) => {
        const outputStore = useOutputStore.getState()
        const entrySessionId = `tool-${toolCallId}`
        const existing = outputStore.entries.find((e) => e.sessionId === entrySessionId)
        if (existing) {
          outputStore.updateEntry(entrySessionId, {
            stdout: (existing.stdout || '') + outputChunk,
          })
        } else {
          // first receive output snippet, create bar goal
          outputStore.addEntry({
            sessionId: entrySessionId,
            command: '',
            status: 'running' as const,
            stdout: outputChunk,
            stderr: '',
            exitCode: null,
            durationMs: null,
          })
        }
      },
      // onStatus: status update message → update streamStore
      (statusMessage) => {
        useStreamStore.getState().setStatusMessage(statusMessage)
      },
      // onWarning: warning message
      (_warningMessage) => {
      },
      // onRoundStart: new round nth start
      (_roundIndex) => {
      },
      // onReconnect: stream center path disconnect reconnect
      (attempt, _maxAttempts) => {
        useStreamStore.getState().setStatus('reconnecting')
        useStreamStore.getState().setRetrying(attempt)
      },
      // onHeartbeat: backend heartbeat keep live
      () => {
        useStreamStore.getState().touchActivity()
        streamActivityAt.set(sessionId, Date.now())
      },
      // inlineDatas: extra template state image data
      inlineDatas.length > 0 ? inlineDatas : undefined,
      // modelId: input box select template type
      selectedModelId,
    ))
  }

  const handleStop = () => {
    if (currentSessionId && streamAbortHandlers.has(currentSessionId)) {
      streamAbortHandlers.get(currentSessionId)?.()
      streamAbortHandlers.delete(currentSessionId)
      setSessionLoading(currentSessionId, false)
    }
    toolProgressStore.clear()

    // current groupId all below in_progress tool message mark as failure
    if (currentSessionId) {
      const session = sessions.get(currentSessionId)
      if (session) {
        const lastUserMsg = [...session.messages].reverse().find(m => m.role === 'user')
        if (lastUserMsg?.groupId) {
          markGroupInProgressAsFailure(currentSessionId, lastUserMsg.groupId)
        }
      }
    }
  }

  // IME group merge status follow trace:
  // WebKit(Safari/WKWebView) at" Enter confirm wait select word" when,compositionend happens before keydown trigger,
  // cause e.nativeEvent.isComposing already false, only rely it judge break will error send.
  // and compositionend with phantom Enter keydown ok can split belong not same event loop task,
  // setTimeout(0) the timer may fire between the two and miss the intercept,
  // because this edit use timestamp window port:compositionend back 250ms inside Enter one rule view as confirm Enter merge block truncate.
  const isComposingRef = useRef(false)
  const compositionEndedAtRef = useRef(0)

  const handleCompositionStart = () => {
    isComposingRef.current = true
    compositionEndedAtRef.current = 0
  }

  const handleCompositionEnd = () => {
    isComposingRef.current = false
    compositionEndedAtRef.current = performance.now()
  }

  const isImeProcessing = (e: React.KeyboardEvent<HTMLDivElement>) => {
    if (isComposingRef.current || e.nativeEvent.isComposing || e.keyCode === 229) {
      return true
    }
    // phantom shadow confirm Enter:compositionend just end not long Enter(user again nth by Enter send through always >250ms)
    if (
      e.key === 'Enter' &&
      compositionEndedAtRef.current > 0 &&
      performance.now() - compositionEndedAtRef.current < 250
    ) {
      return true
    }
    return false
  }

  const handleKeyDown = (e: React.KeyboardEvent<HTMLDivElement>) => {
    if (isImeProcessing(e)) return
    const isModifier = e.metaKey || e.ctrlKey

    // --- shortcut body system ---
    // Ctrl/Cmd+L: clear input box
    if (isModifier && e.key === 'l') {
      e.preventDefault()
      if (inputRef.current) {
        inputRef.current.innerHTML = ''
        inputRef.current.focus()
      }
      return
    }
    // Ctrl/Cmd+Shift+Backspace: clear current session message(keep session)
    if (isModifier && e.shiftKey && e.key === 'Backspace') {
      e.preventDefault()
      if (currentSessionId) {
        clearMessages(currentSessionId)
      }
      return
    }

    // --- input history lead navigate ---
    // ↑: up one bar history(when the caret is at line start or the input is empty)
    if (e.key === 'ArrowUp' && !e.shiftKey && !isModifier) {
      const text = inputRef.current?.innerText || ''
      // only fire when the input is empty or the caret is on the first line
      const selection = window.getSelection()
      const isFirstLine = !selection || selection.anchorOffset === 0 || text.indexOf('\n') === -1
      if (isFirstLine && (text.length === 0 || historyIndexRef.current !== -1)) {
        e.preventDefault()
        navigateHistoryUp()
        return
      }
    }
    // ↓: down one bar history
    if (e.key === 'ArrowDown' && !e.shiftKey && !isModifier) {
      if (historyIndexRef.current !== -1) {
        const selection = window.getSelection()
        const text = inputRef.current?.innerText || ''
        const isLastLine = !selection || selection.anchorOffset === text.length || text.indexOf('\n') === -1
        if (isLastLine) {
          e.preventDefault()
          navigateHistoryDown()
          return
        }
      }
    }

    // --- send shortcut ---
    const shouldSend =
      (e.key === 'Enter' && !e.shiftKey && sendOnEnter && !isModifier) ||
      (e.key === 'Enter' && isModifier && !sendOnEnter)
    if (shouldSend) {
      e.preventDefault()
      handleSend()
      return
    }
    if (e.key === 'Enter' && e.shiftKey) {
      return
    }
    if (e.key === 'Enter' && !e.shiftKey && !isModifier) {
      e.preventDefault()
    }
  }

  const selectSendMode = (mode: 'enter' | 'cmd') => {
    const next = mode === 'enter'
    setSendOnEnter(next)
    localStorage.setItem('sendOnEnter', String(next))
    setShowSendModeDropdown(false)
  }

  const canSend = (inputRef.current?.innerText.trim() || inputTags.length > 0) && currentAgentId && !isLoading && runtimeReady
  const visibleModels = models.filter((model) => {
    const label = `${model.name} ${model.modelName}`
    return !/shell\s*mind\s*agent/i.test(label)
  })
  return (
    <div className="relative flex flex-col h-full flex-shrink-0 overflow-hidden" style={{ width, backgroundColor: colors.bgPrimary }}>
      <PermissionConfirmModal />
      <StreamStatusBar />
      {(() => {
        const conn = activeBinding
          ? connections.find((c) => c.id === activeBinding.connectionId)
          : connections.find((c) => c.id === currentConnectionId)
        if (!conn) return null
        const connected = conn.status === 1
        return (
          <div className="flex items-center gap-2 px-4 py-1.5 border-b" style={{ backgroundColor: connected ? `${colors.accent}08` : `${colors.textDim}06`, borderColor: colors.border }}>
            <div className="w-1.5 h-1.5 rounded-full flex-shrink-0" style={{ backgroundColor: connected ? '#22c55e' : colors.textDim }} />
            <span className="text-[11px] truncate" style={{ color: colors.textDim }}>
              {conn.name}({conn.username}@{conn.host}){connected ? '' : ' · disconnected'}
            </span>
          </div>
        )
      })()}

      <div
        ref={messagesContainerRef}
        className="flex-1 overflow-y-auto min-h-0"
        onScroll={() => {
          const container = messagesContainerRef.current
          if (!container) return
          const { scrollTop, scrollHeight, clientHeight } = container
          const isAtBottom = scrollHeight - scrollTop - clientHeight < 50
          // to up scroll(not bottom) in in manual mode, scroll to bottom exit manual mode
          setIsManualScroll(!isAtBottom)
        }}
      >
        {!currentSession ? (
          <EmptyState onQuickAction={(text) => {
            if (inputRef.current) {
              inputRef.current.innerText = text
              inputHtmlRef.current = inputRef.current.innerHTML
              setInputText(text)
              inputRef.current.focus()
            }
          }} />
        ) : currentSession.messages.length === 0 ? (
          <EmptyState onQuickAction={(text) => {
            if (inputRef.current) {
              inputRef.current.innerText = text
              inputHtmlRef.current = inputRef.current.innerHTML
              setInputText(text)
              inputRef.current.focus()
            }
          }} />
        ) : (
          <div className="py-3 overflow-hidden min-w-0">
            <div className="flex-1">
            {/* message group render: same groupId assistant message aggregate merge as a AI round block */}
            {(() => {
              type AiTurnItem = {
                type: 'aiTurn'
                groupId: string
                msgs: AgentMessage[]
                startIdx: number
                timestamp: number
                showDivider: boolean
                dividerTitle?: string
              }
              type SingleItem = {
                type: 'single'
                msg: AgentMessage
                msgIdx: number
                showDivider: boolean
                dividerTitle?: string
              }
              type RenderItem = AiTurnItem | SingleItem

              const items: RenderItem[] = []
              let i = 0
              while (i < currentSession.messages.length) {
                const msg = currentSession.messages[i]
                const showDivider = i > 0 && (() => {
                  const prev = currentSession.messages[i - 1]
                  if (msg.groupId && prev.groupId && msg.groupId !== prev.groupId) return true
                  return shouldInsertTopicDivider(prev, msg).shouldInsert
                })()
                const dividerTitle = i > 0 ? (() => {
                  const prev = currentSession.messages[i - 1]
                  if (msg.groupId && prev.groupId && msg.groupId !== prev.groupId) return 'New chat'
                  return shouldInsertTopicDivider(prev, msg).title
                })() : undefined

                // user message → single
                if (msg.role === 'user') {
                  items.push({ type: 'single', msg, msgIdx: i, showDivider, dividerTitle })
                  i++
                  continue
                }

                // assistant message has groupId → aggregate same groupId all assistant message as a AI round
                if (msg.groupId && msg.role === 'assistant') {
                  const groupMsgs: AgentMessage[] = [msg]
                  let j = i + 1
                  while (j < currentSession.messages.length && currentSession.messages[j].groupId === msg.groupId && currentSession.messages[j].role === 'assistant') {
                    groupMsgs.push(currentSession.messages[j])
                    j++
                  }
                  items.push({ type: 'aiTurn', groupId: msg.groupId, msgs: groupMsgs, startIdx: i, timestamp: msg.timestamp, showDivider, dividerTitle })
                  i = j
                  continue
                }

                // fallback: no groupId assistant message → single
                items.push({ type: 'single', msg, msgIdx: i, showDivider, dividerTitle })
                i++
              }

              const { colors } = useThemeStore.getState()
              // only most back a AI round block show loading status, avoid old chat also show" align at split analyze"
              let lastAiTurnIdx = -1
              for (let k = items.length - 1; k >= 0; k--) {
                if (items[k].type === 'aiTurn') { lastAiTurnIdx = k; break }
              }

              return items.map((item, idx) => {
                const dividerEl = item.showDivider ? (
                  <TopicDivider
                    prevTimestamp={currentSession.messages[item.type === 'single' ? item.msgIdx : item.startIdx - 1]?.timestamp || 0}
                    currTimestamp={item.type === 'single' ? item.msg.timestamp : item.timestamp}
                    topicIndex={item.type === 'single' ? item.msgIdx : item.startIdx}
                    defaultTitle={item.dividerTitle}
                  />
                ) : null

                // user message or fallback form bar message
                if (item.type === 'single') {
                  return (
                    <React.Fragment key={`single_${item.msg.id}_${idx}`}>
                      {dividerEl}
                      <MessageBubble message={item.msg} isLoading={false} onEditRetry={(msgId) => {
                        if (currentSessionId) {
                          // first get out raw message content, then truncate; put the content back in the input for editing
                          const target = useAgentStore.getState().sessions.get(currentSessionId)?.messages.find(m => m.id === msgId)
                          editAndRetry(currentSessionId, msgId)
                          if (inputRef.current && target) {
                            inputRef.current.innerText = target.content || ''
                            inputHtmlRef.current = inputRef.current.innerHTML
                            setInputText(target.content || '')
                          }
                          setTimeout(() => inputRef.current?.focus(), 50)
                        }
                      }} onAddContextTag={handleAddSentContextTag} />
                    </React.Fragment>
                  )
                }

                // AI round block
                const isLastAiTurn = idx === lastAiTurnIdx
                return (
                  <React.Fragment key={`aiturn_${item.groupId}_${idx}`}>
                    {dividerEl}
                    <AiTurnBlock msgs={item.msgs} colors={colors} isLoading={isLastAiTurn && isLoading} streamStatus={streamStatus} onRetry={() => {
                      setErrorRecovery(null)
                      if (currentSession && currentSession.messages.length >= 2) {
                        const lastUserMsg = [...currentSession.messages].reverse().find(m => m.role === 'user')
                        if (lastUserMsg) {
                          const inputEl = inputRef.current
                          if (inputEl) {
                            inputEl.innerText = lastUserMsg.content || ''
                          }
                          setTimeout(() => handleSend(), 100)
                        }
                      } else if (inputRef.current) {
                        setTimeout(() => handleSend(), 100)
                      }
                    }} />
                  </React.Fragment>
                )
              })
            })()}
            </div>
            {/* error restore card */}
            {errorRecovery && (
              <div className="px-4 py-2">
                <ErrorRecoveryCard
                  error={errorRecovery}
                  canRetry={true}
                  onRetry={() => {
                    setErrorRecovery(null)
                    if (currentSession && currentSession.messages.length >= 2) {
                      const lastUserMsg = [...currentSession.messages].reverse().find(m => m.role === 'user')
                      if (lastUserMsg) {
                        const inputEl = inputRef.current
                        if (inputEl) {
                          inputEl.innerText = lastUserMsg.content || ''
                        }
                        setTimeout(() => handleSend(), 100)
                      }
                    } else if (inputRef.current) {
                        setTimeout(() => handleSend(), 100)
                    }
                  }}
                  onSkip={() => setErrorRecovery(null)}
                  onResetContext={() => {
                    setErrorRecovery(null)
                    if (currentSessionId) {
                      useAgentStore.getState().clearMessages(currentSessionId)
                    }
                  }}
                />
              </div>
            )}
            <div ref={messagesEndRef} />
          </div>
        )}
      </div>

      <div className="w-full h-3 cursor-ns-resize select-none flex items-center justify-center transition-colors hover:bg-blue-500/20 flex-shrink-0" title="Drag to resize the input" onMouseDown={(e) => {
        e.preventDefault()
        e.stopPropagation()
        const startY = e.clientY
        const startHeight = inputRef.current?.offsetHeight || 120
        const onMouseMove = (moveEvent: MouseEvent) => {
          const deltaY = startY - moveEvent.clientY
          const newHeight = Math.max(80, Math.min(280, startHeight + deltaY))
          if (inputRef.current) {
            inputRef.current.style.height = newHeight + 'px'
          }
        }
        const onMouseUp = () => {
          document.removeEventListener('mousemove', onMouseMove)
          document.removeEventListener('mouseup', onMouseUp)
        }
        document.addEventListener('mousemove', onMouseMove)
        document.addEventListener('mouseup', onMouseUp)
      }}>
        <div className="flex gap-1 opacity-30">
          <div className="w-1 h-1 rounded-full bg-gray-400" />
          <div className="w-1 h-1 rounded-full bg-gray-400" />
        </div>
      </div>

      {isLoading && currentSession && (
        <div className="flex justify-center px-4 pb-1 pt-1 flex-shrink-0">
          <AgentActionCapsule messages={currentSession.messages} streamStatus={streamStatus} />
        </div>
      )}

      {/* produce object collect total panel - see Android side design, drop at input box up square */}
      <ArtifactSummaryPanel />

      <div className="mx-3 mb-3 rounded-2xl border overflow-visible flex-shrink-0" style={{ backgroundColor: colors.bgSecondary, borderColor: `${colors.border}80` }}>
        <div className="flex items-center justify-between px-4 py-2">
          <div className="flex items-center gap-2">
          <button
            onClick={() => onToggleExpanded?.()}
            type="button"
            aria-pressed={expanded}
            className="flex items-center px-2 py-1.5 rounded-lg text-[11px] font-medium transition-all"
            style={{ backgroundColor: expanded ? `${colors.accent}20` : colors.bgTertiary, color: expanded ? colors.accent : colors.textSecondary, border: `1px solid ${expanded ? `${colors.accent}40` : 'transparent'}` }}
            title={expanded ? 'Collapse input' : 'Expand input'}
          >
            <svg className="w-4 h-4" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
              {expanded ? (
                <>
                  <polyline points="4 14 10 14 10 20" />
                  <polyline points="20 10 14 10 14 4" />
                  <line x1="3" y1="21" x2="10" y2="14" />
                  <line x1="14" y1="10" x2="21" y2="3" />
                </>
              ) : (
                <>
                  <polyline points="15 3 21 3 21 9" />
                  <polyline points="9 21 3 21 3 15" />
                  <line x1="21" y1="3" x2="14" y2="10" />
                  <line x1="3" y1="21" x2="10" y2="14" />
                </>
              )}
            </svg>
          </button>
          </div>

        <div className="flex items-center gap-2">
          <button onClick={() => setShowChatExport(true)} className="flex items-center gap-1 px-2 py-1.5 rounded-lg text-[11px] font-medium transition-all hover:opacity-80" style={{ backgroundColor: colors.bgTertiary, color: colors.textSecondary, border: '1px solid transparent' }} title="Export chat">
            <svg className="w-3.5 h-3.5" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
              <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4" />
              <polyline points="7 10 12 15 17 10" />
              <line x1="12" y1="15" x2="12" y2="3" />
            </svg>
          </button>
          <button onClick={() => setShowShortcutHelp(true)} className="flex items-center gap-1 px-2 py-1.5 rounded-lg text-[11px] font-medium transition-all hover:opacity-80" style={{ backgroundColor: colors.bgTertiary, color: colors.textSecondary, border: '1px solid transparent' }} title="Shortcuts">
            <svg className="w-3.5 h-3.5" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
              <rect x="2" y="6" width="20" height="12" rx="2" />
              <path d="M6 10h.01M10 10h.01M14 10h.01M18 10h.01M6 14h.01M18 14h.01M8 14h8" />
            </svg>
          </button>
          <button onClick={toggleHistoryPanel} className="flex items-center gap-1 px-2 py-1.5 rounded-lg text-[11px] font-medium transition-all hover:opacity-80" style={{ backgroundColor: showHistoryPanel ? `${colors.accent}20` : colors.bgTertiary, color: showHistoryPanel ? colors.accent : colors.textSecondary, border: `1px solid ${showHistoryPanel ? `${colors.accent}40` : 'transparent'}` }} title="History">
            <svg className="w-3.5 h-3.5" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
              <circle cx="12" cy="12" r="10"></circle>
              <polyline points="12 6 12 12 16 14"></polyline>
            </svg>
          </button>
        </div>
      </div>

        <div className="flex flex-col relative px-4 pt-2 pb-3">
        <div
          className="relative w-full rounded-[10px] border transition-all flex flex-col"
          style={{
            backgroundColor: colors.bgInput,
            borderColor: isFocused ? `${colors.accent}70` : `${colors.border}80`,
            boxShadow: isFocused
              ? `0 0 0 1px ${colors.accent}20, 0 8px 22px rgba(0, 0, 0, 0.09)`
              : '0 6px 18px rgba(0, 0, 0, 0.06), 0 1px 2px rgba(0, 0, 0, 0.04)',
          }}
          onDragEnter={(event) => {
            event.preventDefault()
            event.stopPropagation()
            setIsDragOverInput(true)
          }}
          onDragOver={(event) => {
            event.preventDefault()
            event.stopPropagation()
            setIsDragOverInput(true)
          }}
          onDragLeave={(event) => {
            event.preventDefault()
            event.stopPropagation()
            if (!event.currentTarget.contains(event.relatedTarget as Node)) {
              setIsDragOverInput(false)
            }
          }}
          onDrop={handleNativeDrop}
        >
          {isDragOverInput && (
            <div className="pointer-events-none absolute inset-0 z-20 flex items-center justify-center rounded-[10px] border-2 border-dashed"
                 style={{ backgroundColor: `${colors.accent}08`, borderColor: `${colors.accent}70` }}>
              <span className="text-[11px] font-medium" style={{ color: colors.accent }}>
                Drop files or folders
              </span>
            </div>
          )}
          {inputTags.length > 0 && (
            <div className="flex flex-wrap gap-2 px-3 pt-3 pb-1 max-h-[100px] overflow-y-auto">
              {inputTags.map((tag) => (
                <ContextTagChip
                  key={tag.id}
                  label={tag.label}
                  kind={tag.type === 'terminal-selection' ? 'terminal' : tag.type}
                  fullContent={tag.fullContent}
                  colors={colors}
                  variant="input"
                  onRemove={() => removeInputTag(tag.id)}
                />
              ))}
            </div>
          )}

          <div
            key={inputKey}
            ref={inputRef}
            contentEditable={!isLoading}
            suppressContentEditableWarning
            onInput={(e) => {
              setInputText(e.currentTarget.innerText.replace(/\u00a0/g, ' '))
              inputHtmlRef.current = e.currentTarget.innerHTML
              // CommandMenu detect
              const cursorPos = window.getSelection()?.anchorOffset || 0
              const text = e.currentTarget.innerText.replace(/\u00a0/g, ' ')
              const cmdMenu = useCommandMenu(text, Math.min(cursorPos, text.length), mentionItems)
              if (cmdMenu.trigger) {
                setCmdMenuTrigger(cmdMenu.trigger)
                setCmdMenuIndex(cmdMenu.triggerIndex)
                setCmdMenuQuery(cmdMenu.query)
              } else {
                setCmdMenuTrigger(null)
              }
            }}
            onCompositionStart={handleCompositionStart}
            onCompositionEnd={handleCompositionEnd}
            onKeyDown={handleKeyDown}
            onFocus={() => setIsFocused(true)}
            onBlur={() => setIsFocused(false)}
            onMouseUp={() => {
              const selection = window.getSelection()
              if (selection && selection.rangeCount > 0 && inputRef.current?.contains(selection.anchorNode)) {
                lastRangeRef.current = selection.getRangeAt(0).cloneRange()
              }
            }}
            onPaste={(e) => {
              e.preventDefault()
              // prefer image data from the clipboard
              const imageItems = Array.from(e.clipboardData.items).filter(
                (item) => item.type.startsWith('image/')
              )
              if (imageItems.length > 0) {
                const imageFile = imageItems[0].getAsFile()
                if (imageFile) {
                  const reader = new FileReader()
                  reader.onload = (ev) => {
                    const dataUrl = ev.target?.result as string
                    if (!dataUrl) return
                    insertTagAtCursor({
                      id: `img_${Date.now()}`,
                      label: `Image: ${imageFile.name || 'Pasted image'}`,
                      fullContent: `[Image: ${imageFile.name || 'Pasted image'}]\n${dataUrl}`,
                      type: 'custom',
                    })
                  }
                  reader.readAsDataURL(imageFile)
                  return
                }
              }
              // no image when, go pure text paste stream process
              const rawText = e.clipboardData.getData('text/plain')
              const cleanText = stripMarkdownForPaste(rawText)
              document.execCommand('insertText', false, cleanText)
              syncInputTextFromDom()
            }}
            className="w-full bg-transparent resize-none outline-none text-[13px] leading-relaxed flex-1 whitespace-pre-wrap break-words min-h-[120px] max-h-[280px] overflow-y-auto"
            style={{
              color: isLoading ? colors.textDim : colors.text,
              padding: inputTags.length > 0 ? '4px 16px 44px 16px' : '8px 16px 44px 16px',
            }}
          />

          {(!inputText || inputText.trim() === '') && inputTags.length === 0 && (
            <div className="absolute pointer-events-none text-sm" style={{ left: '16px', top: '8px', color: colors.textDim, opacity: 0.6 }}>
              {inputPlaceholder()}
            </div>
          )}

          <div className="absolute right-3 bottom-3 flex items-center gap-1.5">
            <div className="relative">
              <button onClick={(e) => { e.stopPropagation(); setShowAttachmentMenu(!showAttachmentMenu) }} className="p-1.5 rounded-md transition-colors hover:bg-black/10" style={{ backgroundColor: colors.bgTertiary, color: colors.textSecondary }} title="Add context">
                <svg className="w-4 h-4" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                  <path d="M21.44 11.05l-9.19 9.19a6 6 0 01-8.49-8.49l9.19-9.19a4 4 0 015.66 5.66l-9.2 9.19a2 2 0 01-2.83-2.83l8.49-8.48" />
                </svg>
              </button>
              {showAttachmentMenu && (
                <div className="absolute bottom-full right-0 mb-1 w-32 rounded-lg border shadow-lg py-1 z-50" style={{ backgroundColor: colors.bgPrimary, borderColor: colors.border }}>
                  <button onClick={handleAddCurrentFile} disabled={!activeTabKey} className="w-full text-left px-3 py-1.5 text-[11px] hover:bg-white/5 transition-colors disabled:opacity-50 disabled:cursor-not-allowed" style={{ color: colors.text }}>
                    Add current file
                  </button>
                  <button onClick={handleAddCurrentFolder} disabled={!activeConnectionId} className="w-full text-left px-3 py-1.5 text-[11px] hover:bg-white/5 transition-colors disabled:opacity-50 disabled:cursor-not-allowed" style={{ color: colors.text }}>
                    Add current folder
                  </button>
                  <button onClick={handleAddSelectedText} className="w-full text-left px-3 py-1.5 text-[11px] hover:bg-white/5 transition-colors" style={{ color: colors.text }}>
                    Add selected text
                  </button>
                </div>
              )}
            </div>
            <label className="p-1.5 rounded-md transition-colors hover:bg-black/10 cursor-pointer" style={{ backgroundColor: colors.bgTertiary, color: colors.textSecondary }} title="Upload image">
              <svg className="w-4 h-4" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                <rect x="3" y="3" width="18" height="18" rx="2" ry="2"></rect>
                <circle cx="8.5" cy="8.5" r="1.5"></circle>
                <polyline points="21 15 16 10 5 21"></polyline>
              </svg>
              <input
                type="file"
                accept="image/*"
                className="hidden"
                onChange={(e) => {
                  const file = e.target.files?.[0]
                  if (!file) return
                  const reader = new FileReader()
                  reader.onload = (ev) => {
                    const dataUrl = ev.target?.result as string
                    if (!dataUrl) return
                    // insert the image as a context chip in the input
                    insertTagAtCursor({
                      id: `img_${Date.now()}`,
                      label: `Image: ${file.name}`,
                      fullContent: `[Image: ${file.name}]\n${dataUrl}`,
                      type: 'custom',
                    })
                  }
                  reader.readAsDataURL(file)
                  // reset input so allow allow duplicate select same file
                  e.target.value = ''
                }}
              />
            </label>
            {isLoading ? (
              <button onClick={handleStop} className="p-1.5 rounded-md transition-colors" style={{ backgroundColor: colors.red, color: '#fff' }} title="Stop">
                <svg className="w-4 h-4" viewBox="0 0 24 24" fill="currentColor">
                  <rect x="6" y="6" width="12" height="12" rx="2" />
                </svg>
              </button>
            ) : (
              <button onClick={handleSend} disabled={!canSend} className="p-1.5 rounded-md transition-colors" style={{ backgroundColor: canSend ? colors.accent : colors.bgTertiary, color: canSend ? '#fff' : colors.textSecondary, opacity: canSend ? 1 : 0.5, cursor: canSend ? 'pointer' : 'not-allowed' }} title="Send">
                <svg className="w-4 h-4" viewBox="0 0 24 24" fill="currentColor">
                  <path d="M22 2L11 13M22 2l-7 20-4-9-9-4 20-7z" />
                </svg>
              </button>
            )}
          </div>

          {runningSessions.length > 1 && (
            <button
              onClick={() => {
                const activeIndex = runningSessions.findIndex(session => session.id === currentSessionId)
                const nextSession = runningSessions[(activeIndex + 1) % runningSessions.length]
                if (nextSession) setCurrentSession(nextSession.id)
              }}
              className="mt-2 flex items-center gap-2 rounded-full px-2.5 py-1 text-[11px] font-medium transition-all"
              style={{
                backgroundColor: `${colors.green}14`,
                color: colors.green,
                border: `1px solid ${colors.green}66`,
                boxShadow: `0 0 10px ${colors.green}66, 0 0 26px ${colors.green}22`,
              }}
              title={`Running:${runningSessions.map(session => session.name || 'New chat').join(',')}`}
              aria-label="Switch to the next running chat"
            >
              <span className="h-1.5 w-1.5 animate-pulse rounded-full" style={{ backgroundColor: colors.green }} />
              <span>{runningSessions.length} chats running</span>
              <span className="opacity-70">Click to switch</span>
            </button>
          )}

          <div className="absolute left-3 bottom-3 right-28 flex items-center gap-1.5 z-30 min-w-0">
            <button
              onClick={(event) => { event.stopPropagation(); if (currentAgentId) void newConversation(currentAgentId, activeConversationProjectId) }}
              className="p-1.5 shrink-0 rounded-md transition-colors hover:bg-black/10"
              style={{ backgroundColor: colors.bgTertiary, color: colors.textSecondary }}
              title="New chat in this project"
            >
              <svg className="w-4 h-4" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2"><path d="M12 5v14M5 12h14" /></svg>
            </button>
            <div className="relative min-w-[54px] shrink-[2]" onPointerDown={event => event.stopPropagation()}>
              <button
                onClick={() => {
                  setShowProjectSelector(value => !value)
                  setShowBranchSelector(false)
                }}
                className="h-7 max-w-[168px] min-w-0 px-2 rounded-md text-[11px] font-medium transition-colors hover:bg-black/5 flex items-center gap-1.5"
                style={{ backgroundColor: colors.bgTertiary, color: colors.textSecondary }}
                title="Switch chat project"
              >
                <svg className="w-3.5 h-3.5 shrink-0" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2"><path d="M3 7a2 2 0 0 1 2-2h4l2 2h8a2 2 0 0 1 2 2v9a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z" /></svg>
                <span className="truncate">
                  {conversationProjects.find(item => item.id === activeConversationProjectId)?.name || 'Default project'}
                </span>
                <svg className="w-3 h-3 shrink-0" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5"><polyline points="6 9 12 15 18 9" /></svg>
              </button>
              {showProjectSelector && (
                <div
                  className="absolute bottom-full left-0 mb-1.5 w-60 max-h-56 overflow-y-auto rounded-lg border shadow-xl py-1"
                  style={{ backgroundColor: colors.bgPrimary, borderColor: colors.border }}
                >
                  {conversationProjects.map(project => {
                    const linkedProject = localProjects.find(item => item.id === project.localProjectId)
                    const branch = linkedProject?.branch
                    const active = project.id === activeConversationProjectId
                    return (
                      <button
                        key={project.id}
                        onClick={() => { setShowProjectSelector(false); void switchConversationProject(project.id) }}
                        className="w-full px-2.5 py-1.5 text-left text-[11px] transition-colors hover:bg-black/5"
                        style={{ color: active ? colors.accent : colors.text, backgroundColor: active ? `${colors.accent}10` : 'transparent' }}
                      >
                        <span className="flex items-center gap-1.5">
                          <span className="truncate font-medium">{project.name}</span>
                          {linkedProject && (
                            <span className="rounded-full px-1.5 py-0.5 text-[9px] leading-none" style={{ backgroundColor: `${colors.accent}14`, color: colors.accent }}>
                              Project
                            </span>
                          )}
                        </span>
                        <span className="block truncate text-[10px]" style={{ color: colors.textDim }}>
                          {linkedProject
                            ? `${branch ? `Branch · ${branch}` : 'No branch'}`
                            : 'No linked project'}
                        </span>
                      </button>
                    )
                  })}
                </div>
              )}
            </div>
            {linkedProject && (
              <div className="relative min-w-[54px]" onPointerDown={event => event.stopPropagation()}>
                <button
                  onClick={() => void toggleBranchSelector()}
                  disabled={branchLoading || branchSwitching}
                  className="h-7 max-w-[168px] min-w-0 px-2 rounded-md text-[11px] font-medium transition-colors hover:bg-black/5 flex items-center gap-1.5 disabled:opacity-60"
                  style={{ backgroundColor: colors.bgTertiary, color: colors.textSecondary }}
                  title="Switch project branch"
                >
                  <svg className="w-3.5 h-3.5 shrink-0" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2"><circle cx="6" cy="6" r="2.5" /><circle cx="6" cy="18" r="2.5" /><circle cx="18" cy="8" r="2.5" /><path d="M6 8.5v7M6 12c0-2.5 2.5-4 5-4h3a4 4 0 0 1 4 0" /></svg>
                  <span className="truncate">{linkedProject.branch || 'Branch'}</span>
                  <svg className="w-3 h-3 shrink-0" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5"><polyline points="6 9 12 15 18 9" /></svg>
                </button>
                {showBranchSelector && (
                  <div
                    className="absolute bottom-full left-0 mb-1.5 w-56 max-h-56 overflow-y-auto rounded-lg border shadow-xl py-1"
                    style={{ backgroundColor: colors.bgPrimary, borderColor: colors.border }}
                  >
                    {branchLoading && (
                      <div className="px-2.5 py-2 text-[11px]" style={{ color: colors.textDim }}>Loading branches...</div>
                    )}
                    {!branchLoading && projectBranches.length === 0 && (
                      <div className="px-2.5 py-2 text-[11px]" style={{ color: colors.textDim }}>
                        {branchError || 'No branch detected'}
                      </div>
                    )}
                    {!branchLoading && projectBranches.map(branch => {
                      const active = branch === linkedProject.branch
                      return (
                        <button
                          key={branch}
                          onClick={() => void switchProjectBranch(branch)}
                          disabled={branchSwitching}
                          className="w-full px-2.5 py-1.5 text-left text-[11px] transition-colors hover:bg-black/5 disabled:opacity-60"
                          style={{ color: active ? colors.accent : colors.text, backgroundColor: active ? `${colors.accent}10` : 'transparent' }}
                        >
                          <span className="block truncate">{branch}</span>
                        </button>
                      )
                    })}
                  </div>
                )}
              </div>
            )}
          </div>
        </div>

        {/* CommandMenu popup */}
        {cmdMenuTrigger && (
          <CommandMenu
            trigger={cmdMenuTrigger}
            query={cmdMenuQuery}
            mentions={mentionItems}
            onSelect={(item) => {
              if (cmdMenuTrigger === '/') {
                // command select: replace input contents or run an action
                if (item.insertText) {
                  if (inputRef.current) {
                    const text = inputRef.current.innerText
                    const before = text.slice(0, cmdMenuIndex)
                    const after = text.slice(cmdMenuIndex + cmdMenuQuery.length + 1)
                    inputRef.current.focus({ preventScroll: true })
                    inputRef.current.innerText = before + item.insertText + after
                    setInputText(inputRef.current.innerText)
                    // caret move to end end
                    const range = document.createRange()
                    range.selectNodeContents(inputRef.current)
                    range.collapse(false)
                    const sel = window.getSelection()
                    sel?.removeAllRanges()
                    sel?.addRange(range)
                  }
                } else if (item.id === 'connect') {
                  // open SSH connection config popup window, switch to server tab
                  window.dispatchEvent(new CustomEvent('open-ssh-modal'))
                } else if (item.id === 'disconnect') {
                  // disconnect current active connection
                  const conn = activeBinding
                    ? connections.find(c => c.id === activeBinding.connectionId)
                    : connections.find(c => c.id === currentConnectionId)
                  if (conn) {
                    useConnectionStore.getState().disconnect(conn.id)
                  }
                } else if (item.id === 'clear') {
                  if (currentSessionId) useAgentStore.getState().clearMessages(currentSessionId)
                } else if (item.id === 'reset') {
                  // TODO: reset context API wait backend extract for
                } else if (item.id === 'export') {
                  // lead out chat
                  if (currentSession) {
                    const md = currentSession.messages.map(m => `### ${m.role === 'user' ? '🧑 User' : '🤖 Assistant'}\n\n${m.content}`).join('\n---\n')
                    const blob = new Blob([md], { type: 'text/markdown' })
                    const url = URL.createObjectURL(blob)
                    const a = document.createElement('a')
                    a.href = url
                    a.download = `chat_${new Date().toISOString().slice(0, 10)}.md`
                    a.click()
                    URL.revokeObjectURL(url)
                  }
                } else if (item.id === 'debug') {
                  // debug mode: send hint let AI show detail thin execute process
                  if (inputRef.current) {
                    inputRef.current.innerText = 'Turn on debug mode and show the ReAct trace'
                    setInputText(inputRef.current.innerText)
                  }
                } else if (item.id === 'help') {
                  // help: send hint let AI column out ok use command
                  if (inputRef.current) {
                    inputRef.current.innerText = 'List available commands and shortcuts'
                    setInputText(inputRef.current.innerText)
                  }
                }
              } else {
                // @ mention select: all create tab(do not send, insert input box)
                if (item.id.startsWith('server-')) {
                  // select connected SSH server → connection tab
                  const connId = item.id.replace('server-', '')
                  const conn = connections.find(c => c.id === connId)
                  if (conn) {
                    addInputTag({
                      label: conn.name,
                      fullContent: `Current server: ${conn.name} (${conn.username}@${conn.host}:${conn.port})`,
                      type: 'connection',
                      connectionInfo: {
                        connectionId: conn.id,
                        connectionName: conn.name,
                        host: conn.host,
                        port: conn.port,
                        username: conn.username,
                      },
                    })
                  }
                } else if (item.id.startsWith('project-')) {
                  const projectId = item.id.replace('project-', '')
                  const project = localProjects.find(p => p.id === projectId)
                  if (project) {
                    addInputTag({
                      label: `Project: ${project.name}`,
                      fullContent: `Project: ${project.name}\nPath: ${project.path}\nBranch: ${project.branch || 'unknown'}`,
                      type: 'project',
                    })
                  }
                } else if (item.id.startsWith('conv-project-')) {
                  // chat item goal(not yet off link local project)→ project tab
                  const convId = item.id.replace('conv-project-', '')
                  const conv = conversationProjects.find(cp => cp.id === convId)
                  if (conv) {
                    addInputTag({
                      label: `Project: ${conv.name}`,
                      fullContent: `Project: ${conv.name}\nPath: ${conv.path || 'unspecified'}`,
                      type: 'project',
                    })
                  }
                } else if (item.id === 'current-file') {
                  // current file → file tab
                  addInputTag({
                    label: 'Current file',
                    fullContent: 'Current open file (path pending)',
                    type: 'file',
                  })
                } else if (item.id === 'current-folder') {
                  const localRoot = useLocalFileStore.getState().rootPath
                  const remoteCwd = activeConnectionId ? currentPathByConnection[activeConnectionId] : undefined
                  const cwd = localRoot || remoteCwd
                  addInputTag({
                    label: cwd ? `Folder: ${cwd.split('/').filter(Boolean).pop() || cwd}` : 'Current folder',
                    fullContent: cwd ? `Working folder: ${cwd}` : 'Working directory',
                    type: 'directory',
                  })
                } else if (item.id === 'terminal') {
                  // terminal selected text → terminal-selection tab
                  addInputTag({
                    label: 'Terminal',
                    fullContent: 'Terminal selection (content pending)',
                    type: 'terminal-selection',
                  })
                }
                // all @ mention: remove input box center @ trigger text
                if (inputRef.current) {
                  const text = inputRef.current.innerText
                  const before = text.slice(0, cmdMenuIndex)
                  const after = text.slice(cmdMenuIndex + cmdMenuQuery.length + 1)
                  inputRef.current.innerText = before + after
                  setInputText(inputRef.current.innerText)
                  setInputKey((k) => k + 1)
                }
              }
              setCmdMenuTrigger(null)
            }}
            onClose={() => setCmdMenuTrigger(null)}
          />
        )}

        <div className="flex items-center mt-2 text-[11px]" style={{ color: colors.textDim }}>
          <div className="relative flex items-center min-w-0">
            <select
              value={selectedModelId ?? ''}
              onChange={(event) => selectModel(event.target.value ? Number(event.target.value) : null)}
              className="model-select max-w-[190px] min-w-0 h-7 pl-2.5 pr-7 rounded-lg text-[11px] font-medium outline-none cursor-pointer"
              style={{
                backgroundColor: colors.bgTertiary,
                border: `1px solid ${colors.border}99`,
                color: colors.textSecondary,
              }}
              title="Model for this chat"
            >
              {visibleModels.length === 0 && <option value="">Default model</option>}
              {visibleModels.map((model) => (
                <option key={model.id} value={model.id}>
                  {model.modelName}
                </option>
              ))}
            </select>
            <svg className="absolute right-2 w-3 h-3 pointer-events-none" viewBox="0 0 24 24" fill="none" stroke={colors.textDim} strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round">
              <polyline points="6 9 12 15 18 9" />
            </svg>
          </div>
          <div className="flex-1" />
          <div className="relative">
            <button onClick={() => setShowSendModeDropdown(!showSendModeDropdown)} className="flex items-center gap-1 px-2 py-1 rounded-md cursor-pointer transition-colors hover:bg-black/10 shrink-0 whitespace-nowrap" style={{ backgroundColor: 'transparent', color: colors.textDim }} title="Choose send shortcut">
              <svg className="w-3 h-3" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                <polyline points="6 9 12 15 18 9"></polyline>
              </svg>
              {sendOnEnter ? (
                <span style={{ fontSize: '11px', fontFamily: 'monospace' }}>Enter to send</span>
              ) : (
                <span style={{ fontSize: '11px', fontFamily: 'monospace' }}>{currentKeyLabel} to send</span>
              )}
            </button>
            {showSendModeDropdown && (
              <div className="absolute bottom-full right-0 mb-1 rounded-lg border shadow-lg py-1 min-w-[140px]" style={{ backgroundColor: colors.bgPrimary, borderColor: colors.border }}>
                <button onClick={() => selectSendMode('enter')} className="w-full flex items-center gap-2 px-3 py-1.5 text-left transition-colors" style={{ fontSize: '11px', color: sendOnEnter ? colors.accent : colors.textSecondary }}>
                  {sendOnEnter && (
                    <svg className="w-3 h-3" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                      <polyline points="20 6 9 17 4 12"></polyline>
                    </svg>
                  )}
                  <span>Enter to send</span>
                </button>
                <button onClick={() => selectSendMode('cmd')} className="w-full flex items-center gap-2 px-3 py-1.5 text-left transition-colors" style={{ fontSize: '11px', color: !sendOnEnter ? colors.accent : colors.textSecondary }}>
                  {!sendOnEnter && (
                    <svg className="w-3 h-3" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                      <polyline points="20 6 9 17 4 12"></polyline>
                    </svg>
                  )}
                  <span>{currentKeyLabel} to send</span>
                </button>
              </div>
            )}
          </div>
        </div>
        </div>
      </div>
      {/* P2: shortcut panel */}
      <ShortcutHelp open={showShortcutHelp} onClose={() => setShowShortcutHelp(false)} />
      {/* P2: lead out panel */}
      <ChatExport
        open={showChatExport}
        onClose={() => setShowChatExport(false)}
        messages={currentSession?.messages || []}
        sessionTitle={currentSession?.name}
      />
    </div>
  )
}
