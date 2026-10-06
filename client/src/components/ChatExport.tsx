import { useState } from 'react'
import { useThemeStore } from '../stores/themeStore'
import type { AgentMessage } from '../types'

interface ChatExportProps {
  open: boolean
  onClose: () => void
  messages: AgentMessage[]
  sessionTitle?: string
}

/**
 * chat lead out panel.
 * support Markdown / JSON two kind format, ok copy or down load.
 */
export function ChatExport({ open, onClose, messages, sessionTitle }: ChatExportProps) {
  const { colors } = useThemeStore()
  const [format, setFormat] = useState<'markdown' | 'json'>('markdown')
  const [copied, setCopied] = useState(false)

  if (!open) return null

  const title = sessionTitle || 'AI chat'

  // generate Markdown format
  const toMarkdown = (): string => {
    const lines: string[] = [`# ${title}`, '', `> Exported: ${new Date().toLocaleString('en-US')}`, '']
    for (const msg of messages) {
      const time = new Date(msg.timestamp).toLocaleString('en-US')
      if (msg.role === 'user') {
        lines.push(`## 👤 User (${time})`, '', msg.content, '')
      } else {
        // assistant message: prefer result step content
        const resultStep = msg.steps?.find(s => s.stepType === 'result' && s.content)
        const content = resultStep?.content || msg.content || ''
        if (content) {
          lines.push(`## 🤖 Assistant (${time})`, '', content, '')
        }
        // tool call summary
        const toolSteps = msg.steps?.filter(s => s.stepType === 'tool_call') || []
        if (toolSteps.length > 0) {
          lines.push('**Tool calls:**')
          for (const step of toolSteps) {
            const status = step.status === 'success' ? '✅' : step.status === 'failure' ? '❌' : '⏳'
            lines.push(`- ${status} \`${step.toolName || 'Tool'}\` ${step.toolParams ? `\`${step.toolParams.substring(0, 60)}\`` : ''}`)
          }
          lines.push('')
        }
      }
    }
    return lines.join('\n')
  }

  // generate JSON format
  const toJSON = (): string => {
    const data = {
      title,
      exportedAt: new Date().toISOString(),
      messageCount: messages.length,
      messages: messages.map(msg => ({
        role: msg.role,
        content: msg.content,
        timestamp: new Date(msg.timestamp).toISOString(),
        steps: msg.steps?.map(s => ({
          stepType: s.stepType,
          toolName: s.toolName,
          status: s.status,
          content: s.stepType === 'result' ? s.content : undefined,
        })),
      })),
    }
    return JSON.stringify(data, null, 2)
  }

  const content = format === 'markdown' ? toMarkdown() : toJSON()
  const fileExt = format === 'markdown' ? 'md' : 'json'
  const mimeType = format === 'markdown' ? 'text/markdown' : 'application/json'

  const handleCopy = () => {
    navigator.clipboard.writeText(content)
    setCopied(true)
    setTimeout(() => setCopied(false), 1500)
  }

  const handleDownload = () => {
    const blob = new Blob([content], { type: `${mimeType};charset=utf-8` })
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = `${title.replace(/[/\\?%*:|"<>]/g, '_')}.${fileExt}`
    a.click()
    URL.revokeObjectURL(url)
  }

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center"
      onClick={onClose}
    >
      <div
        className="absolute inset-0"
        style={{ backgroundColor: 'rgba(0,0,0,0.5)', backdropFilter: 'blur(4px)' }}
      />
      <div
        className="relative w-[560px] max-h-[80vh] flex flex-col rounded-xl shadow-2xl"
        style={{
          backgroundColor: colors.bgPrimary,
          border: `1px solid ${colors.border}`,
        }}
        onClick={(e) => e.stopPropagation()}
      >
        {/* title */}
        <div className="flex items-center justify-between px-5 py-4 shrink-0" style={{ borderBottom: `1px solid ${colors.border}` }}>
          <div className="flex items-center gap-2">
            <span className="text-base">📤</span>
            <h2 className="text-[14px] font-semibold" style={{ color: colors.text }}>Export chat</h2>
          </div>
          <button
            onClick={onClose}
            className="w-6 h-6 rounded-md flex items-center justify-center transition-colors hover:opacity-70"
            style={{ backgroundColor: colors.bgSecondary, color: colors.textDim }}
          >
            ✕
          </button>
        </div>

        {/* format select + action button */}
        <div className="flex items-center gap-3 px-5 py-3 shrink-0" style={{ borderBottom: `1px solid ${colors.border}40` }}>
          <div className="flex rounded-md overflow-hidden" style={{ border: `1px solid ${colors.border}` }}>
            <button
              onClick={() => setFormat('markdown')}
              className="px-3 py-1.5 text-[11px] font-medium transition-colors"
              style={{
                backgroundColor: format === 'markdown' ? `${colors.accent}15` : 'transparent',
                color: format === 'markdown' ? colors.accent : colors.textDim,
              }}
            >
              Markdown
            </button>
            <button
              onClick={() => setFormat('json')}
              className="px-3 py-1.5 text-[11px] font-medium transition-colors"
              style={{
                backgroundColor: format === 'json' ? `${colors.accent}15` : 'transparent',
                color: format === 'json' ? colors.accent : colors.textDim,
                borderLeft: `1px solid ${colors.border}`,
              }}
            >
              JSON
            </button>
          </div>
          <div className="flex-1" />
          <span className="text-[10px]" style={{ color: colors.textDim }}>
            {messages.length} messages · {(content.length / 1024).toFixed(1)} KB
          </span>
          <button
            onClick={handleCopy}
            className="px-3 py-1.5 rounded-md text-[11px] font-medium transition-colors hover:opacity-80"
            style={{ backgroundColor: colors.bgSecondary, color: colors.text, border: `1px solid ${colors.border}` }}
          >
            {copied ? '✓ Copied' : '📋 Copy'}
          </button>
          <button
            onClick={handleDownload}
            className="px-3 py-1.5 rounded-md text-[11px] font-medium transition-colors hover:opacity-80"
            style={{ backgroundColor: `${colors.accent}15`, color: colors.accent, border: `1px solid ${colors.accent}30` }}
          >
            💾 Download .{fileExt}
          </button>
        </div>

        {/* preview */}
        <div
          className="flex-1 overflow-auto px-5 py-3 min-h-0"
          style={{ maxHeight: '50vh' }}
        >
          <pre
            className="text-[11px] leading-relaxed whitespace-pre-wrap break-all"
            style={{
              color: colors.textSecondary,
              fontFamily: '"SF Mono", "JetBrains Mono", monospace',
            }}
          >
            {content.substring(0, 10000)}{content.length > 10000 ? '\n\n... (Preview truncated; download for the full file)' : ''}
          </pre>
        </div>
      </div>
    </div>
  )
}
