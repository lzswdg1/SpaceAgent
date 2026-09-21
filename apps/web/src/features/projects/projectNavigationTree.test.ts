import { createElement } from 'react'
import { renderToStaticMarkup } from 'react-dom/server'
import { describe, expect, it, vi } from 'vitest'
import { ProjectNavigationTree } from './ProjectNavigationTree'
import type { Project, ProjectConversation, ProjectDirectory, SourceRepository } from './types'

const project: Project = {
  id: 'project-1', tenantId: 'tenant-1', ownerId: 'user-1', name: 'SpaceAgent',
  description: null, status: 'ACTIVE', createdAt: '2026-09-12T00:00:00Z', updatedAt: '2026-09-12T00:00:00Z',
}
const defaultDirectory: ProjectDirectory = {
  id: 'directory-default', tenantId: 'tenant-1', projectId: project.id,
  sourceRepositoryId: 'source-1', name: 'Default', relativePath: '', defaultDirectory: true,
  state: 'ACTIVE', createdAt: project.createdAt, updatedAt: project.updatedAt,
}
const repositoryDirectory: ProjectDirectory = {
  ...defaultDirectory,
  id: 'directory-repository', name: 'apps/web', relativePath: 'apps/web', defaultDirectory: false,
}
const source = {
  id: 'source-1', displayName: 'owner/repository', state: 'READY',
} as SourceRepository
const conversation = (
  id: string,
  directory: ProjectDirectory,
  title: string,
): ProjectConversation => ({
  conversationId: id, agentId: 'agent-1', userId: 'user-1', projectId: project.id,
  projectDirectoryId: directory.id, activeTaskId: null, status: 'ACTIVE',
  startedAt: project.createdAt, lastMessageAt: project.updatedAt, messages: [],
  messagePage: 1, messageSize: 20, messageTotal: 0, title,
})

const renderTree = () => renderToStaticMarkup(createElement(ProjectNavigationTree, {
  projects: [project],
  conversations: [
    conversation('conversation-default', defaultDirectory, 'Project overview'),
    conversation('conversation-web', repositoryDirectory, 'Frontend implementation'),
  ],
  directories: [defaultDirectory, repositoryDirectory],
  readySources: [source],
  projectId: project.id,
  directoryId: repositoryDirectory.id,
  conversationId: 'conversation-web',
  loading: false,
  creatingProject: false,
  creatingDirectory: false,
  tx: (key: string) => key,
  onToggleProjectCreate: vi.fn(),
  onCreateProject: vi.fn(),
  onSelectProject: vi.fn(),
  onSelectDirectory: vi.fn(),
  onSelectConversation: vi.fn(),
  onCreateConversation: vi.fn(),
  onDeleteConversation: vi.fn(),
  onArchiveDirectory: vi.fn(),
  onToggleDirectoryCreate: vi.fn(),
  onCreateDirectory: vi.fn(),
}))

describe('Project navigation tree structure', () => {
  it('renders only peer roots with their own conversations and no extra Project wrapper', () => {
    const markup = renderTree()

    expect(markup).not.toContain('>SpaceAgent<')
    expect(markup).toContain('Project overview')
    expect(markup).toContain('apps/web')
    expect(markup).toContain('Frontend implementation')
    expect(markup).toContain('>Default<')
    expect(markup.match(/project-folder-icon/g)).toHaveLength(2)
    expect(markup).not.toContain('project-tree-project-children')
    expect(markup.match(/project-tree-directory project-tree-root-directory/g)).toHaveLength(2)
    expect(markup.match(/aria-label="deleteRoot:/g)).toHaveLength(2)
  })

  it('uses semantic collapsible buttons and a selected conversation row', () => {
    const markup = renderTree()

    expect(markup).toContain('aria-expanded="true"')
    expect(markup).toContain('project-tree-conversation active')
    expect(markup.match(/project-tree-conversation-delete/g)).toHaveLength(2)
    expect(markup).not.toContain('project-conversation-icon')
    expect(markup).not.toContain('ACTIVE</small>')
  })
})
