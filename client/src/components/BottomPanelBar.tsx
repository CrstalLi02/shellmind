/**
 * BottomPanelBar - bottom panel edge bar
 *
 * terminal collapse when show to up arrow head, click expand terminal
 * terminal expand back hide edge bar, show a down-arrow at the top of the terminal panel to collapse
 */
import { useThemeStore } from '../stores/themeStore'

interface BottomPanelBarProps {
  /** terminal whether ok see */
  terminalVisible: boolean
  /** switch terminal show */
  onToggleTerminal: () => void
}

export function BottomPanelBar({
  terminalVisible,
  onToggleTerminal,
}: BottomPanelBarProps) {
  const { colors } = useThemeStore()

  // terminal expand when hide bottom edge bar
  if (terminalVisible) {
    return null
  }

  // terminal collapse when show: left" terminal" text, right to down arrow head
  return (
    <div
      className="h-6 flex items-center justify-between px-3 select-none cursor-pointer transition-colors"
      style={{
        backgroundColor: colors.bgSecondary,
        borderTop: `1px solid ${colors.border}80`,
      }}
      onClick={onToggleTerminal}
      title="Expand terminal"
    >
      {/* left: terminal text */}
      <span className="text-xs font-medium transition-colors hover:text-[color:var(--text-primary)]" style={{ color: colors.textSecondary }}>
        Terminal
      </span>
      
      {/* right: to up arrow head(table show click back to up expand) */}
      <svg 
        className="w-4 h-4" 
        viewBox="0 0 24 24" 
        fill="none" 
        stroke="currentColor" 
        strokeWidth="2"
        style={{ color: colors.textSecondary }}
      >
        <polyline points="18 15 12 9 6 15"></polyline>
      </svg>
    </div>
  )
}
