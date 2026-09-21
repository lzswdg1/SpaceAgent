import { memo, useEffect, useRef, useState, type RefObject } from 'react'
import { MarkdownMessage } from '../../components/MarkdownMessage'
import { ReasoningTrace } from './ReasoningTrace'

type TranscriptMessage = {
  id: string; role: string; content: string; streaming?: boolean; completionState?: 'PARTIAL' | 'COMPLETE'
  reasoning?: string; reasoningOpen?: boolean; reasoningStartedAt?: number; reasoningElapsedMs?: number
}

/** Variable-height history window: offscreen AST/DOM is released, never the message data. */
export const ConversationMessage = memo(function ConversationMessage({ message, mode, logRef, tx, toggleReasoning }: {
  message: TranscriptMessage; mode: 'chat' | 'project'; logRef: RefObject<HTMLDivElement | null>
  tx: (key: string) => string; toggleReasoning: (id: string) => void
}) {
  const node = useRef<HTMLElement>(null)
  const height = useRef(Math.max(44, Math.min(20_000, Math.ceil(message.content.length / 48) * 26 + 24)))
  const wasLive = useRef(Boolean(message.streaming))
  wasLive.current ||= Boolean(message.streaming)
  const [visible, setVisible] = useState(Boolean(message.streaming) || typeof IntersectionObserver === 'undefined')
  const mounted = visible || Boolean(message.streaming)
  useEffect(() => {
    const element = node.current, root = logRef.current
    if (!element || !root || typeof IntersectionObserver === 'undefined') return
    const observer = new IntersectionObserver(entries => setVisible(entries[0]?.isIntersecting ?? false), { root, rootMargin: '600px 0px' })
    observer.observe(element)
    return () => observer.disconnect()
  }, [logRef])
  useEffect(() => {
    const element = node.current, root = logRef.current
    if (!mounted || !element || !root) return
    const measure = () => {
      const measured = element.getBoundingClientRect().height
      const delta = measured - height.current
      // Compensate only immutable history placeholders above the viewport. Never
      // follow growing streamed output or move a reader to the latest message.
      if (!wasLive.current && delta && element.getBoundingClientRect().top < root.getBoundingClientRect().top) root.scrollTop += delta
      height.current = measured
    }
    measure()
    if (typeof ResizeObserver === 'undefined') return
    const observer = new ResizeObserver(measure)
    observer.observe(element)
    return () => observer.disconnect()
  }, [mounted, logRef])
  return <article ref={node} aria-label={tx(message.role === 'USER' ? 'messageFromYou' : 'messageFromAssistant')}
    className={`${mode}-message ${message.role.toLowerCase()}`} style={!mounted ? { height: height.current } : undefined}>
    {mounted && <><span>{message.role}</span><div className={`${mode}-message-content`}>
      {message.reasoning && <ReasoningTrace content={message.reasoning} open={Boolean(message.reasoningOpen)} live={Boolean(message.streaming)}
        startedAt={message.reasoningStartedAt ?? Date.now()} elapsedMs={message.reasoningElapsedMs} onToggle={() => toggleReasoning(message.id)} tx={tx} />}
      {message.role === 'ASSISTANT' ? <MarkdownMessage streaming={Boolean(message.streaming)} content={message.content || (message.streaming ? tx('waitingForResponse') : '')} /> : message.content}
      {message.completionState === 'PARTIAL' && <small className="chat-partial-note">{tx('partialAnswerSaved')}</small>}
      {message.streaming && <i />}
    </div></>}
  </article>
})
