/**
 * share tool function and component
 * shared by AiTurnBlock / MessageBubble / MessageStream use
 *
 * re- build notes:
 * - remove frontend normalizeMarkdown(backend MarkdownNormalizer already handled)
 * - frontend cleanMarkdown as fallback(bold space + consecutive blank line + code block keep protect)
 * - unify tool icon/ group/ category function
 * - compact ToolCallView as form ok collapse
 */
import React, { useEffect, useMemo, useRef, useState } from 'react'
import ReactMarkdown from 'react-markdown'
import remarkGfm from 'remark-gfm'
import rehypeHighlight from 'rehype-highlight'
import { common } from 'lowlight'
import { useThemeStore } from '../stores/themeStore'
import type { ReActStep } from '../api/agent'
import type { MessageContextTagKind } from '../types'

// ═══════════════════════════════════════════════════════════════
// type
// ═══════════════════════════════════════════════════════════════

export interface ToolGroup {
  toolName: string
  label: string
  steps: ReActStep[]
  successCount: number
  failCount: number
}

const CONTEXT_TAG_META: Record<MessageContextTagKind, { label: string; color: string }> = {
  file: { label: 'File', color: '#3b82f6' },
  directory: { label: 'Folder', color: '#eab308' },
  project: { label: 'Project', color: '#8b5cf6' },
  image: { label: 'Image', color: '#ec4899' },
  terminal: { label: 'Selection', color: '#10b981' },
  connection: { label: 'Server', color: '#06b6d4' },
  custom: { label: 'Context', color: '#6b7280' },
}

export function getContextTagMeta(kind: MessageContextTagKind) {
  return CONTEXT_TAG_META[kind] || CONTEXT_TAG_META.custom
}

export function ContextTagChip({
  label,
  kind,
  fullContent,
  imageDataUrl,
  colors,
  variant = 'sent',
  onRemove,
  onAddToInput,
}: {
  label: string
  kind: MessageContextTagKind
  fullContent?: string
  imageDataUrl?: string
  colors: ReturnType<typeof useThemeStore.getState>['colors']
  variant?: 'input' | 'sent'
  onRemove?: () => void
  onAddToInput?: (content: string) => void
}) {
  const [open, setOpen] = useState(false)
  const [anchor, setAnchor] = useState({ left: 0, top: 0 })
  const containerRef = useRef<HTMLDivElement>(null)
  const meta = getContextTagMeta(kind)
  const isInput = variant === 'input'
  const hasPayload = !!fullContent?.trim()
  const resolvedImageDataUrl = imageDataUrl
    || fullContent?.match(/data:image\/[a-zA-Z+]+;base64,[A-Za-z0-9+/=]+/)?.[0]
  const isImage = kind === 'image' || !!resolvedImageDataUrl
  const preview = fullContent
    ? fullContent
        .split('\n')
        .filter(line => isImage ? !line.includes('data:image/') : true)
        .slice(0, isImage ? 6 : 24)
        .map(line => line.length > 180 ? `${line.slice(0, 180)}...` : line)
        .join('\n')
    : ''

  useEffect(() => {
    if (!open) return
    const handlePointerDown = (event: PointerEvent) => {
      if (containerRef.current && !containerRef.current.contains(event.target as Node)) {
        setOpen(false)
      }
    }
    document.addEventListener('pointerdown', handlePointerDown)
    return () => document.removeEventListener('pointerdown', handlePointerDown)
  }, [open])

  const openPopover = (event: React.MouseEvent<HTMLButtonElement>) => {
    const rect = event.currentTarget.getBoundingClientRect()
    const left = Math.max(8, Math.min(rect.left, window.innerWidth - 352))
    const top = Math.max(8, Math.min(rect.bottom + 8, window.innerHeight - 320))
    setAnchor({ left, top })
    setOpen(value => !value)
  }

  const copy = async () => {
    if (fullContent) await navigator.clipboard.writeText(fullContent)
    setOpen(false)
  }

  return (
    <div ref={containerRef} className="relative">
      <button
        type="button"
        onClick={hasPayload ? openPopover : undefined}
        className={`inline-flex max-w-[220px] items-center gap-1.5 text-[11px] leading-none transition-colors ${hasPayload ? 'cursor-pointer' : 'cursor-default'} ${isInput ? 'rounded-md px-2 py-[5px]' : 'rounded-full px-2.5 py-[5px]'}`}
        style={{
          backgroundColor: `${meta.color}${isInput ? '14' : '10'}`,
          border: isInput ? `1px solid ${meta.color}38` : '1px solid transparent',
          color: colors.textSecondary,
        }}
        title={fullContent ? `${meta.label} context · click to view` : label}
      >
        <span className="shrink-0 text-[10px]" style={{ color: meta.color }}>
          {kind === 'file' ? '📄' : kind === 'directory' ? '📁' : kind === 'image' ? '🖼️' : kind === 'terminal' ? '⌨️' : kind === 'connection' ? '🖥️' : kind === 'project' ? '🗂️' : '📎'}
        </span>
        <span className="truncate">{label}</span>
      </button>

      {isInput && onRemove && (
        <button
          type="button"
          onPointerDown={(event) => event.stopPropagation()}
          onClick={(event) => {
            event.preventDefault()
            event.stopPropagation()
            onRemove()
          }}
          className="absolute -right-1.5 -top-1.5 z-10 flex h-4 w-4 items-center justify-center rounded-full text-[10px] leading-none shadow-sm transition-colors"
          style={{
            backgroundColor: colors.bgInput,
            border: `1px solid ${colors.border}`,
            color: colors.textSecondary,
          }}
          title="Remove"
        >
          ×
        </button>
      )}

      {open && hasPayload && (
        <div
          className="fixed z-[9999] w-[336px] overflow-hidden rounded-xl border shadow-2xl"
          style={{
            left: anchor.left,
            top: anchor.top,
            backgroundColor: colors.bgInput,
            borderColor: `${colors.border}70`,
          }}
        >
          <div className="border-b px-3 py-2 text-[11px] font-medium" style={{ borderColor: `${colors.border}50`, color: colors.textSecondary }}>
            {label}
          </div>
          <div className="max-h-[180px] overflow-auto px-3 py-2">
            {resolvedImageDataUrl && (
              <img src={resolvedImageDataUrl} alt={label} className="mb-2 max-h-48 w-auto rounded-md" />
            )}
            <pre className="whitespace-pre-wrap break-words text-[11px] leading-relaxed" style={{ color: colors.textSecondary }}>
              {preview || fullContent}
            </pre>
          </div>
          <div className="flex items-center justify-end gap-2 border-t px-3 py-2" style={{ borderColor: `${colors.border}50` }}>
            {onRemove && (
              <button type="button" onClick={() => { onRemove(); setOpen(false) }}
                className="rounded-md px-2 py-1 text-[11px] transition-colors hover:bg-black/5"
                style={{ color: colors.textSecondary }}>
                Remove
              </button>
            )}
            {onAddToInput && (
              <button type="button" onClick={() => { onAddToInput(fullContent || ''); setOpen(false) }}
                className="rounded-md px-2 py-1 text-[11px] font-medium transition-opacity hover:opacity-85"
                style={{ backgroundColor: colors.accent, color: '#fff' }}>
                Add to input
              </button>
            )}
            <button type="button" onClick={copy}
              className="rounded-md px-2 py-1 text-[11px] transition-colors hover:bg-black/5"
              style={{ color: colors.textSecondary }}>
              Copy
            </button>
          </div>
        </div>
      )}
    </div>
  )
}

// ═══════════════════════════════════════════════════════════════
// assist help function
// ═══════════════════════════════════════════════════════════════

export function formatTime(timestamp: number): string {
  const d = new Date(timestamp)
  const hh = String(d.getHours()).padStart(2, '0')
  const mm = String(d.getMinutes()).padStart(2, '0')
  return `${hh}:${mm}`
}

/**
 * split \x3Cthink\x3E...\x3C/think\x3E tab
 * backend although not push thinking event, but AI text center ok can inside embed think tab
 */
export function splitThinkTags(content: string): Array<{ type: 'think' | 'text'; content: string; isStreaming?: boolean }> {
  if (!content) return []
  const parts: Array<{ type: 'think' | 'text'; content: string; isStreaming?: boolean }> = []
  const thinkOpen = String.fromCharCode(60) + 'think' + String.fromCharCode(62)
  const thinkClose = String.fromCharCode(60) + '/think' + String.fromCharCode(62)
  const regex = new RegExp(thinkOpen + '([\\s\\S]*?)(' + thinkClose.replace(/\//g, '\\/') + '|$)', 'g')
  let lastIndex = 0
  let match: RegExpExecArray | null

  while ((match = regex.exec(content)) !== null) {
    if (match.index > lastIndex) {
      parts.push({ type: 'text', content: content.slice(lastIndex, match.index) })
    }
    parts.push({
      type: 'think',
      content: match[1].trim(),
      isStreaming: !match[2] || match[2] !== thinkClose,
    })
    lastIndex = match.index + match[0].length
  }

  if (lastIndex < content.length) {
    parts.push({ type: 'text', content: content.slice(lastIndex) })
  }

  return parts.length > 0 ? parts : [{ type: 'text', content }]
}

/** from toolParams/toolResult center extract language meaning tab */
export function extractToolLabel(step: ReActStep): string {
  const params = step.toolParams || ''
  const result = step.toolResult || ''
  const toolName = step.toolName || ''

  const filePathMatch = params.match(/(?:[\w.-]+\/)*[\w.-]+\.(java|js|ts|jsx|tsx|py|go|rs|rb|php|xml|html|vue|css|scss|json|yml|yaml|toml|sh|bash|zsh|sql|md|txt|properties|conf|cfg|env|gradle|xml|kt|swift|c|cpp|h|hpp)/i)
  if (filePathMatch) {
    const parts = filePathMatch[0].split('/')
    return parts[parts.length - 1]
  }

  if (toolName.toLowerCase().includes('ssh') || toolName.toLowerCase().includes('exec') || toolName.toLowerCase().includes('shell')) {
    const cmd = params.trim().split('\n')[0].trim()
    if (cmd) return cmd.length > 50 ? cmd.substring(0, 50) + '...' : cmd
  }

  if (toolName === 'readLocalFile' || toolName === 'readFile') {
    const pathMatch = params.match(/['"]?([^'"\s]+)['"]?/)
    if (pathMatch) {
      const parts = pathMatch[1].split('/')
      return parts[parts.length - 1] || pathMatch[1]
    }
  }

  if (toolName === 'CodeEditTool' || toolName === 'CodeEdit') {
    const fileFromParams = params.match(/(?:file|path|filePath)['"]?\s*[:=]\s*['"]?([^'"\s,]+)/i)
    if (fileFromParams) {
      const parts = fileFromParams[1].split('/')
      return parts[parts.length - 1]
    }
    const fileFromResult = result.match(/(?:file|path|filePath)['"]?\s*[:=]\s*['"]?([^'"\s,]+)/i)
    if (fileFromResult) {
      const parts = fileFromResult[1].split('/')
      return parts[parts.length - 1]
    }
  }

  if (params.trimStart().startsWith('{')) {
    try {
      const parsed = JSON.parse(params)
      const fileVal = parsed.file || parsed.path || parsed.filePath || parsed.filename
      if (fileVal) {
        const parts = String(fileVal).split('/')
        return parts[parts.length - 1]
      }
      const cmdVal = parsed.command || parsed.cmd
      if (cmdVal) return String(cmdVal).substring(0, 50)
    } catch {}
  }

  if (result.trimStart().startsWith('{')) {
    try {
      const parsed = JSON.parse(result)
      const fileVal = parsed.file || parsed.path || parsed.filePath
      if (fileVal) {
        const parts = String(fileVal).split('/')
        return parts[parts.length - 1]
      }
    } catch {}
  }

  return toolName || 'Tool'
}

/** tool step by (toolName, label) group aggregate merge */
export function groupToolSteps(steps: ReActStep[]): ToolGroup[] {
  const groups: ToolGroup[] = []
  const keyMap = new Map<string, number>()

  for (const step of steps) {
    const label = extractToolLabel(step)
    const toolName = step.toolName || 'Unknown'
    const key = `${toolName}::${label}`

    const idx = keyMap.get(key)
    if (idx !== undefined) {
      groups[idx].steps.push(step)
      if (step.status === 'success') groups[idx].successCount++
      if (step.status === 'failure') groups[idx].failCount++
    } else {
      keyMap.set(key, groups.length)
      groups.push({
        toolName,
        label,
        steps: [step],
        successCount: step.status === 'success' ? 1 : 0,
        failCount: step.status === 'failure' ? 1 : 0,
      })
    }
  }

  return groups
}

/** tool type category */
export function classifyTool(toolName: string): 'file-read' | 'file-edit' | 'terminal' | 'search' | 'directory' | 'agent' | 'mcp' | 'other' {
  const n = toolName.toLowerCase()
  if (n === 'readlocalfile' || n === 'readfile' || n === 'read_file') return 'file-read'
  if (n === 'writelocalfile' || n === 'writefile' || n === 'write_file' || n === 'codeedit' || n === 'codeedittool' || n === 'fileedittool' || n.includes('edit') || n.includes('write')) return 'file-edit'
  if (n.includes('ssh') || n.includes('exec') || n.includes('shell') || n.includes('terminal') || n.includes('bash')) return 'terminal'
  if (n.includes('search') || n.includes('find') || n.includes('grep')) return 'search'
  if (n.includes('list') || n.includes('dir') || n.includes('directory')) return 'directory'
  if (n.includes('agent') || n.includes('sub')) return 'agent'
  if (n.includes('.') && !n.includes(' ') && !['readlocalfile','writelocalfile','listlocalfiles'].includes(n)) return 'mcp'
  return 'other'
}

/** get tool SVG icon + color */
export function getToolIconInfo(toolName: string): { icon: React.ReactNode; color: string; bgColor: string; label: string } {
  const type = classifyTool(toolName)
  switch (type) {
    case 'file-read':
      return {
        icon: <svg className="w-3.5 h-3.5" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/><polyline points="14 2 14 8 20 8"/><line x1="16" y1="13" x2="8" y2="13"/><line x1="16" y1="17" x2="8" y2="17"/></svg>,
        color: '#3b82f6',
        bgColor: 'rgba(59,130,246,0.10)',
        label: 'Read file',
      }
    case 'file-edit':
      return {
        icon: <svg className="w-3.5 h-3.5" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><path d="M11 4H4a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7"/><path d="M18.5 2.5a2.121 2.121 0 0 1 3 3L12 15l-4 1 1-4 9.5-9.5z"/></svg>,
        color: '#f97316',
        bgColor: 'rgba(249,115,22,0.10)',
        label: 'Modify file',
      }
    case 'terminal':
      return {
        icon: <svg className="w-3.5 h-3.5" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><polyline points="4 17 10 11 4 5"/><line x1="12" y1="19" x2="20" y2="19"/></svg>,
        color: '#10b981',
        bgColor: 'rgba(16,185,129,0.10)',
        label: 'Shell command',
      }
    case 'search':
      return {
        icon: <svg className="w-3.5 h-3.5" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><circle cx="11" cy="11" r="8"/><line x1="21" y1="21" x2="16.65" y2="16.65"/></svg>,
        color: '#8b5cf6',
        bgColor: 'rgba(139,92,246,0.10)',
        label: 'Search',
      }
    case 'directory':
      return {
        icon: <svg className="w-3.5 h-3.5" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><path d="M22 19a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h5l2 3h9a2 2 0 0 1 2 2z"/></svg>,
        color: '#eab308',
        bgColor: 'rgba(234,179,8,0.10)',
        label: 'Directory ops',
      }
    case 'agent':
      return {
        icon: <svg className="w-3.5 h-3.5" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><rect x="3" y="11" width="18" height="10" rx="2"/><circle cx="12" cy="5" r="2"/><path d="M7 11V7a5 5 0 0 1 10 0v4"/><circle cx="8" cy="16" r="1" fill="currentColor"/><circle cx="16" cy="16" r="1" fill="currentColor"/></svg>,
        color: '#ec4899',
        bgColor: 'rgba(236,72,153,0.10)',
        label: 'Sub-agent',
      }
    case 'mcp':
      return {
        icon: <svg className="w-3.5 h-3.5" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><circle cx="12" cy="12" r="3"/><path d="M12 1v4M12 19v4M4.22 4.22l2.83 2.83M16.95 16.95l2.83 2.83M1 12h4M19 12h4M4.22 19.78l2.83-2.83M16.95 7.05l2.83-2.83"/></svg>,
        color: '#a855f7',
        bgColor: 'rgba(168,85,247,0.10)',
        label: toolName,
      }
    default:
      return {
        icon: <svg className="w-3.5 h-3.5" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><path d="M14.7 6.3a1 1 0 0 0 0 1.4l1.6 1.6a1 1 0 0 0 1.4 0l3.77-3.77a6 6 0 0 1-7.94 7.94l-6.91 6.91a2.12 2.12 0 0 1-3-3l6.91-6.91a6 6 0 0 1 7.94-7.94l-3.76 3.76z"/></svg>,
        color: '#6b7280',
        bgColor: 'rgba(107,114,128,0.10)',
        label: toolName || 'Tool',
      }
  }
}

// ═══════════════════════════════════════════════════════════════
// Markdown normalize(with backend MarkdownNormalizer align)
// streaming stage frontend also do normalize, ensure render one cause
// ═══════════════════════════════════════════════════════════════

const CB_PREFIX = '\u0001CB'
const CB_SUFFIX = '\u0001'
const IC_PREFIX = '\u0001IC'

/**
 * protect code blocks and inline elements, replace as placeholder
 * CB_PREFIX: code block(front back need line break)
 * IC_PREFIX: inline elements(bold/ italic/ inline code, front back not line break)
 */
const TB_PREFIX = '\u0001TB'
const TB_SUFFIX = '\u0001'

/**
 * keep protect table ok(| on head ok), avoid list rule error match
 * table ok center | -- | equal content will [-*+]\s rule error judge as list
 */
function protectTableLines(text: string, store: string[]): string {
  const lines = text.split('\n')
  const result: string[] = []
  for (let i = 0; i < lines.length; i++) {
    const trimmed = lines[i].trim()
    if (trimmed.startsWith('|') && trimmed.length > 1 && isTableRowJS(trimmed)) {
      const placeholder = TB_PREFIX + store.length + TB_SUFFIX
      store.push(lines[i])
      result.push(placeholder)
    } else {
      result.push(lines[i])
    }
  }
  return result.join('\n')
}

function restoreTableLines(text: string, store: string[]): string {
  let r = text
  for (let i = store.length - 1; i >= 0; i--) {
    r = r.replace(TB_PREFIX + i + TB_SUFFIX, store[i])
  }
  return r
}

function protectElements(text: string, store: string[]): string {
  let r = text
  // 1. around bar code block ```...``` → CB(strict cell match, must close)
  r = replaceAndStoreJS(r, /```[^\n]*\n[\s\S]*?```/g, store, CB_PREFIX)
  // 1b. fallback: not yet close merge around bar code block(AI streaming output ok can missing bind end ```)
  r = replaceAndStoreJS(r, /```[^\n]*\n[\s\S]*$/g, store, CB_PREFIX)
  // 2. inline code `...` → IC
  r = replaceAndStoreJS(r, /`[^`\n]+`/g, store, IC_PREFIX)
  // 3. bold **...** → IC(must at italic front extract)
  r = replaceAndStoreJS(r, /\*\*[^*\n]+\*\*/g, store, IC_PREFIX)
  // 4. italic *...* → IC(bold already extract, remaining single * i.e. italic)
  r = replaceAndStoreJS(r, /\*[^*\n]+\*/g, store, IC_PREFIX)
  return r
}

function replaceAndStoreJS(text: string, pattern: RegExp, store: string[], prefix: string): string {
  return text.replace(pattern, (m) => {
    const placeholder = prefix + store.length + CB_SUFFIX
    store.push(m)
    return placeholder
  })
}

function restoreElements(text: string, store: string[]): string {
  let r = text
  for (let i = store.length - 1; i >= 0; i--) {
    // strip junk text at the start of code blocks(Feishu/ Yuque lead out" copy" button text)
    const cleaned = stripCopyLabelFromCodeBlockJS(store[i])
    r = r.replace(CB_PREFIX + i + CB_SUFFIX, cleaned)
    r = r.replace(IC_PREFIX + i + CB_SUFFIX, store[i])
  }
  return r
}

/**
 * clean code block content on head" copy" junk line.
 * Feishu/ Yuque equal rich text editor lead out Markdown when," copy code" button text write code block:
 * ```text
 * copy
 * true align code content...
 * ```
 * with backend stripCopyLabelFromCodeBlock align
 */
function stripCopyLabelFromCodeBlockJS(codeBlock: string): string {
  if (!codeBlock.startsWith('```')) return codeBlock
  const firstNewline = codeBlock.indexOf('\n')
  if (firstNewline < 0) return codeBlock
  const content = codeBlock.substring(firstNewline + 1)
  const secondNewline = content.indexOf('\n')
  let firstLine: string
  let rest: string
  if (secondNewline < 0) {
    firstLine = content.trim()
    rest = ''
  } else {
    firstLine = content.substring(0, secondNewline).trim()
    rest = content.substring(secondNewline + 1)
  }
  if (firstLine === 'Copy' || firstLine === 'Copy code' || firstLine === '复制' || firstLine === '复制代码') {
    return codeBlock.substring(0, firstNewline + 1) + rest
  }
  return codeBlock
}

/**
 * table handle:|| split lines + patch all separator row
 * with backend processTables / fixTableBlocks align
 */
function processTablesJS(text: string): string {
  let r = mergeTableFragmentsJS(text)
  r = cleanupOrphanedDashFragmentsJS(r)
  r = splitTableContentFromListItemsJS(r)
  r = splitDoublePipeTablesJS(r)
  r = fixTableBlocksJS(r)
  r = dedupSeparatorRowsJS(r)
  r = compactTableBlocksJS(r)
  return r
}

function splitDoublePipeTablesJS(text: string): string {
  if (!text.includes('||')) return text
  const lines = text.split('\n')
  const result: string[] = []
  for (let i = 0; i < lines.length; i++) {
    if (i > 0) result.push('\n')
    if (lines[i].includes('||')) {
      result.push(splitSingleGluedLineJS(lines[i]))
    } else {
      result.push(lines[i])
    }
  }
  return result.join('')
}

function splitSingleGluedLineJS(line: string): string {
  const firstPipe = line.indexOf('|')
  if (firstPipe < 0) return line

  let prefix = ''
  let tablePart = line
  if (firstPipe > 0) {
    const before = line.substring(0, firstPipe).trim()
    if (before && !before.startsWith('|')) {
      prefix = before + '\n'
      tablePart = line.substring(firstPipe)
    }
  }

  const segments = tablePart.split('||')
  const rows: string[] = []

  for (const seg of segments) {
    let row = seg.trim()
    if (!row) continue

    // check end part non table content
    const trailingIdx = findTrailingContentIndexJS(row)
    let tableRowPart = row
    let trailingContent: string | null = null

    if (trailingIdx > 0) {
      tableRowPart = row.substring(0, trailingIdx + 1).trim()
      trailingContent = row.substring(trailingIdx + 1).trim()
    }

    if (tableRowPart) {
      if (!tableRowPart.startsWith('|')) tableRowPart = '|' + tableRowPart
      if (!tableRowPart.endsWith('|')) tableRowPart = tableRowPart + '|'
      if (isSeparatorContentJS(tableRowPart)) {
        tableRowPart = formatSeparatorRowJS(tableRowPart)
      } else {
        tableRowPart = normalizeCellSpacingJS(tableRowPart)
      }
      rows.push(tableRowPart)
    }

    if (trailingContent) rows.push(trailingContent)
  }

  return prefix + rows.join('\n')
}

function findTrailingContentIndexJS(segment: string): number {
  // | followed by ## title
  const m1 = /\|\s*(#{1,6}\s)/.exec(segment)
  if (m1) return m1.index

  // | followed by center text and back side no more extra | and length>30 or contains mark point
  const m2 = /\|\s*([\u4e00-\u9fa5])/.exec(segment)
  if (m2) {
    const after = segment.substring(m2.index + m2[0].length)
    if (!after.includes('|')) {
      const textAfter = segment.substring(m2.index + 1).trim()
      if (/[.!?]/.test(textAfter) || textAfter.length > 30) return m2.index
    }
  }
  return -1
}

function isSeparatorContentJS(row: string): boolean {
  const trimmed = row.trim()
  if (!trimmed.startsWith('|') || trimmed.length <= 2) return false
  let inner = trimmed.substring(1, trimmed.endsWith('|') ? trimmed.length - 1 : trimmed.length)
  if (!inner) return false
  const cells = inner.split('|')
  let hasDash = false
  for (const cell of cells) {
    const c = cell.trim()
    if (!c) continue
    if (!/^[-:]+$/.test(c)) return false
    if (c.includes('-')) hasDash = true
  }
  return hasDash
}

function formatSeparatorRowJS(row: string): string {
  const trimmed = row.trim()
  let inner = trimmed.substring(1, trimmed.endsWith('|') ? trimmed.length - 1 : trimmed.length)
  const cells = inner.split('|')
  const sb: string[] = ['|']
  for (const cell of cells) {
    const c = cell.trim()
    if (!c) continue
    sb.push(' ' + c + ' |')
  }
  return sb.join('')
}

function normalizeCellSpacingJS(row: string): string {
  const trimmed = row.trim()
  let inner = trimmed.substring(1, trimmed.endsWith('|') ? trimmed.length - 1 : trimmed.length)
  const cells = inner.split('|')
  const sb: string[] = ['|']
  for (const cell of cells) {
    sb.push(' ' + cell.trim() + ' |')
  }
  return sb.join('')
}

/**
 * merge AI fragmented table rows in streaming output.
 * AI streaming output when, separator row |---|---| may be split done multiple fragment:
 * | (only vertical line)
 * ------| (separator content fragment)
 * ------| (other a fragment)
 * this merges those fragments into a full table separator row.
 * with backend mergeTableFragments align
 */
function mergeTableFragmentsJS(text: string): string {
  const lines = text.split('\n')
  const result: string[] = []
  let i = 0
  while (i < lines.length) {
    const line = lines[i].trim()

    // detect fragment mode: a | ok followed by fragment ok
    if (line === '|') {
      let j = i + 1
      // skip blank line
      while (j < lines.length && !lines[j].trim()) j++
      // receive set fragment
      const fragments: string[] = []
      while (j < lines.length && /^-+\|?$/.test(lines[j].trim())) {
        fragments.push(lines[j].trim())
        j++
        // skip fragment middle blank line
        while (j < lines.length && !lines[j].trim()) j++
      }

      if (fragments.length > 0) {
        // merge fragment as complete separator row
        const separator = '|' + fragments.map(() => ' --- |').join('')
        result.push(separator)
        i = j
      } else {
        // no has fragment, check whether the next line is a table data row
        let nextNonEmpty = i + 1
        while (nextNonEmpty < lines.length && !lines[nextNonEmpty].trim()) nextNonEmpty++
        if (nextNonEmpty < lines.length && lines[nextNonEmpty].trim().startsWith('|') && lines[nextNonEmpty].trim().length > 1) {
          // | is separator row fragment on head, need to check whether the previous line is a table data row
          const prevIsTable = result.length > 0 && result[result.length - 1].trim().startsWith('|') && result[result.length - 1].trim().length > 1
          if (prevIsTable) {
            const colCount = countColumnsJS(result[result.length - 1])
            result.push(buildSeparatorRowJS(colCount))
          }
          i = nextNonEmpty
        } else {
          result.push(lines[i])
          i++
        }
      }
    } else if (/^-+\|?$/.test(line)) {
      // alone set fragment ok ------|, may be separator row leftover keep
      const prevIsTable = result.length > 0 && result[result.length - 1].trim().startsWith('|') && result[result.length - 1].trim().length > 1
      if (prevIsTable) {
        i++ // skip fragment
        continue
      }
      result.push(lines[i])
      i++
    } else {
      result.push(lines[i])
      i++
    }
  }
  return result.join('\n')
}

/**
 * clean orphan dash lines left by fragmented table separators.
 * like "--","-","------" equal not is table ok pure short horizontal line ok.
 * with backend cleanupOrphanedDashFragments align
 */
function cleanupOrphanedDashFragmentsJS(text: string): string {
  const lines = text.split('\n')
  const result: string[] = []
  for (const line of lines) {
    const trimmed = line.trim()
    // delete orphan set pure short horizontal line fragment(like "--", "-", "------")
    if (/^-{1,50}$/.test(trimmed) && trimmed !== '---') continue
    // delete orphan set short horizontal line+ manage way fragment(like "------|", "---------|")
    if (/^-{2,50}\|$/.test(trimmed)) continue
    // handle fragment sticky merge ok: like "---------| | IC24 | external API contract |"
    const glueMatch = /^-{2,50}\|(.+\|.+)/.exec(trimmed)
    if (glueMatch) {
      const pipeIdx = trimmed.indexOf('|')
      const afterPipe = trimmed.substring(pipeIdx + 1).trim()
      result.push('| ' + afterPipe)
      continue
    }
    // remove pure-dash fragments inside table rows:| ------ | equal
    if (trimmed.startsWith('|') && trimmed.endsWith('|')) {
      const noSep = trimmed.replace(/[|\-\s:]/g, '')
      if (!noSep && !trimmed.includes('---')) continue
    }
    result.push(line)
  }
  return result.join('\n')
}

/**
 * go remove consecutive duplicate separator row.
 * mergeTableFragments and fixTableBlocks ok can each generate one nth separator row,
 * cause consecutive appear multiple | --- | --- |.
 * with backend dedupSeparatorRows align
 */
function dedupSeparatorRowsJS(text: string): string {
  const lines = text.split('\n')
  const result: string[] = []
  let lastWasSep = false
  for (const line of lines) {
    const trimmed = line.trim()
    const isSep = trimmed.startsWith('|') && trimmed.includes('---') && /^\|\s*:?-+:?\s*(\|\s*:?-+:?\s*)*\|?$/.test(trimmed)
    if (isSep && lastWasSep) continue
    if (isSep) {
      lastWasSep = true
      // round delete separator row after blank line
      if (result.length > 0 && !result[result.length - 1].trim()) {
        result.pop()
      }
    } else if (trimmed) {
      lastWasSep = false
    }
    result.push(line)
  }
  return result.join('\n')
}

/**
 * split away table ok center sticky merge list/ title content.
 * AI sometimes glues the last table row to the following list:
 * | downside | extension stuck hard |\u0001IC1\u0001- ✅ private has build make function
 * with backend splitTableContentFromListItems align
 */
function splitTableContentFromListItemsJS(text: string): string {
  const lines = text.split('\n')
  const result: string[] = []

  for (const line of lines) {
    const trimmed = line.trim()
    if (trimmed.startsWith('|') && trimmed.length > 1) {
      const splitPos = findSplitPositionJS(trimmed)
      if (splitPos > 0 && splitPos < trimmed.length - 1) {
        const tablePart = trimmed.substring(0, splitPos + 1) // include |
        const trailing = trimmed.substring(splitPos + 1)
        if (countPipesJS(tablePart) >= 2) {
          result.push(tablePart)
          result.push('')
          result.push(trailing.replace(/^[ \t]+/, ''))
          continue
        }
      }
      result.push(line)
    } else {
      result.push(line)
    }
  }
  return result.join('\n')
}

/**
 * find the last valid split point in a table row, right to left.
 * return the | index,-1 table show no sticky merge content.
 */
function findSplitPositionJS(line: string): number {
  for (let pos = line.length - 1; pos >= 0; pos--) {
    if (line[pos] !== '|') continue
    const after = line.substring(pos + 1)
    if (!after) continue
    if (after.startsWith(IC_PREFIX) || after.startsWith(CB_PREFIX)
      || after.startsWith('**') || after.startsWith('##')
      || /^-\s*[✅❌⚠].*/.test(after) || /^-\s+\S/.test(after)) {
      const before = line.substring(0, pos)
      if (countPipesJS(before) >= 1) return pos
    }
  }
  return -1
}

function countPipesJS(line: string): number {
  let count = 0
  for (const c of line) { if (c === '|') count++ }
  return count
}

/**
 * collapse table chunk inside blank line.
 * table rows must not have blank lines between them, otherwise Markdown render widget not recognize other as table.
 * with backend compactTableBlocks align
 */
function compactTableBlocksJS(text: string): string {
  const lines = text.split('\n')
  const result: string[] = []
  for (let i = 0; i < lines.length; i++) {
    const trimmed = lines[i].trim()
    // if current ok is blank line, check front back whether all is table ok
    if (!trimmed && result.length > 0) {
      const prev = result[result.length - 1].trim()
      const next = (i + 1 < lines.length) ? lines[i + 1].trim() : ''
      if (prev.startsWith('|') && prev.endsWith('|') && next.startsWith('|') && next.endsWith('|')) {
        continue // skip table ok middle blank line
      }
    }
    result.push(lines[i])
  }
  return result.join('\n')
}

function fixTableBlocksJS(text: string): string {
  const lines = text.split('\n')
  const result: string[] = []
  let i = 0
  while (i < lines.length) {
    if (isTableRowJS(lines[i])) {
      const tableRows: string[] = []
      while (i < lines.length && isTableRowJS(lines[i])) {
        tableRows.push(lines[i])
        i++
      }
      result.push(...fixSingleTableJS(tableRows))
    } else {
      result.push(lines[i])
      i++
    }
  }
  return result.join('\n')
}

function isTableRowJS(line: string): boolean {
  const trimmed = line.trim()
  return trimmed.startsWith('|') && trimmed.length > 1
}

function fixSingleTableJS(rows: string[]): string[] {
  if (rows.length === 0) return rows
  if (isSeparatorContentJS(rows[0])) return rows

  const colCount = countColumnsJS(rows[0])
  const fixed: string[] = []

  for (let j = 0; j < rows.length; j++) {
    const row = rows[j]
    if (isSeparatorContentJS(row)) {
      const sepCols = countColumnsJS(row)
      if (sepCols !== colCount) {
        fixed.push(buildSeparatorRowJS(colCount))
      } else {
        fixed.push(formatSeparatorRowJS(row))
      }
    } else {
      // all data ok all do normalizeCellSpacingJS(ensure | props | value | format)
      let r = row.trim()
      if (!r.startsWith('|')) r = '|' + r
      if (!r.endsWith('|')) r = r + '|'
      r = normalizeCellSpacingJS(r)
      fixed.push(r)
      if (j === 0) {
        const nextIsSep = (j + 1 < rows.length) && isSeparatorContentJS(rows[j + 1])
        if (!nextIsSep) fixed.push(buildSeparatorRowJS(colCount))
      }
    }
  }
  return fixed
}

function countColumnsJS(row: string): number {
  const trimmed = row.trim()
  if (!trimmed.startsWith('|')) return 0
  let inner = trimmed
  if (inner.startsWith('|')) inner = inner.substring(1)
  if (inner.endsWith('|')) inner = inner.substring(0, inner.length - 1)
  return inner.split('|').length
}

function buildSeparatorRowJS(colCount: number): string {
  const cells: string[] = []
  for (let c = 0; c < colCount; c++) cells.push(' --- ')
  return '|' + cells.join('|') + '|'
}

/**
 * correct Markdown showcase block (```markdown... ```) content do two nth normalize
 * with backend normalizeMarkdownShowcaseBlocks align
 */
function normalizeMarkdownShowcaseBlocksJS(text: string): string {
  return text.replace(/(```(?:markdown|md)\n?)([\s\S]*?)(```)/g, (_match, _opening, inner, _closing) => {
    return normalizeMarkdown(inner)
  })
}

/**
 * blank line fix restore: ensure title/ list/ table/ code block front back has blank line
 * with backend ensureBlankLines align
 */
function ensureBlankLinesJS(text: string): string {
  const lines = text.split('\n')
  const result: string[] = []

  for (let i = 0; i < lines.length; i++) {
    if (i > 0) {
      const prev = lines[i - 1].replace(/[ \t]+$/, '')
      const curr = lines[i].replace(/[ \t]+$/, '')

      if (!prev) {
        result.push(lines[i])
        continue
      }

      let needBlank = false

      if (/^#{1,6}\s/.test(curr) && !/^#{1,6}\s/.test(prev)) needBlank = true
      if (/^#{1,6}\s/.test(prev) && !/^#{1,6}\s/.test(curr) && curr && !curr.startsWith('|') && !isSeparatorContentJS(curr)) needBlank = true
      if (/^\d+\.\s/.test(curr) && !/^\d+\.\s/.test(prev)) needBlank = true
      if (/^[\-*+]\s/.test(curr) && !/^[\-*+]\s/.test(prev)) needBlank = true
      if (curr.startsWith('|') && !prev.startsWith('|')) needBlank = true
      if (prev.startsWith('|') && !curr.startsWith('|') && curr) needBlank = true
      if (curr.startsWith(CB_PREFIX) && !prev.startsWith(CB_PREFIX)) needBlank = true
      if (prev.endsWith(CB_SUFFIX) && !curr.startsWith(CB_PREFIX) && curr) needBlank = true

      if (needBlank) result.push('')
    }
    result.push(lines[i])
  }
  return result.join('\n')
}

/**
 * complete Markdown normalize function
 * with backend MarkdownNormalizer.normalize() rule align
 * text processing for the frontend streaming phase
 */
function normalizeMarkdown(text: string): string {
  if (!text) return text

  // ═══ stage 0: protect code blocks and inline elements ═══
  const store: string[] = []
  let r = protectElements(text, store)

  // ═══ stage 1: table handle(must at line break fix restore front) ═══
  // fragment merge + orphan set short horizontal line clean + table end part sticky merge split + || split lines + separator row fix restore + dedupe + collapse blank line
  r = processTablesJS(r)

  // ═══ stage 1.5: keep protect table ok-- avoid back continue list rule error match ═══
  const tableStore: string[] = []
  r = protectTableLines(r, tableStore)

  // ═══ stage 2: line break fix restore ═══
  // 2a. ## back patch space
  r = r.replace(/(#{2,6})([^\s#])/g, '$1 $2')
  r = r.replace(/(^|\n)(#)([^\s#])/g, '$1$2 $3')

  // 2b. title mark front line break+ blank line
  r = r.replace(/([^\n\s#])(#{1,6}\s)/g, '$1\n\n$2')

  // 2d. title followed by table mark → line break
  r = r.replace(/(#{1,6}\s[^\n|]+)(\|)/g, '$1\n\n$2')

  // 2d2. title followed by code block placeholder → line break
  r = r.replace(/(#{1,6}\s[^\n\u0001]+)(\u0001CB)/g, '$1\n\n$2')

  // 2f0. has order list mark back patch space
  r = r.replace(/(\d{1,2})\.([^\s\d\n.])/g, '$1. $2')

  // 2h0. no order list mark back patch space(center text scenario)
  // row remove connect char scenario: letter/ number after - is connect char and non list mark(like x64- architecture)
  r = r.replace(/(?<![a-zA-Z0-9\u0001])([-*+])([\u4e00-\u9fa5])/g, '$1 $2')
  // 2h0-en. also insert a space when a list marker is followed by an uppercase letter(class name/ file name through always large write on head)
  // row remove connect char scenario: letter/ number after - is connect char and non list mark(xfg-wrench,JSON-RPC,UTF-8)
  r = r.replace(/(?<![a-zA-Z0-9\u0001])([-*+])([A-Z])/g, '$1 $2')
  // 2h0-en2. list mark followed by small write letter+ center text mix merge content when patch space(row remove pure en text connect char like self-contained)
  // row remove connect char scenario: letter/ number after - is connect char and non list mark(xfg-wrench framework)
  r = r.replace(/(?<![a-zA-Z0-9\u0001])([-*+])([a-z]+)([\u4e00-\u9fa5])/g, '$1 $2$3')
  // 2h0-ext. list mark followed by IC placeholder also patch space
  r = r.replace(/([-*+])(\u0001IC)/g, '$1 $2')

  // 2h1. insert a space between CJK characters and digits
  r = r.replace(/([\u4e00-\u9fa5])(\d)/g, '$1 $2')
  r = r.replace(/(?<!-)(\d)([\u4e00-\u9fa5])/g, '$1 $2')

  // 2f. has order list front line break
  r = r.replace(/([^\n\d\s.])(1\.\s)/g, '$1\n$2')

  // 2g. has order list item middle line break
  r = r.replace(/([^\n\d.#\s])(\d{1,2}\.\s)/g, '$1\n$2')

  // 2h. no order list front line break
  r = r.replace(/([^\n\s])([-*+]\s)/g, '$1\n$2')

  // 2i. tree shape symbol front line break
  r = r.replace(/([^\n])(├──|└──)/g, '$1\n$2')

  // 2j. code block placeholder front line break
  r = r.replace(/([^\n\u0001])(\u0001CB)/g, '$1\n$2')

  // 2k. code block placeholder back line break
  r = r.replace(/(\u0001CB\d+\u0001)([^\n\u0001])/g, '$1\n$2')

  // stage 2.5: fix table rows newly exposed after line breaks
  r = fixTableBlocksJS(r)

  // stage 3: blank line fix restore
  r = ensureBlankLinesJS(r)

  // stage 4: clean
  r = r.replace(/\n{3,}/g, '\n\n')
  r = r.replace(/[ \t]+\n/g, '\n')
  r = r.replace(/^\s+/, '').replace(/\s+$/, '')

  // stage 5: restore keep protect table ok(must run before restoring code blocks, because table inline ok can contains IC/CB placeholder)
  r = restoreTableLines(r, tableStore)

  // stage 5.5: restore code blocks and inline elements
  r = restoreElements(r, store)

  // stage 6:Markdown showcase block two nth normalize
  r = normalizeMarkdownShowcaseBlocksJS(r)

  // stage 7: collapse table chunk inside blank line(must at most back, because front side rule ok can insert blank line)
  r = compactTableBlocksJS(r)

  return r
}

/**
 * fallback Markdown clean
 * 1. keep protect code block
 * 2. ** text ** → **text**(bold mark inside space clean)
 * 3. consecutive 3+ blank line → 2 blank line
 *
 * ⚠️ this function now does full normalization(with backend MarkdownNormalizer align),
 * name cleanMarkdown keep unchanged to stay compatible with existing call sites
 */
// cleanMarkdown already up level as complete whole normalize(with backend MarkdownNormalizer.normalize() align)
// the frontend also normalizes while streaming, ensure render one cause
function cleanMarkdown(text: string): string {
  return normalizeMarkdown(text)
}

// ═══════════════════════════════════════════════════════════════
// share component
// ═══════════════════════════════════════════════════════════════

/** code block */
function CodeBlock({ className, children }: { className?: string; children?: React.ReactNode }) {
  const { colors } = useThemeStore()
  const [copied, setCopied] = React.useState(false)
  const lang = className?.replace('language-', '') || 'text'
  const text = String(children || '').replace(/\n$/, '')

  const handleCopy = () => {
    navigator.clipboard.writeText(text)
    setCopied(true)
    setTimeout(() => setCopied(false), 1500)
  }

  return (
    <div className="relative group/code my-2" style={{ borderRadius: '6px', overflow: 'hidden', border: `1px solid ${colors.border}` }}>
      <div className="flex items-center justify-between px-3 py-1" style={{ backgroundColor: colors.bgSecondary, borderBottom: `1px solid ${colors.border}` }}>
        <span className="text-[10px] font-mono" style={{ color: colors.textDim }}>{lang}</span>
        <button onClick={handleCopy} className="opacity-0 group-hover/code:opacity-100 transition-opacity flex items-center gap-1 text-[10px]" style={{ color: colors.textSecondary }}>
          {copied ? '✓ Copied' : 'Copy'}
        </button>
      </div>
      <pre className="px-3 py-2.5 overflow-x-auto text-[11px] leading-relaxed max-w-full" style={{ backgroundColor: colors.bgPrimary, fontFamily: '"SF Mono", "JetBrains Mono", "Fira Code", monospace' }}>
        <code className={className}>{children}</code>
      </pre>
    </div>
  )
}

/** Markdown render */
export function MarkdownContent({ content, colors }: { content: string; colors: ReturnType<typeof useThemeStore.getState>['colors'] }) {
  const textColor = colors.text
  const linkColor = colors.accent

  // ⚠️ Hook rule:useMemo must at condition return front call
  const processedContent = useMemo(() => {
    if (!content || !content.trim()) return ''
    const normalized = cleanMarkdown(content)
    return normalized.replace(/(?<!\]\()(data:image\/[a-zA-Z]+;base64,[A-Za-z0-9+/=]{100,})/g, (match) => `![](${match})`)
  }, [content])

  if (!content || !content.trim()) return null

  return (
    <ReactMarkdown
      remarkPlugins={[remarkGfm]}
      rehypePlugins={[[rehypeHighlight, { languages: common, aliases: { vue: 'xml', ts: 'typescript', tsx: 'typescript', jsx: 'javascript' } }]]}
      components={{
        pre: ({ children }: { children?: React.ReactNode }) => <>{children}</>,
        code: ({ className, children }: { className?: string; children?: React.ReactNode }) => {
          const isBlock = className?.startsWith('language-') || (typeof children === 'string' && children.includes('\n'))
          if (isBlock) return <CodeBlock className={className}>{children}</CodeBlock>
          return <code className="px-1 py-0.5 rounded text-[12px]" style={{ backgroundColor: `${colors.border}30`, fontFamily: '"SF Mono", "JetBrains Mono", monospace', color: colors.text }}>{children}</code>
        },
        p: ({ children }: { children?: React.ReactNode }) => <p className="m-0 mb-2 last:mb-0 leading-relaxed">{children}</p>,
        a: ({ href, children }: { href?: string; children?: React.ReactNode }) => (
          <a href={href} target="_blank" rel="noopener noreferrer" className="underline cursor-pointer" style={{ color: linkColor }}>{children}</a>
        ),
        ul: ({ children }: { children?: React.ReactNode }) => <ul className="m-0 mb-2 pl-4 list-disc">{children}</ul>,
        ol: ({ children }: { children?: React.ReactNode }) => <ol className="m-0 mb-2 pl-4 list-decimal">{children}</ol>,
        li: ({ children }: { children?: React.ReactNode }) => <li className="m-0 mb-1">{children}</li>,
        h1: ({ children }: { children?: React.ReactNode }) => <h1 className="text-[15px] font-bold mt-3 mb-1.5" style={{ color: textColor }}>{children}</h1>,
        h2: ({ children }: { children?: React.ReactNode }) => <h2 className="text-[14px] font-bold mt-3 mb-1" style={{ color: textColor }}>{children}</h2>,
        h3: ({ children }: { children?: React.ReactNode }) => <h3 className="text-[13px] font-semibold mt-2.5 mb-1" style={{ color: textColor }}>{children}</h3>,
        h4: ({ children }: { children?: React.ReactNode }) => <h4 className="text-[12px] font-semibold mt-2 mb-0.5" style={{ color: textColor }}>{children}</h4>,
        blockquote: ({ children }: { children?: React.ReactNode }) => (
          <blockquote className="my-1.5 pl-3 py-1 rounded-r" style={{ borderLeft: `3px solid ${colors.accent}`, backgroundColor: `${colors.bgSecondary}80`, color: colors.textDim }}>{children}</blockquote>
        ),
        hr: () => <hr className="my-2 border-0" style={{ borderTop: `1px solid ${colors.border}40` }} />,
        table: ({ children }: { children?: React.ReactNode }) => (
          <div className="overflow-x-auto"><table className="my-2 w-full max-w-full text-[11px] border-collapse table-fixed" style={{ border: `1px solid ${colors.border}` }}>{children}</table></div>
        ),
        thead: ({ children }: { children?: React.ReactNode }) => <thead style={{ backgroundColor: colors.bgSecondary }}>{children}</thead>,
        tbody: ({ children }: { children?: React.ReactNode }) => <tbody>{children}</tbody>,
        tr: ({ children }: { children?: React.ReactNode }) => <tr>{children}</tr>,
        th: ({ children }: { children?: React.ReactNode }) => <th className="px-2 py-1 text-left font-semibold border" style={{ borderColor: colors.border, color: textColor }}>{children}</th>,
        td: ({ children }: { children?: React.ReactNode }) => <td className="px-2 py-1 border" style={{ borderColor: colors.border, color: textColor }}>{children}</td>,
        strong: ({ children }: { children?: React.ReactNode }) => <strong className="font-bold" style={{ color: textColor }}>{children}</strong>,
        em: ({ children }: { children?: React.ReactNode }) => <em style={{ color: colors.textSecondary }}>{children}</em>,
        img: ({ src, alt }: { src?: string; alt?: string }) => {
          if (!src || src.length < 100) return null
          return <img src={src} alt={alt || 'uploaded image'} className="max-w-full max-h-64 rounded-lg my-2 object-contain cursor-pointer" style={{ border: `1px solid ${colors.border}40` }} onClick={() => window.open(src, '_blank')} title="Click to zoom" />
        },
      }}
    >
      {processedContent}
    </ReactMarkdown>
  )
}

/** thinking process collapse chunk */
export function ThinkingBlock({ content, isStreaming }: { content: string; isStreaming: boolean }) {
  const { colors } = useThemeStore()
  const [open, setOpen] = React.useState(isStreaming)

  React.useEffect(() => {
    if (isStreaming) setOpen(true)
  }, [isStreaming])

  return (
    <details open={open} className="mb-3 rounded-lg overflow-hidden" style={{ backgroundColor: `${colors.bgSecondary}80`, border: `1px solid ${colors.border}40` }}>
      <summary className="flex items-center gap-2 px-3 py-2 cursor-pointer select-none list-none [&::-webkit-details-marker]:hidden transition-colors hover:bg-black/5"
        onClick={(e) => { e.preventDefault(); setOpen(!open) }}>
        <div className="w-4 h-4 rounded flex items-center justify-center shrink-0" style={{ backgroundColor: `${colors.accent}20` }}>
          {isStreaming ? (
            <svg className="w-3 h-3 animate-spin" style={{ color: colors.accent }} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2"><path d="M21 12a9 9 0 1 1-6.219-8.56" /></svg>
          ) : (
            <svg className="w-3 h-3" style={{ color: colors.accent }} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2"><polyline points="20 6 9 17 4 12" /></svg>
          )}
        </div>
        <span className="text-[11px] font-medium" style={{ color: colors.textSecondary }}>{isStreaming ? 'Thinking...' : 'Thinking'}</span>
        <div className="flex-1" />
        <svg className={`w-3.5 h-3.5 transition-transform ${open ? 'rotate-90' : ''}`} style={{ color: colors.textDim }} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2"><polyline points="6 9 12 15 18 9" /></svg>
      </summary>
      <div className="px-3 pb-3 pt-1 text-[12px] italic" style={{ color: colors.textSecondary }}>
        <ReactMarkdown
          remarkPlugins={[remarkGfm]}
          rehypePlugins={[[rehypeHighlight, { languages: common, aliases: { vue: 'xml', ts: 'typescript', tsx: 'typescript', jsx: 'javascript' } }]]}
          components={{
            code: ({ className: cn, children }: { className?: string; children?: React.ReactNode }) => <CodeBlock className={cn}>{children}</CodeBlock>,
            p: ({ children }: { children?: React.ReactNode }) => <p className="m-0 mb-2 last:mb-0 leading-relaxed">{children}</p>,
            ul: ({ children }: { children?: React.ReactNode }) => <ul className="m-0 mb-2 pl-4 list-disc">{children}</ul>,
            ol: ({ children }: { children?: React.ReactNode }) => <ol className="m-0 mb-2 pl-4 list-decimal">{children}</ol>,
            li: ({ children }: { children?: React.ReactNode }) => <li className="m-0 mb-1">{children}</li>,
          }}
        >
          {content}
        </ReactMarkdown>
      </div>
    </details>
  )
}

/** tool call step view image(form ok collapse) */
export function ToolCallView({ step, colors, compact }: { step: ReActStep; colors: ReturnType<typeof useThemeStore.getState>['colors']; compact?: boolean }) {
  const [expanded, setExpanded] = React.useState(false)
  const toolName = step.toolName || 'Tool'
  const label = extractToolLabel(step)
  const paramSummary = step.toolParams ? step.toolParams.substring(0, 80) : ''

  if (compact) {
    return (
      <div className="py-1 text-[11px]" style={{ color: colors.textSecondary }}>
        <div className="flex items-center gap-1.5">
          <span className="font-medium" style={{ color: colors.text }}>{toolName}</span>
          {step.status === 'in_progress' && <span className="w-1.5 h-1.5 rounded-full animate-pulse" style={{ backgroundColor: '#f59e0b' }} />}
          {step.status === 'success' && <span className="w-1.5 h-1.5 rounded-full" style={{ backgroundColor: '#22c55e' }} />}
          {step.status === 'failure' && <span className="w-1.5 h-1.5 rounded-full" style={{ backgroundColor: '#ef4444' }} />}
          {label !== toolName && <span className="text-[10px] opacity-70 truncate">· {label}</span>}
        </div>
        {paramSummary && (
          <div className="mt-0.5 font-mono text-[10px] truncate opacity-70" style={{ maxWidth: '100%' }}>{paramSummary}</div>
        )}
      </div>
    )
  }

  return (
    <div className="my-0.5">
      <button
        onClick={() => setExpanded(!expanded)}
        className="w-full flex items-center gap-1.5 px-2 py-1.5 text-left rounded transition-colors hover:bg-black/5"
      >
        {step.status === 'in_progress' && <span className="w-1.5 h-1.5 rounded-full animate-pulse shrink-0" style={{ backgroundColor: '#f59e0b' }} />}
        {step.status === 'success' && <span className="w-1.5 h-1.5 rounded-full shrink-0" style={{ backgroundColor: '#22c55e' }} />}
        {step.status === 'failure' && <span className="w-1.5 h-1.5 rounded-full shrink-0" style={{ backgroundColor: '#ef4444' }} />}
        <span className="text-[11px] font-medium shrink-0" style={{ color: colors.text }}>{toolName}</span>
        {label !== toolName && <span className="text-[11px] opacity-60 truncate">{label}</span>}
        {paramSummary && <span className="text-[10px] font-mono opacity-40 truncate hidden sm:inline">{paramSummary}</span>}
        <div className="flex-1" />
        <svg className={`w-3 h-3 transition-transform shrink-0 ${expanded ? 'rotate-90' : ''}`} style={{ color: colors.textDim }} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2"><polyline points="6 9 12 15 18 9" /></svg>
      </button>
      {expanded && (
        <div className="px-3 pb-2 text-[11px] space-y-1.5" style={{ color: colors.textSecondary }}>
          {step.toolParams && (
            <div>
              <span className="font-medium" style={{ color: colors.textDim }}>Args:</span>
              <pre className="mt-0.5 p-1.5 rounded text-[10px] overflow-x-auto" style={{ backgroundColor: colors.bgPrimary, fontFamily: '"SF Mono", "JetBrains Mono", monospace' }}>{step.toolParams}</pre>
            </div>
          )}
          {step.toolResult && (
            <div>
              <span className="font-medium" style={{ color: colors.textDim }}>Result:</span>
              <pre className="mt-0.5 p-1.5 rounded text-[10px] overflow-x-auto max-h-40" style={{ backgroundColor: colors.bgPrimary, fontFamily: '"SF Mono", "JetBrains Mono", monospace' }}>{step.toolResult.substring(0, 500)}</pre>
            </div>
          )}
          {step.error && (
            <div className="text-red-500">
              <span className="font-medium">Error:</span> {step.error}
            </div>
          )}
        </div>
      )}
    </div>
  )
}

/** copy button */
export function CopyButton({ text, colors }: { text: string; colors: ReturnType<typeof useThemeStore.getState>['colors'] }) {
  const [copied, setCopied] = React.useState(false)
  const handleCopy = () => {
    navigator.clipboard.writeText(text)
    setCopied(true)
    setTimeout(() => setCopied(false), 1500)
  }

  return (
    <button onClick={handleCopy} className="flex items-center gap-0.5 px-1 py-0.5 rounded transition-colors hover:opacity-70" style={{ color: colors.textDim }} title="Copy">
      {copied ? (
        <svg className="w-3 h-3 text-green-500" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2"><polyline points="20 6 9 17 4 12" /></svg>
      ) : (
        <svg className="w-3 h-3" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2"><rect x="9" y="9" width="13" height="13" rx="2" ry="2"/><path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"/></svg>
      )}
      <span className="text-[10px]">{copied ? 'Copied' : 'Copy'}</span>
    </button>
  )
}

/** STEP_COLORS */
export const STEP_COLORS: Record<string, string> = {
  thinking: '#a78bfa',
  tool_call: '#60a5fa',
  result: '#34d399',
}

/** from tool step group center estimate total elapsed */
export function formatDuration(groups: ToolGroup[]): string {
  const totalSteps = groups.reduce((sum, g) => sum + g.steps.length, 0)
  if (totalSteps === 0) return ''
  const estimatedMs = totalSteps * 1500
  if (estimatedMs < 1000) return `${estimatedMs}ms`
  if (estimatedMs < 60000) return `${(estimatedMs / 1000).toFixed(0)}s`
  return `${Math.floor(estimatedMs / 60000)}m${Math.round((estimatedMs % 60000) / 1000)}s`
}
