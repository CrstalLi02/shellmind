import { useState, useEffect, useCallback, useRef } from 'react'
import { useThemeStore, themes, type ThemeName } from '../stores/themeStore'
import { useRuntimeStore } from '../stores/runtimeStore'
import { useModelStore } from '../stores/modelStore'
import { normalizeBaseUrl, revealModelKey, testModel, type ModelConfigPayload } from '../api/modelConfig'
import { invoke } from '@tauri-apps/api/core'
import { isSoundEnabled, setSoundEnabled } from '../utils/sound'

interface SettingsProps {
  open: boolean
  onClose: () => void
}

type Section = 'communication' | 'models' | 'appearance' | 'general' | 'cli' | 'about'

const SUPPORTED_COMPLETIONS_PATH = 'chat/completions'

export function Settings({ open, onClose }: SettingsProps) {
  const { currentTheme, setTheme } = useThemeStore()
  const runtime = useRuntimeStore()
  const { models, loading, error, fetchModels, saveModelConfig, removeModel } = useModelStore()

  // Local draft of each setting
  const [inputLang, setInputLang] = useState('English')
  const [inputFont, setInputFont] = useState('JetBrains Mono')
  const [inputFontSize, setInputFontSize] = useState(13)
  const [soundEnabled, setSoundEnabledState] = useState(isSoundEnabled())
  const [section, setSection] = useState<Section>('communication')

  // CLI tool status
  const [cliInstalled, setCliInstalled] = useState(false)
  const [cliLoading, setCliLoading] = useState<'idle' | 'installing' | 'uninstalling'>('idle')
  const [copied, setCopied] = useState(false)
  const [editingModel, setEditingModel] = useState<ModelConfigPayload>({
    name: '', baseUrl: '', apiKey: '', modelName: '', completionsPath: SUPPORTED_COMPLETIONS_PATH,
  })
  const [modelSaving, setModelSaving] = useState(false)
  const [modelTesting, setModelTesting] = useState(false)
  const [showApiKey, setShowApiKey] = useState(false)
  const [revealedApiKey, setRevealedApiKey] = useState<string | null>(null)
  const [modelMessage, setModelMessage] = useState<{ type: 'success' | 'error'; text: string } | null>(null)

  // ── ok drag shrink drop ──
  const dialogRef = useRef<HTMLDivElement>(null)
  const [size, setSize] = useState({ w: 960, h: 640 })
  const dragging = useRef(false)
  const dragStart = useRef({ x: 0, y: 0, w: 0, h: 0 })

  // sync store → local
  // check CLI register status
  useEffect(() => {
    invoke<boolean>('check_cli_installed').then(setCliInstalled).catch(() => setCliInstalled(false))
  }, [])

  useEffect(() => {
    if (open && section === 'models') {
      void fetchModels({ silent: true })
    }
  }, [open, section, fetchModels])

  // ── judge break whether has fix edit ──
  /** cancel: still raw all local status */
  const handleCancel = useCallback(() => {
    onClose()
  }, [onClose])

  // ── drag shrink drop logic edit ──
  const onResizeMouseDown = useCallback((e: React.MouseEvent) => {
    e.preventDefault()
    dragging.current = true
    dragStart.current = { x: e.clientX, y: e.clientY, w: size.w, h: size.h }
    const onMove = (ev: MouseEvent) => {
      if (!dragging.current) return
      const nw = Math.max(700, dragStart.current.w + ev.clientX - dragStart.current.x)
      const nh = Math.max(460, dragStart.current.h + ev.clientY - dragStart.current.y)
      setSize({ w: nw, h: nh })
    }
    const onUp = () => {
      dragging.current = false
      window.removeEventListener('mousemove', onMove)
      window.removeEventListener('mousemove', onMove)
      window.removeEventListener('mouseup', onUp)
    }
    window.addEventListener('mousemove', onMove)
    window.addEventListener('mouseup', onUp)
  }, [size])

  // ── ESC close ──
  useEffect(() => {
    if (!open) return
    const handler = (e: KeyboardEvent) => {
      if (e.key === 'Escape') handleCancel()
    }
    window.addEventListener('keydown', handler)
    return () => window.removeEventListener('keydown', handler)
  }, [open, handleCancel])

  /** copy shellmind-cli command */
  const handleCopyCli = useCallback(async () => {
    try {
      await navigator.clipboard.writeText('shellmind-cli')
      setCopied(true)
      setTimeout(() => setCopied(false), 1500)
    } catch {
      // demote: use execCommand
      const ta = document.createElement('textarea')
      ta.value = 'shellmind-cli'
      document.body.appendChild(ta)
      ta.select()
      try { document.execCommand('copy') } catch {}
      document.body.removeChild(ta)
      setCopied(true)
      setTimeout(() => setCopied(false), 1500)
    }
  }, [])

  const resetModelForm = useCallback(() => {
    setEditingModel({ name: '', baseUrl: '', apiKey: '', modelName: '', completionsPath: SUPPORTED_COMPLETIONS_PATH })
  }, [])

  const handleToggleApiKey = useCallback(async () => {
    if (!showApiKey && editingModel.id && revealedApiKey === null) {
      const existingModel = models.find(model => model.id === editingModel.id)
      if (existingModel?.hasApiKey) {
        const res = await revealModelKey(editingModel.id)
        if (res.code !== '0000') {
          if (res.code === '404' && existingModel.apiKeyMasked) {
            setRevealedApiKey(existingModel.apiKeyMasked)
          } else {
            setModelMessage({ type: 'error', text: res.info || 'Failed to reveal API key' })
            return
          }
        } else {
          setRevealedApiKey(res.data ?? '')
        }
      } else {
        setRevealedApiKey('')
      }
    }
    setShowApiKey(visible => !visible)
  }, [editingModel.id, models, revealedApiKey, showApiKey])

  const submitModel = useCallback(async () => {
    setModelMessage(null)
    setModelSaving(true)
    const saved = await saveModelConfig({ ...editingModel, baseUrl: normalizeBaseUrl(editingModel.baseUrl) })
    setModelSaving(false)
    if (saved) {
      resetModelForm()
      setModelMessage({ type: 'success', text: 'Model config saved' })
    } else {
      setModelMessage({ type: 'error', text: useModelStore.getState().error || 'Save failed' })
    }
  }, [editingModel, saveModelConfig, resetModelForm])

  const testModelConnection = useCallback(async () => {
    setModelMessage(null)
    setModelTesting(true)
    const res = await testModel({ ...editingModel, baseUrl: normalizeBaseUrl(editingModel.baseUrl) })
    setModelTesting(false)
    setModelMessage(res.code === '0000'
      ? { type: 'success', text: 'Connected' }
      : { type: 'error', text: res.info || 'Connection failed' })
  }, [editingModel])

  const handleDeleteModel = useCallback(async (id: number) => {
    const success = await removeModel(id)
    setModelMessage({ type: success ? 'success' : 'error', text: success ? 'Model deleted' : (useModelStore.getState().error || 'Delete failed') })
  }, [removeModel])

  const { colors } = useThemeStore()
  const themeList = (Object.entries(themes) as [ThemeName, typeof themes[ThemeName]][])

  if (!open) return null

  const sections: { id: Section; label: string; icon: string }[] = [
    { id: 'communication', label: 'Connection', icon: '⚙️' },
    { id: 'models', label: 'Models', icon: '🧠' },
    { id: 'appearance', label: 'Appearance', icon: '🎨' },
    { id: 'general', label: 'General', icon: '💻' },
    { id: 'cli', label: 'CLI', icon: '⌨️' },
    { id: 'about', label: 'About', icon: 'ℹ️' },
  ]

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center" style={{ backgroundColor: 'rgba(0,0,0,0.5)' }}>
      <div
        ref={dialogRef}
        className="rounded-xl shadow-2xl flex flex-col overflow-hidden relative select-none"
        style={{
          backgroundColor: colors.bgSecondary,
          border: `1px solid ${colors.border}`,
          width: size.w,
          height: size.h,
          minWidth: 700,
          minHeight: 460,
        }}
      >
        {/* ── top bar ── */}
        <div
          className="flex items-center justify-between px-6 py-3.5 shrink-0"
          style={{ backgroundColor: colors.bgPrimary, borderBottom: `1px solid ${colors.border}` }}
        >
          <span className="text-[14px] font-semibold" style={{ color: colors.text }}>Settings</span>
          <button
            onClick={handleCancel}
            className="w-7 h-7 rounded-full flex items-center justify-center text-sm hover:bg-white/10 transition-colors"
            style={{ color: colors.textDim }}
          >
            ✕
          </button>
        </div>

        {/* ── main: left lead navigate + right content ── */}
        <div className="flex flex-1 min-h-0">
          {/* left lead navigate */}
          <div
            className="w-56 p-4 border-r flex flex-col gap-1 shrink-0"
            style={{ borderColor: colors.border }}
          >
            {sections.map((item) => (
              <button
                key={item.id}
                onClick={() => setSection(item.id)}
                className="flex items-center gap-3 px-3.5 py-2.5 rounded-lg text-[13px] text-left transition-colors"
                style={{
                  backgroundColor: section === item.id ? colors.accentSoft : 'transparent',
                  color: section === item.id ? colors.accent : colors.textSecondary,
                  fontWeight: section === item.id ? 600 : 400,
                }}
              >
                <span className="text-[15px]">{item.icon}</span>
                {item.label}
              </button>
            ))}
          </div>

          {/* right content area */}
          <div className="flex-1 p-8 overflow-y-auto">
            {/* comms(original generic) */}
            {section === 'communication' && (
              <div className="space-y-6">
                <h2 className="text-[15px] font-semibold" style={{ color: colors.text }}>Connection</h2>

                {/* local Agent Runtime */}
                <div className="rounded-lg p-4" style={{ backgroundColor: colors.bgTertiary, border: `1px solid ${colors.border}` }}>
                  <div className="flex items-center justify-between">
                    <div>
                      <h3 className="text-[14px] font-semibold" style={{ color: colors.text }}>Local Agent</h3>
                      <p className="mt-1 text-[12px]" style={{ color: colors.textDim }}>
                        {runtime.status.running
                          ? `Running · http:// 127.0.0.1:${runtime.status.port}`
                          : runtime.starting ? 'Starting...' : 'Not running'}
                      </p>
                      {runtime.status.javaVersion && (
                        <p className="text-[12px]" style={{ color: colors.textDim }}>
                          Java {runtime.status.javaVersion}
                        </p>
                      )}
                    </div>
                    <button
                      onClick={() => void (runtime.status.running ? runtime.stop() : runtime.start())}
                      disabled={runtime.starting}
                      className="px-4 py-2 rounded-md text-[13px] font-medium transition-colors"
                      style={{
                        backgroundColor: runtime.status.running ? colors.bgInput : colors.accentSoft,
                        border: `1px solid ${colors.border}`,
                        color: runtime.status.running ? colors.red : colors.accent,
                      }}
                    >
                      {runtime.status.running ? 'Stop' : 'Start'}
                    </button>
                  </div>
                  {runtime.error && (
                    <p className="mt-3 text-[12px]" style={{ color: colors.red }}>{runtime.error}</p>
                  )}
                  {runtime.status.jarPath && (
                    <p className="mt-3 text-[12px] font-mono break-all" style={{ color: colors.textDim }}>
                      {runtime.status.jarPath}
                    </p>
                  )}
                </div>

                {/* language */}
                <div>
                  <label className="block text-[13px] mb-2" style={{ color: colors.textDim }}>Language</label>
                  <select
                    value={inputLang}
                    onChange={(e) => setInputLang(e.target.value)}
                    className="w-full px-3.5 py-2 rounded-md text-[13px] outline-none"
                    style={{ backgroundColor: colors.bgInput, border: `1px solid ${colors.border}`, color: colors.text }}
                  >
                    <option>English</option>
                    <option>English</option>
                  </select>
                </div>
              </div>
            )}

            {/* template type config */}
            {section === 'models' && (
              <div className="space-y-6">
                <div className="flex items-center justify-between">
                  <h2 className="text-[15px] font-semibold" style={{ color: colors.text }}>Model config</h2>
                  <button
                    onClick={() => { resetModelForm(); setShowApiKey(false); setModelMessage(null) }}
                    className="px-3 py-1.5 rounded-md text-[12px]"
                    style={{ backgroundColor: colors.bgTertiary, border: `1px solid ${colors.border}`, color: colors.textSecondary }}
                  >
                    Add model
                  </button>
                </div>

                {error && (
                  <p className="text-[12px]" style={{ color: colors.red }}>{error}</p>
                )}

                <div className="space-y-2">
                  {loading && <p className="text-[12px]" style={{ color: colors.textDim }}>Loading...</p>}
                  {models.map((model) => (
                    <div key={model.id} className="rounded-lg p-3 flex items-center justify-between gap-3" style={{ backgroundColor: colors.bgTertiary, border: `1px solid ${colors.border}` }}>
                      <div className="min-w-0">
                        <div className="text-[13px] font-medium truncate" style={{ color: colors.text }}>{model.name}</div>
                        <div className="text-[11px] truncate" style={{ color: colors.textDim }}>
                          {model.modelName} · {model.baseUrl}
                        </div>
                      </div>
                      <div className="flex items-center gap-2 shrink-0">
                        <button
                          onClick={() => {
                            resetModelForm()
                            setEditingModel({
                              id: model.id,
                              name: model.name,
                              baseUrl: model.baseUrl,
                              apiKey: '',
                              modelName: model.modelName,
                              completionsPath: SUPPORTED_COMPLETIONS_PATH,
                            })
                            setModelMessage(null)
                          }}
                          className="px-3 py-1.5 rounded-md text-[12px]"
                          style={{ backgroundColor: colors.bgInput, border: `1px solid ${colors.border}`, color: colors.accent }}
                        >
                          Edit
                        </button>
                        <button
                          onClick={() => void handleDeleteModel(model.id)}
                          className="px-3 py-1.5 rounded-md text-[12px]"
                          style={{ backgroundColor: colors.bgInput, border: `1px solid ${colors.border}`, color: colors.red }}
                        >
                          Delete
                        </button>
                      </div>
                    </div>
                  ))}
                </div>

                <div className="rounded-lg p-4 space-y-3" style={{ backgroundColor: colors.bgInput, border: `1px solid ${colors.border}` }}>
                  <h3 className="text-[13px] font-semibold" style={{ color: colors.text }}>
                    {editingModel.id ? 'Edit model' : 'Add model'}
                  </h3>
                  {editingModel.id && (
                    <p className="text-[11px]" style={{ color: colors.textDim }}>
                      Current key: {models.find(m => m.id === editingModel.id)?.apiKeyMasked || '****'}; leave blank to keep the current key.
                    </p>
                  )}
                  <div className="grid grid-cols-2 gap-3">
                    <label className="block space-y-1">
                      <span className="text-[12px]" style={{ color: colors.textDim }}>Display name</span>
                      <input value={editingModel.name} onChange={(e) => setEditingModel({ ...editingModel, name: e.target.value })} className="w-full px-3 py-2 rounded-md text-[13px]" style={{ backgroundColor: colors.bgTertiary, border: `1px solid ${colors.border}`, color: colors.text }} />
                    </label>
                    <label className="block space-y-1">
                      <span className="text-[12px]" style={{ color: colors.textDim }}>Model ID</span>
                      <input value={editingModel.modelName} onChange={(e) => setEditingModel({ ...editingModel, modelName: e.target.value })} placeholder="deepseek-v4-flash" className="w-full px-3 py-2 rounded-md text-[13px]" style={{ backgroundColor: colors.bgTertiary, border: `1px solid ${colors.border}`, color: colors.text }} />
                    </label>
                    <label className="block col-span-2 space-y-1">
                      <span className="text-[12px]" style={{ color: colors.textDim }}>Base URL</span>
                      <input value={editingModel.baseUrl} onChange={(e) => setEditingModel({ ...editingModel, baseUrl: e.target.value })} placeholder="https://api.openai.com/v1" className="w-full px-3 py-2 rounded-md text-[13px]" style={{ backgroundColor: colors.bgTertiary, border: `1px solid ${colors.border}`, color: colors.text }} />
                    </label>
                    <label className="block space-y-1">
                      <span className="text-[12px]" style={{ color: colors.textDim }}>API Key</span>
                      <div className="relative">
                        <input
                          type={showApiKey ? 'text' : 'password'}
                          value={showApiKey && editingModel.id && editingModel.apiKey === '' ? revealedApiKey ?? '' : editingModel.apiKey}
                          onChange={(e) => setEditingModel({ ...editingModel, apiKey: e.target.value })}
                          placeholder={editingModel.id ? 'Leave blank to keep the current key' : 'sk-...'}
                          className="w-full px-3 py-2 pr-10 rounded-md text-[13px]"
                          style={{ backgroundColor: colors.bgTertiary, border: `1px solid ${colors.border}`, color: colors.text }}
                        />
                        <button
                          type="button"
                          onClick={() => void handleToggleApiKey()}
                          aria-label={showApiKey ? 'Hide API key' : 'Show API key'}
                          className="absolute right-2 top-1/2 -translate-y-1/2 p-1 rounded"
                          style={{ color: colors.textDim }}
                        >
                          {showApiKey ? (
                            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                              <path d="M2 12s3.5-7 10-7 10 7 10 7-3.5 7-10 7-10-7-10-7Z" />
                              <circle cx="12" cy="12" r="3" />
                              <path d="m3 3 18 18" />
                            </svg>
                          ) : (
                            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                              <path d="M2 12s3.5-7 10-7 10 7 10 7-3.5 7-10 7-10-7-10-7Z" />
                              <circle cx="12" cy="12" r="3" />
                            </svg>
                          )}
                        </button>
                      </div>
                    </label>
                    <label className="block space-y-1">
                      <span className="text-[12px]" style={{ color: colors.textDim }}>Chat Completions Path</span>
                      <select
                        value={editingModel.completionsPath || SUPPORTED_COMPLETIONS_PATH}
                        onChange={(e) => setEditingModel({ ...editingModel, completionsPath: e.target.value })}
                        className="h-[38px] w-full px-3 py-2 rounded-md text-[13px] outline-none"
                        style={{ backgroundColor: colors.bgTertiary, border: `1px solid ${colors.border}`, color: colors.text }}
                      >
                        <option value={SUPPORTED_COMPLETIONS_PATH}>{SUPPORTED_COMPLETIONS_PATH}</option>
                      </select>
                    </label>
                  </div>
                  {modelMessage && (
                    <p className="text-[12px]" style={{ color: modelMessage.type === 'success' ? colors.green : colors.red }}>{modelMessage.text}</p>
                  )}
                  <div className="flex gap-2 pt-1">
                    <button
                      onClick={() => void submitModel()}
                      disabled={modelSaving}
                      className="px-4 py-2 rounded-md text-[13px] font-medium disabled:opacity-60"
                      style={{ backgroundColor: colors.accentSoft, border: `1px solid ${colors.border}`, color: colors.accent }}
                    >
                      {modelSaving ? 'Saving...' : 'Save'}
                    </button>
                    <button
                      onClick={() => void testModelConnection()}
                      disabled={modelTesting}
                      className="px-4 py-2 rounded-md text-[13px] font-medium disabled:opacity-60"
                      style={{ backgroundColor: colors.bgTertiary, border: `1px solid ${colors.border}`, color: colors.textSecondary }}
                    >
                      {modelTesting ? 'Testing...' : 'Test connection'}
                    </button>
                  </div>
                </div>
              </div>
            )}

            {/* appearance */}
            {section === 'appearance' && (
              <div className="space-y-6">
                <h2 className="text-[15px] font-semibold" style={{ color: colors.text }}>Appearance</h2>
                <div className="grid grid-cols-2 gap-4">
                  {themeList.map(([name, config]) => {
                    const previewColors = name === 'system' ? colors : config.colors
                    return (
                    <button
                      key={name}
                      onClick={() => setTheme(name)}
                      className="p-4 rounded-lg text-left transition-all border-2"
                      style={{
                        backgroundColor: previewColors.bgPrimary,
                        borderColor: currentTheme === name ? previewColors.accent : previewColors.border,
                      }}
                    >
                      <div className="flex gap-1.5 mb-3">
                        {[previewColors.bgPrimary, previewColors.bgSecondary, previewColors.accent, previewColors.green].map((c, i) => (
                          <div key={i} className="w-5 h-5 rounded-full border" style={{ backgroundColor: c, borderColor: previewColors.border }} />
                        ))}
                      </div>
                      <span className="text-[13px] font-medium" style={{ color: previewColors.text }}>{config.label}</span>
                      {currentTheme === name && (
                        <span className="ml-2 text-xs" style={{ color: previewColors.accent }}>✓</span>
                      )}
                    </button>
                    )
                  })}
                </div>
              </div>
            )}

            {/* generic(original terminal) */}
            {section === 'general' && (
              <div className="space-y-6">
                <h2 className="text-[15px] font-semibold" style={{ color: colors.text }}>General</h2>

                {/* font */}
                <div>
                  <label className="block text-[13px] mb-2" style={{ color: colors.textDim }}>Font</label>
                  <select
                    value={inputFont}
                    onChange={(e) => setInputFont(e.target.value)}
                    className="w-full px-3.5 py-2 rounded-md text-[13px] outline-none"
                    style={{ backgroundColor: colors.bgInput, border: `1px solid ${colors.border}`, color: colors.text }}
                  >
                    <option>JetBrains Mono</option>
                    <option>Fira Code</option>
                    <option>Menlo</option>
                    <option>Source Code Pro</option>
                  </select>
                </div>

                {/* font size */}
                <div>
                  <label className="block text-[13px] mb-2" style={{ color: colors.textDim }}>Font size</label>
                  <div className="flex items-center gap-4">
                    <input
                      type="range"
                      min="10"
                      max="24"
                      value={inputFontSize}
                      onChange={(e) => setInputFontSize(Number(e.target.value))}
                      className="flex-1"
                    />
                    <span className="text-[13px] w-8 text-right tabular-nums" style={{ color: colors.text }}>{inputFontSize}px</span>
                  </div>
                </div>

                {/* done hint sound(i.e. when create effect) */}
                <div className="flex items-center justify-between">
                  <div>
                    <label className="block text-[13px] mb-1" style={{ color: colors.textDim }}>Completion sound</label>
                    <p className="text-[12px]" style={{ color: colors.textDim }}>Play a short sound when the AI finishes or errors</p>
                  </div>
                  <button
                    role="switch"
                    aria-checked={soundEnabled}
                    onClick={() => {
                      const next = !soundEnabled
                      setSoundEnabledState(next)
                      setSoundEnabled(next)
                    }}
                    className="relative w-10 h-6 rounded-full transition-colors flex-shrink-0"
                    style={{ backgroundColor: soundEnabled ? colors.accent : colors.bgTertiary }}
                  >
                    <span
                      className="absolute top-0.5 w-5 h-5 rounded-full bg-white transition-all"
                      style={{ left: soundEnabled ? '20px' : '2px' }}
                    />
                  </button>
                </div>
              </div>
            )}

            {/* CLI tool(alone set bar goal) */}
            {section === 'cli' && (
              <div className="space-y-6">
                <h2 className="text-[15px] font-semibold" style={{ color: colors.text }}>CLI</h2>

                {/* settings area */}
                <div
                  className="p-4 rounded-lg space-y-3"
                  style={{ backgroundColor: colors.bgInput, border: `1px solid ${colors.border}` }}
                >
                  <div className="flex items-center gap-3">
                    <span className="text-[14px] font-semibold" style={{ color: colors.text }}>shellmind-cli</span>
                    {cliInstalled ? (
                      <span
                        className="px-2 py-0.5 rounded text-[11px] font-medium"
                        style={{ backgroundColor: colors.green + '22', color: colors.green }}
                      >
                        Registered
                      </span>
                    ) : (
                      <span
                        className="px-2 py-0.5 rounded text-[11px] font-medium"
                        style={{ backgroundColor: colors.textDim + '22', color: colors.textDim }}
                      >
                        Not registered
                      </span>
                    )}
                  </div>
                  <p className="text-[12px]" style={{ color: colors.textDim }}>
                    Put <code className="px-1 py-0.5 rounded text-[11px]" style={{ backgroundColor: colors.bgSecondary, color: colors.accent }}>shellmind-cli</code> on PATH so you can launch the app from a terminal.
                  </p>
                  <div className="flex items-center gap-2 pt-1">
                    {cliInstalled ? (
                      <button
                        onClick={async () => {
                          setCliLoading('uninstalling')
                          try {
                            await invoke<string>('uninstall_cli_command')
                            setCliInstalled(false)
                          } catch (e) {
                            alert(`Unregister failed: ${e}. Admin permission may be required.`)
                          }
                          setCliLoading('idle')
                        }}
                        disabled={cliLoading !== 'idle'}
                        className="px-4 py-1.5 rounded-md text-[12px] font-medium transition-colors"
                        style={{
                          backgroundColor: colors.bgTertiary,
                          border: `1px solid ${colors.border}`,
                          color: colors.textSecondary,
                          cursor: cliLoading !== 'idle' ? 'not-allowed' : 'pointer',
                        }}
                      >
                        {cliLoading === 'uninstalling' ? 'Removing...' : 'Unregister'}
                      </button>
                    ) : (
                      <button
                        onClick={async () => {
                          setCliLoading('installing')
                          try {
                            await invoke<string>('install_cli_command')
                            setCliInstalled(true)
                          } catch (e) {
                            alert(`Register failed: ${e}. Admin permission may be required.`)
                          }
                          setCliLoading('idle')
                        }}
                        disabled={cliLoading !== 'idle'}
                        className="px-4 py-1.5 rounded-md text-[12px] font-medium transition-colors"
                        style={{
                          backgroundColor: cliLoading === 'idle' ? colors.accent : colors.bgTertiary,
                          color: cliLoading === 'idle' ? '#fff' : colors.textDim,
                          cursor: cliLoading !== 'idle' ? 'not-allowed' : 'pointer',
                        }}
                      >
                        {cliLoading === 'installing' ? 'Installing...' : 'Install'}
                      </button>
                    )}
                  </div>
                </div>

                {/* use notes area */}
                <div
                  className="p-4 rounded-lg space-y-3"
                  style={{ backgroundColor: colors.bgInput, border: `1px solid ${colors.border}` }}
                >
                  <h3 className="text-[13px] font-semibold" style={{ color: colors.text }}>How to use</h3>
                  <p className="text-[13px]" style={{ color: colors.textSecondary }}>
                    Install <code className="px-1 py-0.5 rounded text-[11px]" style={{ backgroundColor: colors.bgSecondary, color: colors.accent }}>shellmind-cli</code> , then run this from any terminal:
                  </p>

                  {/* command block: click whole chunk copy */}
                  <button
                    onClick={handleCopyCli}
                    className="w-full flex items-center justify-between gap-2 px-3.5 py-2.5 rounded-md text-left transition-colors group hover:brightness-110"
                    style={{
                      backgroundColor: colors.bgSecondary,
                      border: `1px solid ${colors.border}`,
                    }}
                  >
                    <code className="text-[13px] font-mono" style={{ color: colors.text }}>
                      $ shellmind-cli
                    </code>
                    <span
                      className="text-[11px] font-medium shrink-0 transition-colors"
                      style={{ color: copied ? colors.green : colors.textDim }}
                    >
                      {copied ? '✓ Copied' : 'Click to copy'}
                    </span>
                  </button>

                  <ul className="text-[12px] space-y-1.5 pt-1" style={{ color: colors.textDim }}>
                    <li>• From any terminal, run <code style={{ color: colors.accent }}>shellmind-cli</code> to launch ShellMind</li>
                    <li>• macOS / Linux (writes to <code style={{ color: colors.accent }}>~/.local/bin</code>)</li>
                    <li>• Restart the terminal or run <code style={{ color: colors.accent }}>source ~/.zshrc</code> for PATH to apply</li>
                  </ul>
                </div>
              </div>
            )}

            {/* about */}
            {section === 'about' && (
              <div className="flex flex-col items-center py-10">
                <img src="/logo.png" alt="ShellMind-Study" className="w-20 h-20 rounded-xl mb-4" />
                <h3 className="text-lg font-semibold mb-1" style={{ color: colors.text }}>ShellMind-Study</h3>
                <p className="text-[13px] mb-6" style={{ color: colors.textDim }}>v0.1.0 · AI + SSH terminal</p>
                <div className="w-full max-w-sm p-4 rounded-lg text-[13px] space-y-3" style={{ backgroundColor: colors.bgInput }}>
                  {[
                    ['Frontend', 'Tauri 2.0 + React 19'],
                    ['Backend', 'Spring AI + Google ADK'],
                    ['Build', 'Vite 7 + TypeScript'],
                  ].map(([k, v]) => (
                    <div key={k} className="flex justify-between">
                      <span style={{ color: colors.textDim }}>{k}</span>
                      <span style={{ color: colors.textSecondary }}>{v}</span>
                    </div>
                  ))}
                </div>
              </div>
            )}
          </div>
        </div>

        {/* ── bottom bar ── */}
        <div
          className="flex items-center justify-end gap-3 px-6 py-3.5 shrink-0"
          style={{ backgroundColor: colors.bgPrimary, borderTop: `1px solid ${colors.border}` }}
        >
          <button
            onClick={handleCancel}
            className="px-5 py-2 rounded-md text-[13px] font-medium transition-colors"
            style={{
              backgroundColor: colors.bgTertiary,
              border: `1px solid ${colors.border}`,
              color: colors.textSecondary,
            }}
          >
            Close
          </button>
        </div>

        {/* ── right down corner drag shrink drop handle ── */}
        <div
          onMouseDown={onResizeMouseDown}
          className="absolute bottom-0 right-0 w-5 h-5 cursor-se-resize flex items-end justify-end p-0.5"
          style={{ color: colors.textDim }}
        >
          <svg width="10" height="10" viewBox="0 0 10 10" fill="currentColor">
            <circle cx="8" cy="2" r="1" />
            <circle cx="8" cy="5" r="1" />
            <circle cx="5" cy="5" r="1" />
            <circle cx="8" cy="8" r="1" />
            <circle cx="5" cy="8" r="1" />
            <circle cx="2" cy="8" r="1" />
          </svg>
        </div>
      </div>
    </div>
  )
}
