/**
 * theme switch via cross animation.
 * use View Transitions API(Chrome 111+) real current flat smooth theme switch.
 * unsupported when silently demote(no animation switch).
 */

/**
 * check browse preview widget whether support View Transitions API
 */
export function supportsViewTransitions(): boolean {
  return typeof document !== 'undefined' && 'startViewTransition' in document
}

/**
 * bar via cross animation theme switch.
 * at callback center fix edit theme status,API capture before/after snapshots and cross-fade.
 *
 * @param updateCallback - execute theme switch callback(like setTheme('dark'))
 * @param options - optional config
 * @param options.durationMs - via cross when long(default 300ms)
 * @param options.selector - param and via cross root select widget(default 'body')
 *
 * @example
 * ```tsx
 * animateThemeChange(() => setTheme(theme === 'dark' ? 'light' : 'dark'))
 * ```
 */
export function animateThemeChange(
  updateCallback: () => void,
  options?: { durationMs?: number; selector?: string }
): void {
  const durationMs = options?.durationMs ?? 300
  const selector = options?.selector ?? 'body'

  if (!supportsViewTransitions()) {
    // unsupported View Transitions → directly switch
    updateCallback()
    return
  }

  // use View Transitions API
  const transition = (document as any).startViewTransition(() => {
    updateCallback()
  })

  // from set meaning animation:root cross fork fade in fade out
  transition.ready.then(() => {
    const root = document.querySelector(selector)
    if (!root) return

    root.animate(
      [
        { opacity: 0.85, filter: 'brightness(0.95)' },
        { opacity: 1, filter: 'brightness(1)' },
      ],
      {
        duration: durationMs,
        easing: 'ease-out',
      }
    )
  }).catch(() => {
    // via cross fail silently ignore
  })
}
