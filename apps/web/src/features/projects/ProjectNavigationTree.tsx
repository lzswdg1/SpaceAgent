import Tree, { type DataNode } from '@rc-component/tree'
import { ChevronRight, FolderClosed, MessageSquare } from 'lucide-react'
import { useState, type FormEvent } from 'react'
import type { Project, ProjectConversation, ProjectDirectory, SourceRepository, GithubConnectionOption } from './types'

type Props = {
  navigationLocked?: boolean
  hasMore?:boolean; loadingMore?:boolean; onLoadMore?:()=>void;
  githubConnections?:GithubConnectionOption[];onRefreshConnections?:()=>void;
  collapsed?: boolean; projects: Project[]; conversations: ProjectConversation[];
  directories: ProjectDirectory[]; readySources: SourceRepository[];
  projectId: string | null; directoryId: string | null; conversationId: string | null;
  loading: boolean; creatingProject: boolean; creatingDirectory: boolean; tx: (key: string) => string;
  onToggleProjectCreate: () => void; onCreateProject: (event: FormEvent<HTMLFormElement>) => void;
  onSelectProject: (id: string) => void; onSelectDirectory: (id: string) => void; onSelectConversation: (id: string) => void;
  onCreateConversation: (directory: ProjectDirectory) => void;
  onDeleteConversation: (conversation: ProjectConversation, directory: ProjectDirectory) => void;
  onArchiveDirectory: (directory: ProjectDirectory) => void;
  onToggleDirectoryCreate: () => void; onCreateDirectory: (event: FormEvent<HTMLFormElement>) => void;
}

export function ProjectNavigationTree(props: Props) {
  const { collapsed=false,projects,conversations,directories,readySources,directoryId,conversationId,loading,tx }=props
  const [closed,setClosed]=useState<Set<string>>(()=>new Set())
  const [kind,setKind]=useState('EMPTY')
  const roots=directories.filter(root=>root.state==='ACTIVE' && projects.some(project=>project.id===root.projectId && project.status==='ACTIVE'))
  const select=(root:ProjectDirectory)=>{if(props.navigationLocked)return;props.onSelectProject(root.projectId);props.onSelectDirectory(root.id)}
  const form=(submit:Props['onCreateProject'])=><form className="project-create root-create-form" onSubmit={submit}>
    <input type="hidden" name="rootKind" value={kind}/>
    <label><span>{tx('rootType')}</span><select value={kind} onChange={event=>setKind(event.target.value)}><option value="EMPTY">{tx('emptyRoot')}</option><option value="GITHUB">{tx('githubRepositoryRoot')}</option>{readySources.length>0&&<option value="REPOSITORY">{tx('existingRepositoryRoot')}</option>}</select></label>
    {kind==='GITHUB'&&<><label><span>GitHub</span><select name="connectionId" required><option value="">{tx('chooseGithubConnection')}</option>{props.githubConnections?.map(connection=><option key={connection.id} value={connection.id}>{connection.accountName||connection.name}</option>)}</select></label><input name="githubUrl" type="url" placeholder="https://github.com/owner/repository" required/>{!props.githubConnections?.length&&<a href="/app/mcp" target="_blank" rel="noopener noreferrer">{tx('openMcpReauthorization')}</a>}<button type="button" onClick={props.onRefreshConnections}>{tx('refreshConnections')}</button></>}
    {kind==='REPOSITORY'&&<select name="sourceRepositoryId" aria-label={tx('sourceRepositories')} required><option value="">{tx('sourceRepositories')}</option>{readySources.map(source=><option key={source.id} value={source.id}>{source.displayName}</option>)}</select>}
    <input name="name" aria-label={tx('rootName')} placeholder={tx('rootName')} maxLength={120} required/>
    <small>{tx(kind==='EMPTY'?'emptyRootStorageHint':'repositoryRootHint')}</small>
    <button disabled={loading||(kind==='GITHUB'&&!props.githubConnections?.length)}>{tx('create')}</button>
  </form>
  const treeData: DataNode[] = roots.map(root => ({
    key: `root:${root.id}`, disabled: props.navigationLocked,
    className: 'project-tree-directory project-tree-root-directory',
    title: <span className="platform-tree-title"><FolderClosed className="project-folder-icon" size={16} aria-hidden="true" /><span className="platform-tree-name" title={root.name}>{root.name}</span><span className="platform-tree-actions">
      <button type="button" disabled={loading} aria-label={tx('newProjectConversation') + ': ' + root.name} onClick={event => { event.stopPropagation(); select(root); props.onCreateConversation(root) }}>＋</button>
      <button type="button" disabled={loading} aria-label={tx('deleteRoot') + ': ' + root.name} onClick={event => { event.stopPropagation(); props.onArchiveDirectory(root) }}>×</button>
    </span></span>,
    children: conversations.filter(conversation => conversation.projectId === root.projectId && conversation.projectDirectoryId === root.id).map(conversation => ({
      key: `conversation:${conversation.conversationId}`, isLeaf: true, disabled: props.navigationLocked,
      className: 'project-tree-conversation' + (conversation.conversationId === conversationId ? ' active' : ''),
      title: <span className="platform-tree-title"><MessageSquare size={13} aria-hidden="true" /><span className="platform-tree-name" title={conversation.title}>{conversation.title}</span><button type="button" className="project-tree-conversation-delete" aria-label={tx('deleteConversation') + ': ' + conversation.title} onClick={event => { event.stopPropagation(); props.onDeleteConversation(conversation, root) }}>×</button></span>,
    })),
  }))
  return <aside className={`project-tree ${collapsed ? 'is-collapsed' : ''}`} id="project-navigation-tree" inert={collapsed}>
    <header><span>{tx('projectNav')}</span><button type="button" disabled={props.navigationLocked} aria-label={tx('createRoot')} onClick={props.onToggleProjectCreate}>＋</button></header>
    {props.navigationLocked&&<small role="status">{tx('finishStreamBeforeSwitch')}</small>}
    {props.creatingProject&&form(props.onCreateProject)}
    <nav className="project-list" aria-label={tx('projectNav')}>
      <Tree
        prefixCls="platform-tree" treeData={treeData} showIcon={false} virtual={false}
        expandedKeys={roots.filter(root => !closed.has(root.id)).map(root => `root:${root.id}`)}
        selectedKeys={conversationId ? [`conversation:${conversationId}`] : directoryId ? [`root:${directoryId}`] : []}
        switcherIcon={<ChevronRight size={13} aria-hidden="true" />}
        onExpand={keys => setClosed(new Set(roots.filter(root => !keys.includes(`root:${root.id}`)).map(root => root.id)))}
        onSelect={(_, info) => {
          if (props.navigationLocked) return
          const key = String(info.node.key)
          if (key.startsWith('root:')) { const root = roots.find(root => `root:${root.id}` === key); if (root) select(root) }
          else {
            const conversation = conversations.find(conversation => `conversation:${conversation.conversationId}` === key)
            const root = roots.find(root => root.id === conversation?.projectDirectoryId)
            if (conversation && root) { select(root); props.onSelectConversation(conversation.conversationId) }
          }
        }}
      />
      <button type="button" className="project-tree-add-directory" disabled={props.navigationLocked} onClick={props.onToggleDirectoryCreate}>＋ {tx('createRoot')}</button>
      {props.creatingDirectory&&form(props.onCreateDirectory)}
      {!loading&&!roots.length&&<p>{tx('noProjects')}</p>}
      {props.hasMore&&<button type="button" className="project-tree-add-directory" disabled={props.loadingMore} onClick={props.onLoadMore}>{tx('loadMoreConversations')}</button>}
    </nav>
  </aside>
}
