import { useState, useEffect, useCallback } from 'react'
import { useThemeStore } from '../stores/themeStore'
import { useConnectionStore } from '../stores/connectionStore'
import { AuthType } from '../types'
import type { SshConnectionPayload } from '../api/sshConnection'

interface SSHConnectionModalProps {
  open: boolean
  onClose: () => void
  /** in edit mode, pass existing connection data */
  editingConnection?: {
    id: string
    name: string
    host: string
    port: number
    username: string
    authType: number
  } | null
}

type AuthMethod = 'password' | 'privateKey'

export function SSHConnectionModal({ open, onClose, editingConnection }: SSHConnectionModalProps) {
  const { colors } = useThemeStore()
  const { createConnection, updateConnection } = useConnectionStore()
  const isEditing = !!editingConnection

  const [tab, setTab] = useState<'basic' | 'auth' | 'advanced'>('basic')
  const [authMethod, setAuthMethod] = useState<AuthMethod>('password')

  // Basic
  const [name, setName] = useState('')
  const [host, setHost] = useState('')
  const [port, setPort] = useState(22)

  // Auth
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [privateKey, setPrivateKey] = useState('')

  // Advanced
  const [connectionTimeout, setConnectionTimeout] = useState(30)
  const [keepAliveInterval, setKeepAliveInterval] = useState(60)
  const [enableCompression, setEnableCompression] = useState(false)
  const [startupCommand, setStartupCommand] = useState('')

  // Submission
  const [submitting, setSubmitting] = useState(false)

  // Validation
  const [errors, setErrors] = useState<Record<string, string>>({})

  // edit mode when, use useEffect fill fill table form
  useEffect(() => {
    if (open && editingConnection) {
      setName(editingConnection.name)
      setHost(editingConnection.host)
      setPort(editingConnection.port)
      setUsername(editingConnection.username)
      setAuthMethod(editingConnection.authType === 2 ? 'privateKey' : 'password')
      setPassword('')
      setPrivateKey('')
      setTab('basic')
    } else if (open && !editingConnection) {
      // new mode, reset table form
      setName('')
      setHost('')
      setPort(22)
      setUsername('')
      setPassword('')
      setPrivateKey('')
      setAuthMethod('password')
      setTab('basic')
    }
  }, [open, editingConnection])

  const validate = useCallback(() => {
    const errs: Record<string, string> = {}
    if (!name.trim()) errs.name = 'Connection name is required'
    if (!host.trim()) errs.host = 'Host is required'
    if (!username.trim()) errs.username = 'Username is required'
    if (authMethod === 'password' && !password) errs.password = 'Password is required'
    if (authMethod === 'privateKey' && !privateKey.trim()) errs.privateKey = 'Private key is required'
    if (port < 1 || port > 65535) errs.port = 'Invalid port'
    setErrors(errs)
    return Object.keys(errs).length === 0
  }, [name, host, username, password, privateKey, port, authMethod])

  const handleConnect = useCallback(async () => {
    if (!validate()) return

    setSubmitting(true)

    const payload: SshConnectionPayload = {
      connectionId: editingConnection?.id,
      connectionName: name.trim(),
      host: host.trim(),
      port,
      username: username.trim(),
      authType: authMethod === 'password' ? AuthType.PASSWORD : AuthType.PRIVATE_KEY,
      password: authMethod === 'password' && password ? password : undefined,
      privateKey: authMethod === 'privateKey' ? privateKey : undefined,
      userId: 'default',
      connectTimeout: connectionTimeout,
      keepaliveInterval: keepAliveInterval,
      startupCommand: startupCommand || undefined,
      compression: enableCompression,
      strictHostKeyCheck: true,
    }

    const ok = isEditing
      ? await updateConnection(payload)
      : await createConnection(payload)
    setSubmitting(false)

    if (ok) {
      onClose()
    }
    // on failure error stored store, popup window keep hold open
  }, [validate, name, host, port, username, password, privateKey, authMethod, connectionTimeout, keepAliveInterval, startupCommand, enableCompression, createConnection, updateConnection, editingConnection, isEditing, onClose])

  // ESC to close
  useEffect(() => {
    if (!open) return
    const handler = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose()
    }
    window.addEventListener('keydown', handler)
    return () => window.removeEventListener('keydown', handler)
  }, [open, onClose])

  if (!open) return null

  const tabs: { id: 'basic' | 'auth' | 'advanced'; label: string }[] = [
    { id: 'basic', label: 'Basics' },
    { id: 'auth', label: 'Auth' },
    { id: 'advanced', label: 'Advanced' },
  ]

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center"
      style={{ backgroundColor: 'rgba(0,0,0,0.6)' }}
      onMouseDown={(e) => {
        if (e.target === e.currentTarget) onClose()
      }}
    >
      {/* Modal */}
      <div
        className="w-[640px] max-h-[85vh] rounded-xl shadow-2xl flex flex-col overflow-hidden"
        style={{ backgroundColor: colors.bgSecondary, border: `1px solid ${colors.border}` }}
      >
        {/* Header */}
        <div
          className="flex items-center justify-between px-6 py-4 flex-shrink-0"
          style={{ borderBottom: `1px solid ${colors.border}` }}
        >
          <div className="flex items-center gap-3">
            <div
              className="w-8 h-8 rounded-lg flex items-center justify-center"
              style={{ backgroundColor: `${colors.accent}20` }}
            >
              <svg className="w-4 h-4" style={{ color: colors.accent }} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                <rect x="2" y="3" width="20" height="14" rx="2" ry="2"></rect>
                <line x1="8" y1="21" x2="16" y2="21"></line>
                <line x1="12" y1="17" x2="12" y2="21"></line>
              </svg>
            </div>
            <div>
              <h2 className="text-sm font-semibold" style={{ color: colors.text }}>{isEditing ? 'Edit SSH connection' : 'New SSH connection'}</h2>
              <p className="text-[11px]" style={{ color: colors.textDim }}>{isEditing ? 'Edit server connection settings' : 'Configure the server connection'}</p>
            </div>
          </div>
          <button
            onClick={onClose}
            className="w-7 h-7 rounded-full flex items-center justify-center transition-colors"
            style={{ color: colors.textDim, backgroundColor: 'transparent' }}
            onMouseEnter={(e) => { e.currentTarget.style.backgroundColor = `${colors.textDim}20` }}
            onMouseLeave={(e) => { e.currentTarget.style.backgroundColor = 'transparent' }}
          >
            ✕
          </button>
        </div>

        {/* Tabs */}
        <div
          className="flex px-6 pt-4 pb-0 flex-shrink-0"
          style={{ borderBottom: `1px solid ${colors.border}` }}
        >
          {tabs.map((t) => (
            <button
              key={t.id}
              onClick={() => setTab(t.id)}
              className="px-4 py-2.5 text-[13px] font-medium transition-colors relative"
              style={{
                color: tab === t.id ? colors.accent : colors.textSecondary,
              }}
            >
              {t.label}
              {tab === t.id && (
                <div
                  className="absolute bottom-0 left-2 right-2 h-0.5 rounded-full"
                  style={{ backgroundColor: colors.accent }}
                />
              )}
            </button>
          ))}
        </div>

        {/* Content */}
        <div className="flex-1 overflow-y-auto p-6">
          {tab === 'basic' && (
            <div className="space-y-5">
              {/* connection name */}
              <div>
                <label className="flex items-center gap-1.5 text-[12px] mb-2" style={{ color: colors.textSecondary }}>
                  <span>Connection name</span>
                  <span className="text-[10px] px-1 py-0.5 rounded" style={{ backgroundColor: colors.accentSoft, color: colors.accent }}>Required</span>
                </label>
                <input
                  type="text"
                  value={name}
                  onChange={(e) => setName(e.target.value)}
                  placeholder="e.g. Production"
                  className="w-full px-4 py-2.5 rounded-lg text-[13px] outline-none transition-all"
                  style={{
                    backgroundColor: colors.bgInput,
                    border: `1px solid ${errors.name ? colors.red : colors.border}`,
                    color: colors.text,
                  }}
                  onFocus={(e) => { e.currentTarget.style.borderColor = colors.accent }}
                  onBlur={(e) => { e.currentTarget.style.borderColor = errors.name ? colors.red : colors.border }}
                />
                {errors.name && <p className="text-[11px] mt-1" style={{ color: colors.red }}>{errors.name}</p>}
              </div>

              {/* host address */}
              <div>
                <label className="flex items-center gap-1.5 text-[12px] mb-2" style={{ color: colors.textSecondary }}>
                  <span>Host</span>
                  <span className="text-[10px] px-1 py-0.5 rounded" style={{ backgroundColor: colors.accentSoft, color: colors.accent }}>Required</span>
                </label>
                <input
                  type="text"
                  value={host}
                  onChange={(e) => setHost(e.target.value)}
                  placeholder="192.168.1.100 or example.com"
                  className="w-full px-4 py-2.5 rounded-lg text-[13px] outline-none transition-all"
                  style={{
                    backgroundColor: colors.bgInput,
                    border: `1px solid ${errors.host ? colors.red : colors.border}`,
                    color: colors.text,
                  }}
                  onFocus={(e) => { e.currentTarget.style.borderColor = colors.accent }}
                  onBlur={(e) => { e.currentTarget.style.borderColor = errors.host ? colors.red : colors.border }}
                />
                {errors.host && <p className="text-[11px] mt-1" style={{ color: colors.red }}>{errors.host}</p>}
              </div>

              {/* port */}
              <div>
                <label className="text-[12px] mb-2 block" style={{ color: colors.textSecondary }}>Port</label>
                <input
                  type="number"
                  value={port}
                  onChange={(e) => setPort(parseInt(e.target.value) || 22)}
                  className="w-36 px-4 py-2.5 rounded-lg text-[13px] outline-none transition-all"
                  style={{
                    backgroundColor: colors.bgInput,
                    border: `1px solid ${errors.port ? colors.red : colors.border}`,
                    color: colors.text,
                  }}
                  onFocus={(e) => { e.currentTarget.style.borderColor = colors.accent }}
                  onBlur={(e) => { e.currentTarget.style.borderColor = errors.port ? colors.red : colors.border }}
                />
                {errors.port && <p className="text-[11px] mt-1" style={{ color: colors.red }}>{errors.port}</p>}
              </div>

              {/* hint */}
              <div
                className="flex items-start gap-3 p-4 rounded-lg"
                style={{ backgroundColor: `${colors.accent}10`, border: `1px solid ${colors.accent}30` }}
              >
                <svg className="w-4 h-4 mt-0.5 flex-shrink-0" style={{ color: colors.accent }} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                  <circle cx="12" cy="12" r="10"></circle>
                  <line x1="12" y1="16" x2="12" y2="12"></line>
                  <line x1="12" y1="8" x2="12.01" y2="8"></line>
                </svg>
                <div>
                  <p className="text-[12px] font-medium" style={{ color: colors.accent }}>Before connecting</p>
                  <p className="text-[11px] mt-0.5" style={{ color: colors.textDim }}>
                    Make sure SSH is running and the port is not blocked.
                  </p>
                </div>
              </div>
            </div>
          )}

          {tab === 'auth' && (
            <div className="space-y-5">
              {/* recognize verify way switch */}
              <div>
                <label className="text-[12px] mb-2 block" style={{ color: colors.textSecondary }}>Auth method</label>
                <div className="flex gap-2">
                  <button
                    onClick={() => setAuthMethod('password')}
                    className="flex-1 py-2.5 px-4 rounded-lg text-[13px] font-medium transition-all flex items-center justify-center gap-2"
                    style={{
                      backgroundColor: authMethod === 'password' ? colors.accentSoft : colors.bgInput,
                      border: `1px solid ${authMethod === 'password' ? colors.accent : colors.border}`,
                      color: authMethod === 'password' ? colors.accent : colors.textSecondary,
                    }}
                  >
                    <svg className="w-4 h-4" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                      <rect x="3" y="11" width="18" height="11" rx="2" ry="2"></rect>
                      <path d="M7 11V7a5 5 0 0 1 10 0v4"></path>
                    </svg>
                    Password
                  </button>
                  <button
                    onClick={() => setAuthMethod('privateKey')}
                    className="flex-1 py-2.5 px-4 rounded-lg text-[13px] font-medium transition-all flex items-center justify-center gap-2"
                    style={{
                      backgroundColor: authMethod === 'privateKey' ? colors.accentSoft : colors.bgInput,
                      border: `1px solid ${authMethod === 'privateKey' ? colors.accent : colors.border}`,
                      color: authMethod === 'privateKey' ? colors.accent : colors.textSecondary,
                    }}
                  >
                    <svg className="w-4 h-4" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                      <path d="M21 2l-2 2m-7.61 7.61a5.5 5.5 0 1 1-7.778 7.778 5.5 5.5 0 0 1 7.777-7.777zm0 0L15.5 7.5m0 0l3 3L22 7l-3-3m-3.5 3.5L19 4"></path>
                    </svg>
                    Private key
                  </button>
                </div>
              </div>

              {/* username */}
              <div>
                <label className="flex items-center gap-1.5 text-[12px] mb-2" style={{ color: colors.textSecondary }}>
                  <span>Username</span>
                  <span className="text-[10px] px-1 py-0.5 rounded" style={{ backgroundColor: colors.accentSoft, color: colors.accent }}>Required</span>
                </label>
                <input
                  type="text"
                  value={username}
                  onChange={(e) => setUsername(e.target.value)}
                  placeholder="root"
                  className="w-full px-4 py-2.5 rounded-lg text-[13px] outline-none transition-all"
                  style={{
                    backgroundColor: colors.bgInput,
                    border: `1px solid ${errors.username ? colors.red : colors.border}`,
                    color: colors.text,
                  }}
                  onFocus={(e) => { e.currentTarget.style.borderColor = colors.accent }}
                  onBlur={(e) => { e.currentTarget.style.borderColor = errors.username ? colors.red : colors.border }}
                />
                {errors.username && <p className="text-[11px] mt-1" style={{ color: colors.red }}>{errors.username}</p>}
              </div>

              {/* password */}
              {authMethod === 'password' && (
                <div>
                  <label className="flex items-center gap-1.5 text-[12px] mb-2" style={{ color: colors.textSecondary }}>
                    <span>Password</span>
                    {!isEditing && <span className="text-[10px] px-1 py-0.5 rounded" style={{ backgroundColor: colors.accentSoft, color: colors.accent }}>Required</span>}
                    {isEditing && <span className="text-[10px] px-1 py-0.5 rounded" style={{ backgroundColor: colors.textDim + '20', color: colors.textDim }}>Leave blank to keep</span>}
                  </label>
                  <input
                    type="password"
                    value={password}
                    onChange={(e) => setPassword(e.target.value)}
                    placeholder={isEditing ? 'Leave blank to keep the current password' : 'Enter password'}
                    className="w-full px-4 py-2.5 rounded-lg text-[13px] outline-none transition-all"
                    style={{
                      backgroundColor: colors.bgInput,
                      border: `1px solid ${errors.password ? colors.red : colors.border}`,
                      color: colors.text,
                    }}
                    onFocus={(e) => { e.currentTarget.style.borderColor = colors.accent }}
                    onBlur={(e) => { e.currentTarget.style.borderColor = errors.password ? colors.red : colors.border }}
                  />
                  {errors.password && <p className="text-[11px] mt-1" style={{ color: colors.red }}>{errors.password}</p>}
                </div>
              )}

              {/* private key */}
              {authMethod === 'privateKey' && (
                <div>
                  <label className="flex items-center gap-1.5 text-[12px] mb-2" style={{ color: colors.textSecondary }}>
                    <span>Private key</span>
                    {!isEditing && <span className="text-[10px] px-1 py-0.5 rounded" style={{ backgroundColor: colors.accentSoft, color: colors.accent }}>Required</span>}
                    {isEditing && <span className="text-[10px] px-1 py-0.5 rounded" style={{ backgroundColor: colors.textDim + '20', color: colors.textDim }}>Leave blank to keep</span>}
                  </label>
                  <textarea
                    value={privateKey}
                    onChange={(e) => setPrivateKey(e.target.value)}
                    placeholder={`-----BEGIN RSA PRIVATE KEY-----\n...\n-----END RSA PRIVATE KEY-----`}
                    rows={6}
                    className="w-full px-4 py-2.5 rounded-lg text-[12px] font-mono outline-none transition-all resize-none"
                    style={{
                      backgroundColor: colors.bgInput,
                      border: `1px solid ${errors.privateKey ? colors.red : colors.border}`,
                      color: colors.text,
                    }}
                    onFocus={(e) => { e.currentTarget.style.borderColor = colors.accent }}
                    onBlur={(e) => { e.currentTarget.style.borderColor = errors.privateKey ? colors.red : colors.border }}
                  />
                  {errors.privateKey && <p className="text-[11px] mt-1" style={{ color: colors.red }}>{errors.privateKey}</p>}
                  <p className="text-[11px] mt-2" style={{ color: colors.textDim }}>
                    RSA, ED25519, and similar. Paste the key here, or set a key path under Advanced.
                  </p>
                </div>
              )}

              {/* note stay password hint */}
              <div className="flex items-center gap-2">
                <input type="checkbox" id="remember" className="w-4 h-4 rounded" style={{ accentColor: colors.accent }} />
                <label htmlFor="remember" className="text-[12px]" style={{ color: colors.textSecondary }}>
                  Remember password (encrypted locally)
                </label>
              </div>
            </div>
          )}

          {tab === 'advanced' && (
            <div className="space-y-5">
              {/* connection timeout */}
              <div>
                <label className="text-[12px] mb-2 block" style={{ color: colors.textSecondary }}>
                  Connect timeout (seconds)
                </label>
                <div className="flex items-center gap-4">
                  <input
                    type="range"
                    min="5"
                    max="120"
                    step="5"
                    value={connectionTimeout}
                    onChange={(e) => setConnectionTimeout(parseInt(e.target.value))}
                    className="flex-1"
                    style={{ accentColor: colors.accent }}
                  />
                  <span
                    className="text-[13px] font-mono w-12 text-right"
                    style={{ color: colors.text }}
                  >
                    {connectionTimeout}s
                  </span>
                </div>
              </div>

              {/* keep live middle gap */}
              <div>
                <label className="text-[12px] mb-2 block" style={{ color: colors.textSecondary }}>
                  Keepalive interval (seconds)
                </label>
                <div className="flex items-center gap-4">
                  <input
                    type="range"
                    min="0"
                    max="300"
                    step="10"
                    value={keepAliveInterval}
                    onChange={(e) => setKeepAliveInterval(parseInt(e.target.value))}
                    className="flex-1"
                    style={{ accentColor: colors.accent }}
                  />
                  <span
                    className="text-[13px] font-mono w-12 text-right"
                    style={{ color: colors.text }}
                  >
                    {keepAliveInterval === 0 ? 'Close' : `${keepAliveInterval}s`}
                  </span>
                </div>
              </div>

              {/* start command */}
              <div>
                <label className="text-[12px] mb-2 block" style={{ color: colors.textSecondary }}>Run after connect</label>
                <input
                  type="text"
                  value={startupCommand}
                  onChange={(e) => setStartupCommand(e.target.value)}
                  placeholder="e.g. cd /home && ls -la"
                  className="w-full px-4 py-2.5 rounded-lg text-[13px] font-mono outline-none transition-all"
                  style={{
                    backgroundColor: colors.bgInput,
                    border: `1px solid ${colors.border}`,
                    color: colors.text,
                  }}
                  onFocus={(e) => { e.currentTarget.style.borderColor = colors.accent }}
                  onBlur={(e) => { e.currentTarget.style.borderColor = colors.border }}
                />
                <p className="text-[11px] mt-2" style={{ color: colors.textDim }}>
                  Commands to run after connect; separate with ;
                </p>
              </div>

              {/* other select item */}
              <div className="space-y-3 pt-2">
                <div className="flex items-center justify-between py-2">
                  <div>
                    <p className="text-[13px]" style={{ color: colors.text }}>Enable compression</p>
                    <p className="text-[11px]" style={{ color: colors.textDim }}>Compress the SSH stream</p>
                  </div>
                  <button
                    onClick={() => setEnableCompression(!enableCompression)}
                    className="w-10 h-5.5 rounded-full transition-all relative"
                    style={{
                      backgroundColor: enableCompression ? colors.accent : colors.border,
                    }}
                  >
                    <div
                      className="w-4.5 h-4.5 bg-white rounded-full absolute top-0.25 transition-all"
                      style={{
                        left: enableCompression ? '22px' : '2px',
                      }}
                    />
                  </button>
                </div>

                <div className="flex items-center justify-between py-2">
                  <div>
                    <p className="text-[13px]" style={{ color: colors.text }}>Strict host-key checking</p>
                    <p className="text-[11px]" style={{ color: colors.textDim }}>Confirm the host fingerprint on first connect</p>
                  </div>
                  <button
                    className="w-10 h-5.5 rounded-full transition-all relative"
                    style={{
                      backgroundColor: colors.accent,
                    }}
                  >
                    <div
                      className="w-4.5 h-4.5 bg-white rounded-full absolute top-0.25 transition-all"
                      style={{ left: '22px' }}
                    />
                  </button>
                </div>
              </div>
            </div>
          )}
        </div>

        {/* Footer */}
        <div
          className="flex items-center justify-between px-6 py-4 flex-shrink-0"
          style={{ borderTop: `1px solid ${colors.border}`, backgroundColor: colors.bgTertiary }}
        >
          <p className="text-[11px]" style={{ color: colors.textDim }}>
            Press <kbd className="px-1.5 py-0.5 rounded text-[10px]" style={{ backgroundColor: colors.bgInput }}>Esc</kbd> Close
          </p>
          <div className="flex items-center gap-3">
            <button
              onClick={onClose}
              disabled={submitting}
              className="px-5 py-2 rounded-lg text-[13px] font-medium transition-all"
              style={{
                backgroundColor: 'transparent',
                border: `1px solid ${colors.border}`,
                color: colors.textSecondary,
                opacity: submitting ? 0.5 : 1,
              }}
              onMouseEnter={(e) => { if (!submitting) e.currentTarget.style.backgroundColor = `${colors.textDim}10` }}
              onMouseLeave={(e) => { e.currentTarget.style.backgroundColor = 'transparent' }}
            >
              Cancel
            </button>
            <button
              onClick={handleConnect}
              disabled={submitting}
              className="px-5 py-2 rounded-lg text-[13px] font-medium transition-all flex items-center gap-2"
              style={{
                backgroundColor: colors.accent,
                color: '#fff',
                opacity: submitting ? 0.7 : 1,
              }}
              onMouseEnter={(e) => { if (!submitting) e.currentTarget.style.opacity = '0.9' }}
              onMouseLeave={(e) => { if (!submitting) e.currentTarget.style.opacity = '1' }}
            >
              {submitting ? (
                <>
                  <svg className="w-4 h-4 animate-spin" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                    <path d="M12 2v4M12 18v4M4.93 4.93l2.83 2.83M16.24 16.24l2.83 2.83M2 12h4M18 12h4M4.93 19.07l2.83-2.83M16.24 7.76l2.83-2.83" />
                  </svg>
                  Submitting...
                </>
              ) : (
                <>
                  <svg className="w-4 h-4" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                    <path d="M5 12h14M12 5l7 7-7 7" />
                  </svg>
                  {isEditing ? 'Save' : 'Connect'}
                </>
              )}
            </button>
          </div>
        </div>
      </div>
    </div>
  )
}
