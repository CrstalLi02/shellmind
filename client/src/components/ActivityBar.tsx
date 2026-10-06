import { Fragment } from 'react'
import { useThemeStore } from '../stores/themeStore'

type TabId = 'servers' | 'files' | 'sftp' | 'local' | 'extensions'

interface ActivityBarProps {
  activeTab: TabId
  onTabChange: (tab: TabId) => void
  sidebarVisible: boolean
  onToggleSidebar: () => void
  onOpenSettings: () => void
}

const tabs: { id: TabId; icon: React.ReactElement; label: string }[] = [
  {
    id: 'local',
    label: 'Project',
    icon: (
      <svg className="w-5 h-5" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">
        <rect x="3.2" y="3.2" width="7.6" height="7.6" rx="2.4" />
        <rect x="3.2" y="13.2" width="7.6" height="7.6" rx="2.4" />
        <rect x="13.2" y="13.2" width="7.6" height="7.6" rx="2.4" />
        <rect x="13.4" y="3.4" width="7.2" height="7.2" rx="2.4" transform="rotate(45 17 7)" fill="currentColor" stroke="none" />
      </svg>
    ),
  },
  {
    id: 'servers',
    label: 'SSH servers',
    icon: (
      <svg className="w-5 h-5" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">
        <rect x="3" y="3.5" width="18" height="7" rx="2" />
        <rect x="3" y="13.5" width="18" height="7" rx="2" />
        <path d="M6.5 7h.01M6.5 17h.01" />
        <path d="M10 17.5h6" />
      </svg>
    ),
  },
  {
    id: 'files',
    label: 'Remote files',
    icon: (
      <svg className="w-5 h-5" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">
        <path d="M3 17.5a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2h4.3l2 2.3H17a2 2 0 0 1 2 2v1.1" />
        <path d="M23 16.2A5 5 0 1 0 13.2 18" />
        <path d="M17.4 21.5l2.5-2.5-2.5-2.5" />
        <path d="M12.5 19h7.4" />
      </svg>
    ),
  },
  {
    id: 'sftp',
    label: 'SFTP',
    icon: (
      <svg className="w-5 h-5" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">
        <path d="M18 9.2h-1.1A6.8 6.8 0 1 0 8.2 18h9.8a4.4 4.4 0 0 0 0-8.8z" />
        <path d="M9 13l3-3 3 3" />
        <path d="M12 10.5v7" />
      </svg>
    ),
  },
]

export function ActivityBar({ activeTab, onTabChange, sidebarVisible, onToggleSidebar, onOpenSettings }: ActivityBarProps) {
  const { colors } = useThemeStore()

  return (
    <div
      className="w-12 flex flex-col items-center py-2.5 flex-shrink-0"
      style={{ backgroundColor: colors.bgSecondary, borderRight: `1px solid ${colors.border}` }}
    >
      {/* Tab button */}
      <div className="flex flex-col gap-1.5">
        {tabs.map((tab) => {
          const active = activeTab === tab.id
          return (
            <Fragment key={tab.id}>
              <button
                onClick={() => {
                  if (activeTab === tab.id) onToggleSidebar()
                  else onTabChange(tab.id)
                }}
                className="w-10 h-10 flex items-center justify-center rounded-lg transition-all"
                style={{
                  color: active ? colors.text : colors.textSecondary,
                  backgroundColor: active ? colors.accentSoft : 'transparent',
                  border: `1px solid ${active ? `${colors.accent}26` : 'transparent'}`,
                }}
                title={activeTab === tab.id ? (sidebarVisible ? `Collapse ${tab.label}` : `Expand ${tab.label}`) : tab.label}
              >
                {tab.icon}
              </button>
              {tab.id === 'local' && (
                <div className="my-1 h-px w-6 flex-shrink-0" style={{ backgroundColor: colors.border }} />
              )}
            </Fragment>
          )
        })}
      </div>

      {/* bottom: settings */}
      <div className="mt-auto flex flex-col gap-1.5 pt-2">
        <button
          onClick={onOpenSettings}
          className="w-10 h-10 flex items-center justify-center rounded-lg transition-all"
          style={{ color: colors.textDim, border: '1px solid transparent' }}
          title="Settings"
        >
          <svg className="w-5 h-5" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
            <circle cx="12" cy="12" r="3"></circle>
            <path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 0 1 0 2.83 2 2 0 0 1-2.83 0l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 0 1-2 2 2 2 0 0 1-2-2v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 0 1-2.83 0 2 2 0 0 1 0-2.83l.06-.06A1.65 1.65 0 0 0 4.68 15a1.65 1.65 0 0 0-1.51-1H3a2 2 0 0 1-2-2 2 2 0 0 1 2-2h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 0 1 0-2.83 2 2 0 0 1 2.83 0l.06.06A1.65 1.65 0 0 0 9 4.68a1.65 1.65 0 0 0 1-1.51V3a2 2 0 0 1 2-2 2 2 0 0 1 2 2v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 0 1 2.83 0 2 2 0 0 1 0 2.83l-.06.06A1.65 1.65 0 0 0 19.4 9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 0 1 2 2 2 2 0 0 1-2 2h-.09a1.65 1.65 0 0 0-1.51 1z"></path>
          </svg>
        </button>
      </div>
    </div>
  )
}
