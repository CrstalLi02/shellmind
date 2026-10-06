/**
 * permission confirm Store
 *
 * manage backend send from permission_confirm event.
 * current PermissionGuard L2 rule hit CONFIRM level, backend send permission_confirm SSE event,
 * frontend popup PermissionConfirmModal, user confirm/ reject back round write result.
 *
 * blocking machine make:
 * 1. backend send permission_confirm event → frontend receive back push to pendingConfirmations
 * 2. PermissionConfirmModal render most early one bar confirm request
 * 3. user click" confirm execute" or" reject"→ call resolveConfirmation
 * 4. frontend via fetch POST /api/v1/permission/resolve round write result to backend
 * 5. backend receive result → continue/ center stop tool execute
 */

import { create } from 'zustand'
import { getAuthHeaders, getBaseUrl } from '../api/request'

export interface PermissionConfirmRequest {
  /** confirm request unique ID */
  confirmId: string
  /** tool name name */
  toolName: string
  /** tool params(command/ path etc) */
  toolArgs: string
  /** wind risk equal level */
  riskLevel: 'DENY' | 'CONFIRM' | 'ALLOW'
  /** wind risk raw because */
  reason: string
  /** timeout time(ms),0= no timeout */
  timeoutMs: number
  /** request to reach timestamp */
  arrivedAt: number
}

export interface PermissionState {
  /** pending confirmation queue(FIFO) */
  pending: PermissionConfirmRequest[]
  /** current display confirm request(queue first part) */
  current: PermissionConfirmRequest | null

  /** add one bar confirm request */
  pushConfirmation: (req: PermissionConfirmRequest) => void
  /** unbind decide current confirm(user already action) */
  resolveConfirmation: (confirmId: string, approved: boolean, modifiedArgs?: string) => void
  /** clear queue */
  clearAll: () => void
}

export const usePermissionStore = create<PermissionState>((set, get) => ({
  pending: [],
  current: null,

  pushConfirmation: (req) => {
    const { pending } = get()
    const newPending = [...pending, req]
    set({
      pending: newPending,
      current: newPending[0] || null,
    })
  },

  resolveConfirmation: (confirmId, approved, modifiedArgs) => {
    const { pending } = get()
    const newPending = pending.filter((p) => p.confirmId !== confirmId)

    // round write result to backend
    fetch(`${getBaseUrl()}/api/v1/permission/resolve`, {
      method: 'POST',
      headers: getAuthHeaders({ 'Content-Type': 'application/json' }),
      body: JSON.stringify({
        confirmId,
        approved,
        modifiedArgs: modifiedArgs || undefined,
      }),
    }).catch((err) => {
      console.error('[permissionStore] resolveConfirmation failed:', err)
    })

    set({
      pending: newPending,
      current: newPending[0] || null,
    })
  },

  clearAll: () => set({ pending: [], current: null }),
}))
