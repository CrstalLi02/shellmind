/**
 * TerminalTabBar - terminal panel tab bar
 *
 * at bottom panel inside switch" local terminal""SSH terminal"" output" three panel.
 */
import { useThemeStore } from '../stores/themeStore'

export type TerminalTabId = 'local' | 'ssh' | 'output'

interface TerminalTabBarProps {
  activeTab: TerminalTabId
  onTabChange: (tab: TerminalTabId) => void
  /** local terminal whether ok use */
  localAvailable?: boolean
  /** SSH terminal whether ok use */
  sshAvailable?: boolean
  /** output panel whether has content */
  hasOutput?: boolean
}

export function TerminalTabBar({
  activeTab,
  onTabChange,
  localAvailable = true,
  sshAvailable = true,
  hasOutput = false,
}: TerminalTabBarProps) {
  const { colors } = useThemeStore()

  const tabs: Array<{ id: TerminalTabId; label: string; icon: string; available: boolean; badge?: boolean }> = [
    { id: 'local', label: 'Local terminal', icon: '💻', available: localAvailable },
    { id: 'ssh', label: 'SSH terminal', icon: '🔗', available: sshAvailable },
    { id: 'output', label: 'Output', icon: '📋', available: true, badge: hasOutput },
  ]

  return (
    <div
      className="flex items-center gap-1 px-2 h-8 border-b flex-shrink-0"
      style={{ backgroundColor: colors.bgSecondary, borderColor: colors.border }}
    >
      {tabs.map((tab) => {
        const isActive = activeTab === tab.id
        return (
          <button
            key={tab.id}
            onClick={() => tab.available && onTabChange(tab.id)}
            disabled={!tab.available}
            className="px-3 py-1 rounded text-[11px] font-medium transition-colors flex items-center gap-1.5"
            style={{
              backgroundColor: isActive ? colors.accent + '20' : 'transparent',
              color: isActive ? colors.accent : tab.available ? colors.textDim : colors.textDim + '50',
              cursor: tab.available ? 'pointer' : 'not-allowed',
            }}
          >
            <span className="text-[10px]">{tab.icon}</span>
            {tab.label}
            {tab.badge && (
              <span
                className="w-1.5 h-1.5 rounded-full"
                style={{ backgroundColor: colors.accent }}
              />
            )}
          </button>
        )
      })}
    </div>
  )
}
