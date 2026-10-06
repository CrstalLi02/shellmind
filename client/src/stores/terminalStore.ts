/**
 * terminal status manage - manage local terminal/SSH terminal active tab
 */
import { create } from 'zustand'
import type { TerminalTabId } from '../components/TerminalTabBar'

interface TerminalState {
  /** current active terminal tab */
  activeTerminalTab: TerminalTabId
  /** local terminal session ID */
  localPtySessionId: string | null
  /** SSH terminal session ID */
  sshTerminalSessionId: string | null
  /** bottom panel whether ok see */
  terminalVisible: boolean
  /** local terminal goal mark working directory(by" at terminal open in" equal in port settings) */
  localTerminalCwd: string | null
  /** cwd change count widget: each time" at terminal open in" increment, for drive action terminal cd */
  localTerminalNonce: number

  setActiveTerminalTab: (tab: TerminalTabId) => void
  setLocalPtySessionId: (id: string | null) => void
  setSshTerminalSessionId: (id: string | null) => void
  setTerminalVisible: (visible: boolean) => void
  /** open the given directory in the local terminal: switch tab + through know terminal cd */
  openLocalTerminalAt: (cwd: string) => void
}

export const useTerminalStore = create<TerminalState>((set) => ({
  activeTerminalTab: 'local',
  localPtySessionId: null,
  sshTerminalSessionId: null,
  terminalVisible: true,
  localTerminalCwd: null,
  localTerminalNonce: 0,

  setActiveTerminalTab: (tab) => set({ activeTerminalTab: tab }),
  setLocalPtySessionId: (id) => set({ localPtySessionId: id }),
  setSshTerminalSessionId: (id) => set({ sshTerminalSessionId: id }),
  setTerminalVisible: (visible) => set({ terminalVisible: visible }),
  openLocalTerminalAt: (cwd) =>
    set((state) => ({
      localTerminalCwd: cwd,
      localTerminalNonce: state.localTerminalNonce + 1,
      activeTerminalTab: 'local',
    })),
}))
