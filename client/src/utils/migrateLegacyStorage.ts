/**
 * item goal edit name(→ ShellMind) after localStorage one nth migrate.
 *
 * must at any what store module front execute(store at module load when i.e. read localStorage),
 * because this by main.tsx one ok so secondary as use way import.
 * only on new key not save at when copy old value, not delete old key, ok duplicate execute.
 */

const LEGACY_KEYS: Array<[legacy: string, current: string]> = [
  ['walissh_server_url', 'shellmind_server_url'],
  ['walissh_theme', 'shellmind_theme'],
  ['walicode_selected_model_id', 'shellmind_selected_model_id'],
  ['walicode-local-projects', 'shellmind-local-projects'],
  ['walicode-local-folder', 'shellmind-local-folder'],
  ['walicode-conversation-projects', 'shellmind-conversation-projects'],
  ['walicode-sidebar-width', 'shellmind-sidebar-width'],
]

try {
  for (const [legacy, current] of LEGACY_KEYS) {
    if (localStorage.getItem(current) !== null) continue
    const value = localStorage.getItem(legacy)
    if (value !== null) localStorage.setItem(current, value)
  }
} catch {
  // localStorage not ok use when silently ignore
}

export {}
