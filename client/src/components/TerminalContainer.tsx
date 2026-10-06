/**
 * TerminalContainer - terminal content widget
 *
 * unify manage local terminal,SSH terminal,AI output panel tab switch.
 */
import { useState, useEffect, useRef } from 'react'
import { useThemeStore } from '../stores/themeStore'
import { useConnectionStore } from '../stores/connectionStore'
import { useTerminalStore } from '../stores/terminalStore'
import { ConnectionStatus } from '../types'
import { TerminalTabBar, type TerminalTabId } from './TerminalTabBar'
import { LocalTerminal } from './LocalTerminal'
import { TerminalPanel } from './TerminalPanel'
import { OutputPanel } from './OutputPanel'
import { useOutputStore } from '../stores/outputStore'

interface TerminalContainerProps {
  /** terminal session change callback */
  onTerminalSessionChange?: (sessionId: string | null) => void
  /** whether keep hold terminal session(unmount when not close) */
  keepSessionOnUnmount?: boolean
  /** collapse terminal callback */
  onCloseTerminal?: () => void
}

export function TerminalContainer({
  onTerminalSessionChange,
  keepSessionOnUnmount = true,
  onCloseTerminal,
}: TerminalContainerProps) {
  const { colors } = useThemeStore()
  const { currentConnectionId, connections } = useConnectionStore()
  const { entries: outputEntries } = useOutputStore()
  const [activeTab, setActiveTab] = useState<TerminalTabId>('local')
  const localTerminalCwd = useTerminalStore((s) => s.localTerminalCwd)
  const localTerminalNonce = useTerminalStore((s) => s.localTerminalNonce)
  const prevLocalNonceRef = useRef(localTerminalNonce)

  const currentConn = connections.find((c) => c.id === currentConnectionId)
  const sshAvailable = !!currentConn && currentConn.status === ConnectionStatus.CONNECTED

  // " at terminal open in" trigger when switch to local terminal tab
  useEffect(() => {
    if (localTerminalNonce !== prevLocalNonceRef.current) {
      prevLocalNonceRef.current = localTerminalNonce
      setActiveTab('local')
    }
  }, [localTerminalNonce])

  // SSH disconnect when auto switch to local terminal
  useEffect(() => {
    if (!sshAvailable && activeTab === 'ssh') {
      setActiveTab('local')
    }
  }, [sshAvailable, activeTab])

  // SSH connection success when auto switch to SSH terminal
  useEffect(() => {
    if (sshAvailable && activeTab !== 'ssh') {
      setActiveTab('ssh')
    }
  }, [sshAvailable])

  return (
    <div className="h-full flex flex-col min-w-0" style={{ backgroundColor: colors.bgPrimary }}>
      <div className="flex items-center" style={{ borderBottom: `1px solid ${colors.border}` }}>
        <div className="flex-1">
          <TerminalTabBar
            activeTab={activeTab}
            onTabChange={setActiveTab}
            localAvailable={true}
            sshAvailable={sshAvailable}
            hasOutput={outputEntries.length > 0}
          />
        </div>
        {/* collapse terminal button */}
        {onCloseTerminal && (
          <button
            onClick={onCloseTerminal}
            className="h-8 px-2 flex items-center justify-center hover:opacity-80 transition-opacity"
            style={{ color: colors.textSecondary }}
            title="Collapse terminal"
          >
            <svg className="w-4 h-4" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
              <polyline points="6 9 12 15 18 9"></polyline>
            </svg>
          </button>
        )}
      </div>
      <div className="flex-1 overflow-hidden">
        {activeTab === 'local' && (
          <LocalTerminal
            cwd={localTerminalCwd ?? undefined}
            cwdNonce={localTerminalNonce}
            onSessionChange={onTerminalSessionChange}
          />
        )}
        {activeTab === 'ssh' && (
          <TerminalPanel
            onTerminalSessionChange={onTerminalSessionChange}
            keepSessionOnUnmount={keepSessionOnUnmount}
          />
        )}
        {activeTab === 'output' && <OutputPanel />}
      </div>
    </div>
  )
}
