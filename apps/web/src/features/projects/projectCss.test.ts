import { readFileSync } from 'node:fs'
import { describe, expect, it } from 'vitest'

const css = readFileSync(new URL('./project.css', import.meta.url), 'utf8')

describe('Project directory tree styles', () => {
  it('uses a compact folder and conversation navigation tree', () => {
    expect(css).toContain('.project-tree-project-row{')
    expect(css).toContain('.project-folder-icon{')
    expect(css).toContain('.project-tree-conversation.active{')
    expect(css).toContain('.project-tree-project-children{')
    expect(css).toContain('.project-tree-root-directory')
    expect(css).not.toContain('.project-directories{')
    expect(css).not.toContain('.project-conversations{')
  })
})
