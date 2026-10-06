import { useRuntimeStore } from '../stores/runtimeStore'
import { useThemeStore } from '../stores/themeStore'

export function RuntimeStatusIndicator() {
  const { colors } = useThemeStore()
  const { status, starting, error } = useRuntimeStore()

  const state = starting || status.running && status.port === 0
    ? 'starting'
    : status.running
      ? 'ready'
      : 'stopped'

  const color = state === 'ready'
    ? colors.green
    : state === 'starting'
      ? colors.yellow
      : colors.textSecondary

  const label = state === 'ready'
    ? `Agent ${status.port}`
    : state === 'starting'
      ? 'Starting Agent'
      : 'Agent disconnected'

  return (
    <div
      className="flex items-center gap-2 h-7 px-2.5 rounded-full"
      style={{
        backgroundColor: `${color}14`,
        border: `1px solid ${color}30`,
      }}
      title={error || (status.running ? `Local Agent connected: http:// 127.0.0.1:${status.port}`: ' align at start local Agent')}
    >
      <span
        className={`w-1.5 h-1.5 rounded-full ${state === 'starting' ? 'animate-pulse' : ''}`}
        style={{ backgroundColor: color }}
      />
      <span className="text-[11px] leading-none font-medium" style={{ color }}>
        {label}
      </span>
    </div>
  )
}
