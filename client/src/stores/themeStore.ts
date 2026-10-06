import { create } from 'zustand'
import { animateThemeChange } from '../utils/themeTransition'

export type ThemeName = 'dark' | 'light' | 'midnight' | 'forest' | 'system'

export interface ThemeColors {
  bgPrimary: string       // main background #1e1e1e
  bgSecondary: string     // side bar back scene #252526
  bgTertiary: string      // control/ card #2d2d2d
  bgInput: string         // input box #1e1e1e
  bgHover: string         // hover state
  bgTitleBar: string      // title bar #323233
  border: string          // border #3c3c3c
  text: string            // body #e5e7eb
  textSecondary: string   // nth need text #9ca3af
  textDim: string         // weak text #6b7280
  accent: string          // primary color(blue)
  accentSoft: string      // primary color fade base
  userBubble: string      // user message bubble back scene(muted color)
  userBubbleText: string  // user message text color
  green: string           // success/ online
  red: string             // error
  yellow: string          // warning
}

export interface ThemeConfig {
  name: ThemeName
  label: string
  colors: ThemeColors
}

export const themes: Record<ThemeName, ThemeConfig> = {
  dark: {
    name: 'dark',
    label: 'VS Code Dark',
    colors: {
      bgPrimary: '#1e1e1e',
      bgSecondary: '#252526',
      bgTertiary: '#2d2d2d',
      bgInput: '#1e1e1e',
      bgHover: '#2a2d2e',
      bgTitleBar: '#323233',
      border: '#3c3c3c',
      text: '#e5e7eb',
      textSecondary: '#9ca3af',
      textDim: '#6b7280',
      accent: '#4f8af5',
      accentSoft: 'rgba(79,138,245,0.12)',
      userBubble: '#2a3a5c',
      userBubbleText: '#e0e8f4',
      green: '#3fb950',
      red: '#f85149',
      yellow: '#d29922',
    },
  },
  light: {
    name: 'light',
    label: 'Light',
    colors: {
      bgPrimary: '#ffffff',
      bgSecondary: '#f3f4f6',
      bgTertiary: '#e5e7eb',
      bgInput: '#ffffff',
      bgHover: '#ebedef',
      bgTitleBar: '#f0f0f0',
      border: '#d1d5db',
      text: '#111827',
      textSecondary: '#4b5563',
      textDim: '#9ca3af',
      accent: '#2563eb',
      accentSoft: 'rgba(37,99,235,0.10)',
      userBubble: '#4a6fa5',
      userBubbleText: '#ffffff',
      green: '#16a34a',
      red: '#dc2626',
      yellow: '#ca8a04',
    },
  },
  midnight: {
    name: 'midnight',
    label: 'GitHub Dark',
    colors: {
      bgPrimary: '#0d1117',
      bgSecondary: '#161b22',
      bgTertiary: '#21262d',
      bgInput: '#0d1117',
      bgHover: '#1c2129',
      bgTitleBar: '#161b22',
      border: '#30363d',
      text: '#e6edf3',
      textSecondary: '#8b949e',
      textDim: '#484f58',
      accent: '#58a6ff',
      accentSoft: 'rgba(88,166,255,0.12)',
      userBubble: '#1e3a5f',
      userBubbleText: '#dce8f4',
      green: '#3fb950',
      red: '#f85149',
      yellow: '#d29922',
    },
  },
  forest: {
    name: 'forest',
    label: 'Forest',
    colors: {
      bgPrimary: '#1a1f16',
      bgSecondary: '#222820',
      bgTertiary: '#2d332a',
      bgInput: '#1a1f16',
      bgHover: '#282e24',
      bgTitleBar: '#222820',
      border: '#3d4538',
      text: '#d4e4d8',
      textSecondary: '#8aaa90',
      textDim: '#5a6b5e',
      accent: '#4ade80',
      accentSoft: 'rgba(74,222,128,0.12)',
      userBubble: '#3f4a42',
      userBubbleText: '#eef2ee',
      green: '#22c55e',
      red: '#ef4444',
      yellow: '#eab308',
    },
  },
  system: {
    name: 'system',
    label: 'System',
    // placeholder - actual colors by resolveThemeColors() action state return
    colors: {} as ThemeColors,
  },
}

const THEME_STORAGE_KEY = 'shellmind_theme'

/** from localStorage read already save theme, default dark */
function getInitialTheme(): ThemeName {
  try {
    const saved = localStorage.getItem(THEME_STORAGE_KEY)
    if (saved && (saved in themes || saved === 'system')) {
      return saved as ThemeName
    }
  } catch {
    // localStorage not ok use when ignore
  }
  return 'dark'
}

/** follow any system unified when, based on prefers-color-scheme select actual theme */
function resolveSystemTheme(): 'dark' | 'light' {
  if (typeof window !== 'undefined' && window.matchMedia) {
    return window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light'
  }
  return 'dark'
}

/** get theme actual colors(system → dark/light) */
function resolveThemeColors(name: ThemeName): ThemeColors {
  if (name === 'system') {
    return themes[resolveSystemTheme()].colors
  }
  return themes[name].colors
}

/** get theme actual name(system → dark/light), for write DOM data-theme */
function resolveThemeName(name: ThemeName): 'dark' | 'light' {
  if (name === 'system') {
    return resolveSystemTheme()
  }
  return name as 'dark' | 'light'
}

/** actual theme name write document.documentElement data-theme props, for CSS select widget use */
function applyThemeToDOM(name: ThemeName) {
  if (typeof document === 'undefined') return
  const resolved = resolveThemeName(name)
  document.documentElement.dataset.theme = resolved
}

interface ThemeStore {
  currentTheme: ThemeName
  colors: ThemeColors
  setTheme: (name: ThemeName) => void
}

const initialTheme = getInitialTheme()
applyThemeToDOM(initialTheme)

export const useThemeStore = create<ThemeStore>((set) => ({
  currentTheme: initialTheme,
  colors: resolveThemeColors(initialTheme),

  setTheme: (name) => {
    // use View Transitions API real current flat smooth theme switch
    animateThemeChange(() => {
      try {
        localStorage.setItem(THEME_STORAGE_KEY, name)
      } catch {
        // localStorage not ok use when ignore
      }
      applyThemeToDOM(name)
      set({
        currentTheme: name,
        colors: resolveThemeColors(name),
      })
    })
  },
}))

// listen system unified theme change(current currentTheme === 'system' when auto switch)
if (typeof window !== 'undefined' && window.matchMedia) {
  const mediaQuery = window.matchMedia('(prefers-color-scheme: dark)')
  mediaQuery.addEventListener('change', () => {
    const { currentTheme } = useThemeStore.getState()
    if (currentTheme === 'system') {
      applyThemeToDOM('system')
      useThemeStore.setState({ colors: resolveThemeColors('system') })
    }
  })
}
