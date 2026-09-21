import {useCallback, useEffect, useRef, useState, type ReactNode, type RefObject} from 'react'
import './conversation-transcript.css'
import {hasMessagesBelow} from './chatViewport'

/** New content changes only the affordance, never the reader's scroll position. */
export function ConversationViewport({className, logRef, children, tx}: {
  className: string; logRef: RefObject<HTMLDivElement | null>; children: ReactNode; tx: (key: string) => string
}) {
  const contentRef=useRef<HTMLDivElement>(null)
  const [below,setBelow]=useState(false)
  const belowRef=useRef(false)
  const update=useCallback(()=>{
    const log=logRef.current
    if(!log)return
    const next=hasMessagesBelow(log.scrollHeight,log.scrollTop,log.clientHeight)
    if(next!==belowRef.current){belowRef.current=next;setBelow(next)}
  },[logRef])
  useEffect(()=>{
    const log=logRef.current,content=contentRef.current
    if(!log||!content)return
    let frame:number|null=null
    const schedule=()=>{
      if(frame===null)frame=requestAnimationFrame(()=>{frame=null;update()})
    }
    const observer=new ResizeObserver(schedule)
    observer.observe(log);observer.observe(content)
    log.addEventListener('scroll',schedule,{passive:true});update()
    return()=>{observer.disconnect();log.removeEventListener('scroll',schedule);if(frame!==null)cancelAnimationFrame(frame)}
  },[logRef,update])
  const jump=()=>{
    const log=logRef.current
    if(log)log.scrollTop=log.scrollHeight
    update()
  }
  return <div className="conversation-viewport">
    <div className={className} ref={logRef}>
      <div className="conversation-transcript" ref={contentRef}>{children}</div>
    </div>
    {below&&<button type="button" className="message-jump-latest" onClick={jump} aria-label={tx('jumpToLatestMessage')}>
      <span aria-hidden="true">↓</span> {tx('jumpToLatestMessage')}
    </button>}
  </div>
}
