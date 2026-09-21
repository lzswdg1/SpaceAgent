import { createContext, useContext, useEffect, useState, type ReactNode } from 'react'
import { createPortal } from 'react-dom'

// Page owners retain their data and mutation handlers; only their navigation is portalled.
export const WorkspaceNavigationContext = createContext<HTMLElement | null>(null)
export function WorkspaceNavigation({ children, ready = true }: { children: ReactNode; ready?: boolean }) {
  const target = useContext(WorkspaceNavigationContext)
  const [revealed, setRevealed] = useState(ready)
  useEffect(() => { if (ready) setRevealed(true) }, [ready])
  return target ? createPortal(
    <div className={`workspace-navigation-view${revealed ? ' is-ready' : ''}`} inert={!revealed} aria-busy={!revealed}>
      {children}
    </div>, target) : null
}
