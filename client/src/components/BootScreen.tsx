import { useEffect, useMemo, useRef, useState } from 'react'
import { useRuntimeStore } from '../stores/runtimeStore'
import { useThemeStore } from '../stores/themeStore'

interface BootScreenProps {
  onRetry: () => void
  onSkip: () => void
}

/** Progress 0–100 from boot stage and elapsed time (ease-out during the booting stage). */
function stageProgress(stage: string, elapsedMs: number): number {
  switch (stage) {
    case 'init':
      return 5
    case 'resolve':
      return 18
    case 'prepare':
      return 32
    case 'spawn':
      return 45
    case 'booting': {
      const t = Math.min(elapsedMs / 18000, 1)
      return Math.round(45 + (92 - 45) * (1 - Math.pow(1 - t, 2)))
    }
    case 'ready':
      return 100
    default:
      return 5
  }
}

export function BootScreen({ onRetry, onSkip }: BootScreenProps) {
  const { colors } = useThemeStore()
  const { status, starting, error } = useRuntimeStore()

  const mountedAt = useRef(Date.now())
  const [now, setNow] = useState(Date.now())
  useEffect(() => {
    const timer = setInterval(() => setNow(Date.now()), 200)
    return () => clearInterval(timer)
  }, [])

  const ready = status.running && status.port > 0 && !!status.token
  const failed =
    !starting &&
    !ready &&
    (status.stage === 'error' || !!error || (!status.running && !!status.lastError))

  const elapsed = now - mountedAt.current
  const progress = useMemo(
    () => (ready ? 100 : stageProgress(status.stage, elapsed)),
    [ready, status.stage, elapsed],
  )
  const elapsedSec = Math.floor(elapsed / 1000)

  const failureText = error || status.lastError || 'Local Agent failed to start'

  return (
    <div
      className="fixed inset-0 z-[9999] flex items-center justify-center"
      style={{ backgroundColor: colors.bgPrimary }}
    >
      <style>{`
        @keyframes shellmind-boot-pulse {
          0%, 100% { opacity: 1; }
          50% { opacity: 0.55; }
        }
        @keyframes shellmind-boot-shimmer {
          0% { transform: translateX(-100%); }
          100% { transform: translateX(250%); }
        }
      `}</style>

      <div className="flex w-[420px] max-w-[86vw] flex-col items-center">
        {/* Logo / title */}
        <div
          className="mb-2 flex h-14 w-14 items-center justify-center rounded-2xl text-[26px] font-bold"
          style={{
            backgroundColor: colors.accentSoft,
            color: colors.accent,
            border: `1px solid ${colors.accent}40`,
            animation: failed ? undefined : 'shellmind-boot-pulse 2.2s ease-in-out infinite',
          }}
        >
          W
        </div>
        <div className="text-[17px] font-semibold" style={{ color: colors.text }}>
          ShellMind-Study
        </div>
        <div className="mt-1 text-[12px]" style={{ color: colors.textDim }}>
          AI DevOps Assistant
        </div>

        {!failed ? (
          <>
            {/* progress bar */}
            <div
              className="mt-8 h-[6px] w-full overflow-hidden rounded-full"
              style={{ backgroundColor: colors.bgTertiary }}
            >
              <div
                className="relative h-full rounded-full transition-[width] duration-300 ease-out"
                style={{ width: `${progress}%`, backgroundColor: colors.accent }}
              >
                <div
                  className="absolute inset-y-0 w-[40%] rounded-full"
                  style={{
                    background:
                      'linear-gradient(90deg, transparent, rgba(255,255,255,0.45), transparent)',
                    animation: 'shellmind-boot-shimmer 1.4s linear infinite',
                  }}
                />
              </div>
            </div>

            {/* stage notes */}
            <div
              className="mt-3 flex w-full items-center justify-between text-[12px]"
              style={{ color: colors.textSecondary }}
            >
              <span>{status.stageMessage || 'Starting the local Agent service...'}</span>
              <span style={{ color: colors.textDim }}>{progress}%</span>
            </div>
            <div className="mt-1 text-[11px]" style={{ color: colors.textDim }}>
              Elapsed {elapsedSec}s · First launch loads the Java runtime; please wait
            </div>
          </>
        ) : (
          <>
            {/* fail notes */}
            <div
              className="mt-8 w-full rounded-xl px-4 py-3"
              style={{
                backgroundColor: `${colors.red}12`,
                border: `1px solid ${colors.red}40`,
              }}
            >
              <div className="flex items-center gap-2 text-[13px] font-medium" style={{ color: colors.red }}>
                <span>Startup failed</span>
              </div>
              <div
                className="mt-1.5 break-all text-[12px] leading-relaxed"
                style={{ color: colors.textSecondary }}
              >
                {failureText}
              </div>
              <div className="mt-2 text-[11px] leading-relaxed" style={{ color: colors.textDim }}>
                Common causes: Java missing or older than 17, Agent package missing, or port in use.
                See logs/agent.log in the app data directory.
              </div>
            </div>

            <div className="mt-5 flex items-center gap-3">
              <button
                onClick={onRetry}
                className="h-8 cursor-pointer rounded-lg px-4 text-[12px] font-medium text-white"
                style={{ backgroundColor: colors.accent }}
              >
                Retry
              </button>
              <button
                onClick={onSkip}
                className="h-8 cursor-pointer rounded-lg px-4 text-[12px]"
                style={{
                  color: colors.textSecondary,
                  border: `1px solid ${colors.border}`,
                  backgroundColor: colors.bgTertiary,
                }}
              >
                Skip and continue
              </button>
            </div>
          </>
        )}
      </div>
    </div>
  )
}
