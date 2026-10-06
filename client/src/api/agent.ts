/**
 * agent API
 */
import { get, post, getAuthHeaders, getBaseUrl } from './request'
import { toolProgressStore } from '../components/ToolProgressBar'
import { chatConfig } from '../config/chat'
import { invoke } from '@tauri-apps/api/core'

export interface AiAgentConfigDTO {
  agentId: string
  agentName: string
  agentDesc: string
}

/** create session request */
export interface CreateSessionRequestDTO {
  agentId: string
  userId: string
}

/** create session response */
export interface CreateSessionResponseDTO {
  sessionId: string
}

/** current project context */
export interface ProjectContextDTO {
  /** project name(folder name), like "ai-mcp-gateway" */
  name: string
  /** project root path(absolute path), like "/Users/xxx/coding/ai-mcp-gateway" */
  rootPath: string
  /** Git branch(if ok recognize other) */
  branch?: string | null
}

/** chat request */
export interface ChatRequestDTO {
  agentId: string
  userId: string
  sessionId: string
  message: string
  terminalSessionId?: string | null

  /** current project context(optional, by frontend local file tree note in) */
  projectContext?: ProjectContextDTO | null
}

/** backend ReAct event(ReActEventDTO) */
export interface ReActEvent {
  event:
    | 'text'
    | 'tool_call'
    | 'tool_result'
    | 'round_end'
    | 'done'
    | 'error'
    | 'warning'
    | 'heartbeat'
    | 'tool_progress'
    | 'task_breakdown'
    | 'task_progress'
    | 'sub_agent_call'
    | 'sub_agent_result'
    | 'permission_confirm'
    | 'tool_output'
    | 'round_start'
    | 'status'
    | 'execute_local_command'
  content?: string
  toolCallId?: string
  toolName?: string
  status?: string
  fullText?: string
  args?: string
  summary?: string
  timestamp?: number
  stepInfo?: {
    currentStep: number
    maxSteps: number
    shouldContinue: boolean
    totalToolCalls: number
  }
  taskBreakdown?: TaskBreakdownDTO
  taskProgress?: {
    subTaskIndex: number
    subTaskTitle: string
    status: string
    totalSubTasks: number
    completedSubTasks: number
  }
  subAgent?: SubAgentInfo
  changeSummary?: ChangeSummary
  // ── add event char segment ──
  /** permission confirm info (event=permission_confirm) */
  permission?: PermissionConfirmData
  /** tool real when output snippet (event=tool_output) */
  outputChunk?: string
  /** status update message (event=status) */
  statusMessage?: string
  /** local point command ID (event=execute_local_command) */
  cmdId?: string
  /** local command (event=execute_local_command) */
  command?: string
  /** working directory (event=execute_local_command) */
  cwd?: string
  /** timeout time ms (event=execute_local_command) */
  timeoutMs?: number
}

/** permission confirm event data */
export interface PermissionConfirmData {
  confirmId: string
  toolName: string
  toolArgs: string
  riskLevel: 'DENY' | 'CONFIRM' | 'ALLOW'
  reason: string
  timeoutMs: number
}

/** proxy call info */
export interface SubAgentInfo {
  agentName: string
  task: string
  status: 'running' | 'success' | 'error'
  result?: string
  durationMs?: number
}

/** file change summary */
export interface ChangeSummary {
  description?: string
  topic?: string
  created: ChangeFile[]
  modified: ChangeFile[]
  deleted: ChangeFile[]
}

/** single file change */
export interface ChangeFile {
  path: string
  kind: 'create' | 'modify' | 'delete'
  addedLines?: number
  removedLines?: number
}

/** task split unbind DTO */
export interface TaskBreakdownDTO {
  originalRequest: string
  subTasks: TaskSubTask[]
  needConfirmation: boolean
  summary: string
}

/** subtask */
export interface TaskSubTask {
  index: number
  title: string
  description: string
  expectedTools: string
  status: 'pending' | 'executing' | 'completed' | 'failed' | 'skipped'
  result?: string
}

/** frontend ReAct step(for UI render) */
export interface ReActStep {
  stepType: 'thinking' | 'tool_call' | 'result'
  stepIndex: number
  content?: string
  toolName?: string
  toolParams?: string
  toolResult?: string
  toolCallId?: string
  status: 'in_progress' | 'success' | 'failure'
  error?: string
  /**
   * pending when final answer mark:
   * - text event streaming push when content as" truncate to current all text",isFinalText=true -- at this point it identity not yet set:
   * if back continue no has tool call, it then is final answer; if appear down a tool call, then demote as process notes.
   * - tool_call(in_progress) event if demotePendingText=true, table show this front align at streaming render provisional answer
   * should demote as process notes(the server confirmed another tool call follows this text).
   */
  isFinalText?: boolean
  demotePendingText?: boolean
}

/** check query agent list */
export async function queryAgentList(): Promise<AiAgentConfigDTO[]> {
  const res = await get<AiAgentConfigDTO[]>('/api/v1/query_ai_agent_config_list')
  if (res.code === '0000' && res.data) {
    return res.data
  }
  console.error('[agentApi] queryAgentList failed:', res.info)
  return []
}

/** create session */
export async function createSession(agentId: string, userId: string = 'default'): Promise<string | null> {
  const res = await post<CreateSessionResponseDTO>('/api/v1/create_session', {
    agentId,
    userId,
  })
  if (res.code === '0000' && res.data?.sessionId) {
    return res.data.sessionId
  }
  console.error('[agentApi] createSession failed:', res.info)
  return null
}

/**
 * ReAct streaming chat(SSE)
 *
 * correct receive backend ReActEventDTO format, event as pure JSON ok(no data: prefix)
 *
 * event type:
 * - text: text stream(content= snippet, fullText= accumulate)
 * - tool_call: tool call start(toolName, toolCallId)
 * - tool_result: tool execute result(toolCallId, content)
 * - round_end: one round end(stepInfo)
 * - done: all done(content= most end result JSON)
 * - error: error
 */
export interface InlineImageData {
  /** base64 encoding data(without data:image/xxx;base64, prefix) */
  data: string
  /** MIME type, like image/png,image/jpeg */
  mimeType: string
}

export function reactChatStream(
  agentId: string,
  userId: string,
  sessionId: string,
  message: string,
  onStep: (step: ReActStep) => void,
  onText: (fullText: string) => void,
  onDone: (finalContent: string) => void,
  onError: (err: string) => void,
  terminalSessionId?: string | null,
  onTaskBreakdown?: (breakdown: TaskBreakdownDTO) => void,
  onTaskProgress?: (progress: { subTaskIndex: number; subTaskTitle: string; status: string; totalSubTasks: number; completedSubTasks: number }) => void,
  onSubAgent?: (info: SubAgentInfo) => void,
  onChangeSummary?: (summary: ChangeSummary) => void,
  projectContext?: ProjectContextDTO | null,
  // ── add callback ──
  onPermissionConfirm?: (data: PermissionConfirmData) => void,
  onToolOutput?: (toolCallId: string, outputChunk: string) => void,
  onStatus?: (message: string) => void,
  onWarning?: (message: string) => void,
  onRoundStart?: (roundIndex: number) => void,
  onReconnect?: (attempt: number, maxAttempts: number) => void,
  onHeartbeat?: () => void,
  // ── extra template state support ──
  inlineDatas?: InlineImageData[],
  modelId?: number | null,
): () => void {
  const baseUrl = getBaseUrl()
  const url = `${baseUrl}/api/v1/chat_stream`

  const controller = new AbortController()
  const cfg = chatConfig

  // tool call → step index map
  const toolStepMap = new Map<string, number>()
  // tool call ID → tool name map(tool_result fill back when toolName)
  const toolNameMap = new Map<string, string>()
  // tool name → latest args map(tool_progress done fill back when args)
  const toolProgressArgsMap = new Map<string, string>()
  let stepCounter = 0
  let lastFullText = ''
  const pendingToolProgress = new Map<string, number>()
  let retryCount = 0
  // stream center path disconnect reconnect params
  let streamReconnectCount = 0
  let isStreamStarted = false // whether already start receive receive stream data
  let isAborted = false // user main action cancel
  let doneCalled = false // prevent onDone duplicate call
  // once request timeout set when widget
  let requestTimer: ReturnType<typeof setTimeout> | null = null

  function clearRequestTimer() {
    if (requestTimer) {
      clearTimeout(requestTimer)
      requestTimer = null
    }
  }

  function doFetch() {
    // request level timeout: exceed cfg.requestTimeout then center stop this nth request merge retry
    clearRequestTimer()
    const perRequestController = new AbortController()
    const combinedSignal = AbortSignal.any([controller.signal, perRequestController.signal])
    requestTimer = setTimeout(() => {
      console.warn(`[SSE] request timeout after ${cfg.requestTimeout}ms`)
      perRequestController.abort()
    }, cfg.requestTimeout)

    fetch(url, {
      method: 'POST',
      headers: getAuthHeaders({ 'Content-Type': 'application/json' }),
      body: JSON.stringify({ agentId, userId, sessionId, message, terminalSessionId, projectContext, inlineDatas, modelId }),
      signal: combinedSignal,
    })
    .then((res) => {
      clearRequestTimer()
      if (!res.ok) {
        // 5xx error when retry
        if (res.status >= 500 && retryCount < cfg.maxRetries) {
          retryCount++
          console.warn(`[SSE] HTTP ${res.status}, retrying ${retryCount}/${cfg.maxRetries}...`)
          onReconnect?.(retryCount, cfg.maxRetries)
          setTimeout(doFetch, cfg.retryBaseDelay * retryCount)
          return
        }
        onError(`HTTP ${res.status}: ${res.statusText}`)
        return
      }

      // Phase 2: SSE reconnect success back, check break line period middle cache result
      if (streamReconnectCount > 0 || retryCount > 0) {
        console.log('[SSE] reconnected; checking results cached during the disconnect...')
        fetch(`${getBaseUrl()}/api/v1/tool_result/pending_all`, { headers: getAuthHeaders() })
          .then(r => r.json())
          .then(data => {
            if (data.code === '0000' && data.data && Object.keys(data.data).length > 0) {
              console.log(`[SSE] found ${Object.keys(data.data).length} cached results from the disconnect window`)
            }
          })
          .catch(err => console.warn('[SSE] failed to check cached results:', err.message))
      }

      const reader = res.body!.getReader()
      if (!reader) {
        onError('No response body')
        return
      }
      const decoder = new TextDecoder()
      let buffer = ''

      function read() {
        reader.read().then(({ done, value }) => {
          if (done) {
            // SSE stream ended normally
            if (!doneCalled) {
              doneCalled = true
              onDone(lastFullText)
            }
            return
          }
          buffer += decoder.decode(value, { stream: true })

          // mark stream already start
          isStreamStarted = true

          // by newline split, parse JSON event(backend directly send JSON ok, no data: prefix)
          const lines = buffer.split('\n')
          buffer = lines.pop() || ''

          for (const line of lines) {
            const trimmed = line.trim()
            if (!trimmed) continue

            // SSE message received

            try {
              const event: ReActEvent = JSON.parse(trimmed)
              // ignore heartbeat keep live event
              if (event.event === 'heartbeat') {
                // SSE heartbeat received
                onHeartbeat?.()
                continue
              }
              isStreamStarted = true
              processEvent(event)
            } catch {
              // non JSON ok, ignore(may be HTTP chunk boundary)
            }
          }
          read()
        }).catch((err) => {
          if (err.name === 'AbortError' || isAborted) {
            // user main action cancel, do not reconnect
            return
          }
          // stream center path disconnect(network error/ server close)
          if (isStreamStarted && streamReconnectCount < cfg.maxStreamReconnects) {
            streamReconnectCount++
            console.warn(`[SSE] stream interrupted, reconnecting ${streamReconnectCount}/${cfg.maxStreamReconnects}...`, err.message)
            onReconnect?.(streamReconnectCount, cfg.maxStreamReconnects)
            setTimeout(() => {
              if (!isAborted) doFetch()
            }, cfg.streamReconnectBaseDelay * streamReconnectCount)
          } else if (lastFullText) {
            // already reach reconnect max, but has accumulate content → cross pay already has content + mark center break
            console.warn('[SSE] reconnect exhausted, delivering partial content')
            // first through know center break(let frontend show restore card), again cross pay partial content
            onError('SSE connection interrupted; some content may be incomplete')
            if (!doneCalled) {
              doneCalled = true
              onDone(lastFullText)
            }
          } else {
            onError(err.message)
          }
        })
      }

      function processEvent(event: ReActEvent) {
        // Phase 2: all event type log(for debug)
        console.debug(`[SSE] event=${event.event}, cmdId=${event.cmdId || '-'}, toolName=${event.toolName || '-'}`)

        switch (event.event) {
          case 'text': {
            // backend each round ReAct will reset all text; tool boundary back other up new process segment
            const fullText = event.fullText || event.content || ''
            if (!fullText.trim()) break
            lastFullText = fullText
            // language meaning fix align(root fix duplicate display): streaming text directly as" pending when final answer" push,
            // identity by back continue event decide set-- appear tool_call when by demote signal number demote as process notes.
            // old logic edit it push done thinking process segment,done when again push complete whole answer case, cause same content display two pass.
            onStep({
              stepType: 'thinking',
              stepIndex: 0,
              content: fullText,
              status: 'in_progress',
              isFinalText: true,
            })
            break
          }

          case 'tool_call': {
            // tool call start → confirm first" this front streaming render center provisional answer is process notes", send demote signal number
            if (lastFullText) {
              onStep({
                stepType: 'thinking',
                stepIndex: 0,
                content: lastFullText,
                status: 'in_progress',
                demotePendingText: true,
              })
            }
            // tool call → new step
            stepCounter++
            const idx = stepCounter
            const toolName = event.toolName || 'unknown'
            const toolArgs = event.args || ''
            if (event.toolCallId) {
              toolStepMap.set(event.toolCallId, idx)
              toolNameMap.set(event.toolCallId, toolName)
            }
            onStep({
              stepType: 'tool_call',
              stepIndex: idx,
              toolName,
              toolParams: toolArgs,
              content: toolArgs ? `Call ${toolName}: ${toolArgs}` : `Call ${toolName}`,
              status: 'in_progress',
            })
            break
          }

          case 'tool_result': {
            // tool result → update already has step
            const toolCallId = event.toolCallId || ''
            const existingIdx = toolStepMap.get(toolCallId)
            // from map fill back toolName(tool_result event this body not carry bar toolName)
            const resolvedToolName = toolNameMap.get(toolCallId) || ''
            if (existingIdx !== undefined) {
              onStep({
                stepType: 'tool_call',
                stepIndex: existingIdx,
                toolName: resolvedToolName,
                toolResult: event.content || '',
                status: event.status === 'error' ? 'failure' : 'success',
                error: event.status === 'error' ? event.content : undefined,
              })
            } else {
              // not yet find to corresponds to tool_call(ADK auto execute scenario), new step
              stepCounter++
              onStep({
                stepType: 'tool_call',
                stepIndex: stepCounter,
                toolName: resolvedToolName,
                toolResult: event.content || '',
                status: event.status === 'error' ? 'failure' : 'success',
                error: event.status === 'error' ? event.content : undefined,
              })
            }
            break
          }

          case 'tool_progress': {
            if (event.status === 'executing') {
              // save args for done event use
              const tn = event.toolName || 'unknown'
              if (event.args) toolProgressArgsMap.set(tn, event.args)
              const progressKey = `${tn}:${event.args || ''}`
              const existingProgressIndex = pendingToolProgress.get(progressKey)
              if (existingProgressIndex !== undefined) {
                break
              }

              stepCounter++
              pendingToolProgress.set(progressKey, stepCounter)
              const progressCallId = `progress_${stepCounter}`
              // update ToolProgressBar store
              toolProgressStore.set({
                toolCallId: `${tn}-${stepCounter}`,
                toolName: tn,
                status: 'running',
              })
              onStep({
                stepType: 'tool_call',
                stepIndex: stepCounter,
                toolCallId: progressCallId,
                toolName: tn,
                toolParams: event.args || '',
                content: `Running ${tn}: ${event.args || ''}`,
                status: 'in_progress',
              })
            } else {
              // tool execute done(success/error)→ fill back args as toolParams
              const tn = event.toolName || 'unknown'
              const savedArgs = toolProgressArgsMap.get(tn) || ''
              const progressKey = `${tn}:${savedArgs}`
              const progressIndex = pendingToolProgress.get(progressKey)
                ?? Array.from(pendingToolProgress.entries())
                  .find(([key]) => key.startsWith(`${tn}:`))?.[1]
              pendingToolProgress.delete(progressKey)
              if (progressIndex !== undefined) {
                for (const [key, index] of pendingToolProgress.entries()) {
                  if (index === progressIndex) pendingToolProgress.delete(key)
                }
              }
              // update ToolProgressBar store
              toolProgressStore.update(`${tn}-${progressIndex ?? stepCounter}`, {
                status: event.status === 'success' ? 'success' : 'failure',
                detail: event.summary,
              })
              // delay remove progress bar
              setTimeout(() => toolProgressStore.remove(`${tn}-${stepCounter}`), 2000)
              onStep({
                stepType: 'tool_call',
                stepIndex: progressIndex ?? stepCounter,
                toolCallId: progressIndex !== undefined ? `progress_${progressIndex}` : undefined,
                toolName: tn,
                toolParams: savedArgs,
                toolResult: event.summary || '',
                status: event.status === 'success' ? 'success' : 'failure',
              })
            }
            break
          }

          case 'round_end': {
            // round nth end → send thinking step(show progress)
            const info = event.stepInfo
            if (info) {
              stepCounter++
              onStep({
                stepType: 'thinking',
                stepIndex: stepCounter,
                content: `Step ${info.currentStep}/${info.maxSteps} · ${info.totalToolCalls} tool calls`,
                status: info.shouldContinue ? 'in_progress' : 'success',
              })
            }
            break
          }

          case 'done': {
            // done → try try parse most end result
            let finalContent = ''
            if (event.content) {
              try {
                const result = JSON.parse(event.content)
                // assistantContent not save at than ReActResultDTO, take directly content
                finalContent = result.content || ''
              } catch (e) {
                console.warn('[SSE done] Failed to parse result JSON:', e)
                finalContent = ''
              }
            }
            // if parse fail or content is empty, fall back to lastFullText
            if (!finalContent && lastFullText) {
              finalContent = lastFullText
            }
            onText(finalContent)
            // pass pass file change summary
            if (event.changeSummary) {
              onChangeSummary?.(event.changeSummary)
            }
            // trigger onDone callback, ensure loading status clear remove
            // backend ok can not close SSE connection(missing emitter.complete()),
            // so not can dependency reader.read() done signal number from trigger onDone
            if (!doneCalled) {
              doneCalled = true
              onDone(finalContent)
            }
            break
          }

          case 'error': {
            // error event: same when through know onStep(render to chat center) and onError(trigger ErrorRecoveryCard)
            const errorMsg = event.content || 'Unknown error'
            onStep({
              stepType: 'result',
              stepIndex: ++stepCounter,
              error: errorMsg,
              status: 'failure',
            })
            // trigger error restore card
            onError(errorMsg)
            break
          }

          case 'warning': {
            // warning(non-fatal)
            onWarning?.(event.content || '')
            break
          }

          case 'permission_confirm': {
            // permission confirm request → push permissionStore
            if (event.permission && onPermissionConfirm) {
              onPermissionConfirm(event.permission)
            }
            break
          }

          case 'tool_output': {
            // tool real when output snippet
            if (event.toolCallId && event.outputChunk) {
              onToolOutput?.(event.toolCallId, event.outputChunk)
            }
            break
          }

          case 'status': {
            // status update(context collapse/ demote/ reconnect etc)
            if (event.statusMessage) {
              onStatus?.(event.statusMessage)
            }
            break
          }

          case 'round_start': {
            // new round nth start
            if (event.content) {
              onRoundStart?.(parseInt(event.content, 10) || 1)
            }
            break
          }

          case 'execute_local_command': {
            // SSE receive point command → directly execute → POST round pass result
            // GET /tool_result/pending only for SSE after reconnect, fetch commands missed while disconnected
            const cmdId = event.cmdId || ''
            const command = event.command || ''
            const cwd = event.cwd || undefined
            const cmdTimeoutMs = event.timeoutMs || 60000

            if (!cmdId || !command) {
              console.warn('[SSE] execute_local_command missing cmdId or command', event)
              break
            }

            console.log(`[SSE] received local command: cmdId=${cmdId}, command=${command}`)

            // run a local command asynchronously and return the output
            ;(async () => {
              const startTime = Date.now()
              try {
                // call Tauri local command execute
                const result = await invoke<{ success: boolean; stdout: string; stderr: string; exit_code: number }>(
                  'execute_shell_cmd',
                  {
                    command,
                    cwd,
                    timeoutMs: cmdTimeoutMs,
                    autoBackground: false,
                  }
                )

                const durationMs = Date.now() - startTime
                const output = (result.stdout || '') + (result.stderr ? `\n${result.stderr}` : '')

                console.log(`[SSE] local command finished: cmdId=${cmdId}, exitCode=${result.exit_code}, durationMs=${durationMs}`)

                // round pass result to Server
                const baseUrl = getBaseUrl()
                await fetch(`${baseUrl}/api/v1/tool_result`, {
                  method: 'POST',
                  headers: getAuthHeaders({ 'Content-Type': 'application/json' }),
                  body: JSON.stringify({
                    cmdId,
                    sessionId,
                    status: result.success ? 'SUCCESS' : 'ERROR',
                    output,
                    exitCode: result.exit_code,
                    durationMs,
                    success: result.success,
                  }),
                })
              } catch (err: any) {
                const durationMs = Date.now() - startTime
                console.error(`[SSE] local command failed: cmdId=${cmdId}`, err)

                // round pass error result
                try {
                  const baseUrl = getBaseUrl()
                  await fetch(`${baseUrl}/api/v1/tool_result`, {
                    method: 'POST',
                    headers: getAuthHeaders({ 'Content-Type': 'application/json' }),
                    body: JSON.stringify({
                      cmdId,
                      sessionId,
                      status: 'ERROR',
                      error: err?.message || 'Local command failed',
                      durationMs,
                      success: false,
                    }),
                  })
                } catch (postErr) {
                  console.error(`[SSE] failed to post command result: cmdId=${cmdId}`, postErr)
                }
              }
            })()
            break
          }

          case 'task_breakdown': {
            // task split unbind extract case
            if (event.taskBreakdown && onTaskBreakdown) {
              onTaskBreakdown(event.taskBreakdown)
            }
            break
          }

          case 'task_progress': {
            // subtask progress
            if (event.taskProgress && onTaskProgress) {
              onTaskProgress(event.taskProgress)
            }
            break
          }

          case 'sub_agent_call': {
            // proxy call start
            if (event.subAgent && onSubAgent) {
              onSubAgent(event.subAgent)
            }
            // same when as tool_call step show(use 🤖 prefix distinguish proxy)
            stepCounter++
            const subIdx = stepCounter
            onStep({
              stepType: 'tool_call',
              stepIndex: subIdx,
              toolName: `🤖 ${event.subAgent?.agentName || 'sub-agent'}`,
              content: `Delegate to sub-agent ${event.subAgent?.agentName || ''}: ${event.subAgent?.task || ''}`,
              status: 'in_progress',
            })
            break
          }

          case 'sub_agent_result': {
            // proxy execute done
            if (event.subAgent && onSubAgent) {
              onSubAgent(event.subAgent)
            }
            // update the latest sub-agent step status
            onStep({
              stepType: 'tool_call',
              stepIndex: stepCounter,
              toolResult: event.subAgent?.result || '',
              status: event.subAgent?.status === 'error' ? 'failure' : 'success',
            })
            break
          }
        }
      }

      read()
    })
    .catch((err) => {
      clearRequestTimer()
      if (err.name !== 'AbortError' || !isAborted) {
        // network error / timeout retry
        if (retryCount < cfg.maxRetries) {
          retryCount++
          console.warn(`[SSE] Network/timeout error, retrying ${retryCount}/${cfg.maxRetries}...`, err.message)
          onReconnect?.(retryCount, cfg.maxRetries)
          setTimeout(doFetch, cfg.retryBaseDelay * retryCount)
          return
        }
        onError(err.message)
      }
    })
  }

  doFetch()

  return () => {
    isAborted = true
    clearRequestTimer()
    controller.abort()
  }
}

/**
 * non streaming chat(compatible old interface)
 */
export function chatStream(
  agentId: string,
  userId: string,
  sessionId: string,
  message: string,
  onChunk: (text: string) => void,
  onDone: () => void,
  onError: (err: string) => void,
  terminalSessionId?: string | null,
): () => void {
  // demote to reactChatStream
  return reactChatStream(
    agentId, userId, sessionId, message,
    () => {}, // ignore steps
    onChunk, // text → onChunk
    () => onDone(),
    onError,
    terminalSessionId,
  )
}
