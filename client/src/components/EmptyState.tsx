import { useThemeStore } from '../stores/themeStore'

interface EmptyStateProps {
  onQuickAction?: (text: string) => void
}

interface CapabilityCard {
  icon: string
  title: string
  description: string
  prompt: string
}

const CAPABILITIES: CapabilityCard[] = [
  {
    icon: '🖥️',
    title: 'Server ops',
    description: 'Check system status, manage processes, analyze logs',
    prompt: 'Check server status and resource usage',
  },
  {
    icon: '🔍',
    title: 'Troubleshooting',
    description: 'Diagnose service failures and error logs',
    prompt: 'Investigate recent service errors',
  },
  {
    icon: '📄',
    title: 'File management',
    description: 'Browse files, inspect config, search content',
    prompt: 'List files in the current directory',
  },
  {
    icon: '🔒',
    title: 'Security check',
    description: 'Audit permissions and scan for security risks',
    prompt: 'Run a basic security check',
  },
  {
    icon: '📊',
    title: 'Performance',
    description: 'Analyze CPU/memory/disk bottlenecks',
    prompt: 'Analyze current server performance',
  },
  {
    icon: '🚀',
    title: 'Deploy',
    description: 'Build, deploy, and roll back',
    prompt: 'Check the latest deploy status',
  },
  {
    icon: '🏗️',
    title: 'Architecture',
    description: 'Analyze structure, dependencies, and code quality',
    prompt: "Analyze this project's architecture and module dependencies",
  },
  {
    icon: '🐛',
    title: 'Bugfix',
    description: 'Locate bugs, analyze root cause, propose fixes',
    prompt: 'Find bugs in this code and suggest fixes',
  },
  {
    icon: '✨',
    title: 'Code generation',
    description: 'Write features, complete implementations, add tests',
    prompt: 'Generate a REST API implementation',
  },
  {
    icon: '📝',
    title: 'Code review',
    description: 'Review diffs, flag issues, suggest improvements',
    prompt: 'Review recent code changes and suggest improvements',
  },
  {
    icon: '🔧',
    title: 'Config',
    description: 'App config, env vars, CI/CD pipelines',
    prompt: 'Review and tighten the current config',
  },
  {
    icon: '🐳',
    title: 'Containers',
    description: 'Docker images, Kubernetes deploys, container orchestration',
    prompt: 'Write a Dockerfile and deploy config for this project',
  },
]

/**
 * empty state boot page side.
 * show can force card + fast speed action, help new user up.
 */
export function EmptyState({ onQuickAction }: EmptyStateProps) {
  const { colors } = useThemeStore()

  return (
    <div className="flex flex-col items-center justify-center h-full px-6 py-8 overflow-y-auto">
      {/* Logo + title */}
      <div className="flex flex-col items-center mb-6">
        <div
          className="w-14 h-14 rounded-2xl flex items-center justify-center mb-3"
          style={{ backgroundColor: `${colors.accent}15`, border: `1px solid ${colors.accent}25` }}
        >
          <span className="text-2xl">🤖</span>
        </div>
        <h2 className="text-[15px] font-semibold mb-1" style={{ color: colors.text }}>
          AI DevOps assistant
        </h2>
        <p className="text-[12px] text-center max-w-[280px]" style={{ color: colors.textDim }}>
          From server ops to coding - full-stack DevOps in natural language
        </p>
      </div>

      {/* can force card net cell */}
      <div className="grid grid-cols-2 gap-2.5 w-full max-w-[480px] mb-6 max-h-[360px] overflow-y-auto pr-1">
        {CAPABILITIES.map((cap) => (
          <button
            key={cap.title}
            onClick={() => onQuickAction?.(cap.prompt)}
            className="flex items-start gap-2.5 p-3 rounded-lg text-left transition-all hover:scale-[1.02] active:scale-[0.98]"
            style={{
              backgroundColor: colors.bgSecondary,
              border: `1px solid ${colors.border}60`,
            }}
          >
            <span className="text-lg leading-none mt-0.5">{cap.icon}</span>
            <div className="min-w-0 flex-1">
              <div className="text-[12px] font-medium mb-0.5" style={{ color: colors.text }}>
                {cap.title}
              </div>
              <div className="text-[10px] leading-snug" style={{ color: colors.textDim }}>
                {cap.description}
              </div>
            </div>
          </button>
        ))}
      </div>

      {/* shortcut hint */}
      <div className="flex items-center gap-3 text-[10px]" style={{ color: colors.textDim }}>
        <span className="flex items-center gap-1">
          <kbd
            className="px-1.5 py-0.5 rounded text-[9px] font-mono"
            style={{ backgroundColor: colors.bgSecondary, border: `1px solid ${colors.border}`, color: colors.textSecondary }}
          >
            /
          </kbd>
          Command menu
        </span>
        <span className="flex items-center gap-1">
          <kbd
            className="px-1.5 py-0.5 rounded text-[9px] font-mono"
            style={{ backgroundColor: colors.bgSecondary, border: `1px solid ${colors.border}`, color: colors.textSecondary }}
          >
            ?
          </kbd>
          Shortcuts
        </span>
        <span className="flex items-center gap-1">
          <kbd
            className="px-1.5 py-0.5 rounded text-[9px] font-mono"
            style={{ backgroundColor: colors.bgSecondary, border: `1px solid ${colors.border}`, color: colors.textSecondary }}
          >
            @
          </kbd>
          Mention
        </span>
      </div>
    </div>
  )
}
