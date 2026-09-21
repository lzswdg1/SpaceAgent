import { memo, useDeferredValue } from 'react'
import Markdown from 'react-markdown'
import remarkGfm from 'remark-gfm'
import './markdown-message.css'
import { markdownBlocks } from './markdownBlocks'

export function safeMarkdownUrl(value: string): string | undefined {
  if (/[\u0000-\u0020\u007f]/.test(value)) return undefined
  if (value.startsWith('#')) return value
  try {
    const url = new URL(value)
    return ['https:', 'http:'].includes(url.protocol) ? url.href : undefined
  } catch { return undefined }
}

const plugins = [remarkGfm]
const components = {
  a: ({ href, children }: { href?: string; children?: import('react').ReactNode }) => href
    ? <a href={href} target="_blank" rel="noopener noreferrer">{children}</a> : <span>{children}</span>,
  img: ({ alt }: { alt?: string }) => <span>{alt}</span>,
  table: ({ children }: { children?: import('react').ReactNode }) => <div className="markdown-table-scroll" tabIndex={0}><table>{children}</table></div>,
}
const MarkdownBlock = memo(function MarkdownBlock({ content }: { content: string }) {
  return <Markdown remarkPlugins={plugins} skipHtml urlTransform={safeMarkdownUrl} components={components}>{content}</Markdown>
})

/** React AST rendering only. Raw HTML and automatic remote image loading are disabled. */
export const MarkdownMessage = memo(function MarkdownMessage({ content, streaming = false }: { content: string; streaming?: boolean }) {
  const deferredContent = useDeferredValue(content)
  return <div className="markdown-message">
    {(streaming ? markdownBlocks(deferredContent) : [deferredContent]).map((block, index) => <MarkdownBlock key={index} content={block} />)}
  </div>
})
