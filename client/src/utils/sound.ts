/**
 * chat done hint sound
 * use Web Audio API merge done short prompt sound effect, zero-dependency,Win/Mac across flat host compatible.
 * user can at settings center on off, default close(browser autoplay policy requires a user gesture first).
 */

const SOUND_ENABLED_KEY = 'shellmind_sound_enabled'

let audioCtx: AudioContext | null = null

function getAudioCtx(): AudioContext | null {
  if (typeof window === 'undefined') return null
  if (!audioCtx) {
    try {
      audioCtx = new (window.AudioContext || (window as any).webkitAudioContext)()
    } catch {
      return null
    }
  }
  // browse preview widget strategy slightly: user cross mutual front context may be suspend
  if (audioCtx.state === 'suspended') {
    audioCtx.resume().catch(() => {})
  }
  return audioCtx
}

export function isSoundEnabled(): boolean {
  try {
    return localStorage.getItem(SOUND_ENABLED_KEY) === 'true'
  } catch {
    return false
  }
}

export function setSoundEnabled(enabled: boolean): void {
  try {
    if (enabled) {
      localStorage.setItem(SOUND_ENABLED_KEY, 'true')
    } else {
      localStorage.removeItem(SOUND_ENABLED_KEY)
    }
  } catch {
    // silently ignore
  }
}

/**
 * broadcast drop done hint sound: two sound up call(C5 → E5), clear fragile please
 */
export function playCompleteSound(): void {
  if (!isSoundEnabled()) return
  const ctx = getAudioCtx()
  if (!ctx) return

  const now = ctx.currentTime
  const notes = [
    { freq: 523.25, start: 0, duration: 0.12 },    // C5
    { freq: 659.25, start: 0.10, duration: 0.15 },  // E5
  ]

  for (const note of notes) {
    const osc = ctx.createOscillator()
    const gain = ctx.createGain()
    osc.type = 'sine'
    osc.frequency.value = note.freq
    gain.gain.setValueAtTime(0, now + note.start)
    gain.gain.linearRampToValueAtTime(0.15, now + note.start + 0.01)
    gain.gain.exponentialRampToValueAtTime(0.001, now + note.start + note.duration)
    osc.connect(gain)
    gain.connect(ctx.destination)
    osc.start(now + note.start)
    osc.stop(now + note.start + note.duration)
  }
}

/**
 * broadcast drop error hint sound: one sound low sink down call
 */
export function playErrorSound(): void {
  if (!isSoundEnabled()) return
  const ctx = getAudioCtx()
  if (!ctx) return

  const now = ctx.currentTime
  const osc = ctx.createOscillator()
  const gain = ctx.createGain()
  osc.type = 'sine'
  osc.frequency.setValueAtTime(440, now)
  osc.frequency.exponentialRampToValueAtTime(220, now + 0.2)
  gain.gain.setValueAtTime(0, now)
  gain.gain.linearRampToValueAtTime(0.12, now + 0.01)
  gain.gain.exponentialRampToValueAtTime(0.001, now + 0.25)
  osc.connect(gain)
  gain.connect(ctx.destination)
  osc.start(now)
  osc.stop(now + 0.25)
}
