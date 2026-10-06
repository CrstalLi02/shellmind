import { useEffect, useRef } from 'react'
import { useThemeStore } from '../stores/themeStore'

interface TypewriterRendererProps {
  /** backend push accumulate all text */
  fullText: string
  /** whether still at load(streaming output center) */
  isLoading: boolean
  /** render done callback */
  onRenderComplete?: () => void
  /** from set meaning render function(like Markdown render) */
  renderContent?: (text: string) => React.ReactNode
  /** @deprecated deprecated, keep only as interface compatible */
  maxCharsPerFrame?: number
}

export function TypewriterRenderer({
  fullText,
  isLoading,
  onRenderComplete,
  renderContent,
}: TypewriterRendererProps) {
  const { colors } = useThemeStore()
  const completedRef = useRef(false)

  // isLoading become false when call one nth onRenderComplete
  useEffect(() => {
    if (!isLoading && !completedRef.current) {
      completedRef.current = true
      onRenderComplete?.()
    }
    if (isLoading) {
      completedRef.current = false
    }
  }, [isLoading, onRenderComplete])

  const content = renderContent ? renderContent(fullText) : (
    <div
      className="text-[13px] leading-relaxed whitespace-pre-wrap break-words"
      style={{ color: colors.text }}
      dangerouslySetInnerHTML={{ __html: fullText }}
    />
  )

  return (
    <div className="relative">
      {content}
      {isLoading && (
        <span
          className="inline-block w-0.5 animate-pulse"
          style={{
            backgroundColor: colors.accent,
            verticalAlign: 'text-bottom',
            height: '1em',
          }}
        />
      )}
    </div>
  )
}
