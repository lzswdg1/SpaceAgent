import { createElement } from 'react'
import { renderToStaticMarkup } from 'react-dom/server'
import { describe, expect, it } from 'vitest'
import { MarkdownMessage, safeMarkdownUrl } from './MarkdownMessage'

const render = (content: string) => renderToStaticMarkup(createElement(MarkdownMessage, { content }))
describe('assistant Markdown presentation', () => {
  it('renders headings, emphasis, nested lists, fences and GFM tables', () => {
    const html = render('# 标题\n\n**重点**和`workspaceId`\n\n1. 第一步\n   - 子项\n\n```ts\nconst n = 1\n```\n\n| 名称 | 状态 |\n| --- | --- |\n| repo | READY |')
    expect(html).toContain('<h1>标题</h1>')
    expect(html).toContain('<strong>重点</strong>')
    expect(html).toContain('<code>workspaceId</code>')
    expect(html).toContain('<ol>')
    expect(html).toContain('<ul>')
    expect(html).toContain('<pre><code class="language-ts">')
    expect(html).toContain('<table>')
  })
  it('does not execute raw HTML, unsafe links or load remote images', () => {
    const html = render('<script>alert(1)</script>\n\n[x](javascript:alert) ![description](https://example.com/tracker.png)\n\n[Safe](https://example.com)')
    expect(html).not.toContain('<script')
    expect(html).not.toContain('javascript:')
    expect(html).not.toContain('<img')
    expect(html).toContain('noopener noreferrer')
    for (const url of ['javascript:alert(1)', 'data:text/html,bad', 'file:///etc/passwd', '//example.com', 'java\nscript:alert']) expect(safeMarkdownUrl(url)).toBeUndefined()
  })
  it('renders partial streamed fences safely and completed content consistently', () => {
    expect(render('```js\nconst x = "<script>"')).toContain('&lt;script&gt;')
    expect(render('**完整回答**')).toContain('<strong>完整回答</strong>')
  })
})
