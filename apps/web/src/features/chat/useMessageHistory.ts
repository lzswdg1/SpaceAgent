import { useEffect, useRef, useState, type Dispatch, type SetStateAction } from 'react'
import { loadMessagePage } from './chatApi'
import type { AuthenticatedRequest, Message } from './types'

export function useMessageHistory(request:AuthenticatedRequest,
  conversationId:string|null,sending:boolean,setMessages:Dispatch<SetStateAction<(Message & {id:string})[]>>,
  onError:(error:unknown)=>void,refreshKey?:unknown) {
  const generation=useRef(0), errorHandler=useRef(onError)
  errorHandler.current=onError
  const [cursor,setCursor]=useState<number|null>(null)
  const [hasOlder,setHasOlder]=useState(false)
  const [loadingOlder,setLoadingOlder]=useState(false)
  const previousId=useRef(conversationId)
  useEffect(()=>{
    const current=++generation.current
    if(previousId.current!==conversationId){setMessages([]);setHasOlder(false);setCursor(null);previousId.current=conversationId}
    if(!conversationId){setMessages([]);return}
    if(sending)return
    loadMessagePage(request,conversationId).then(page=>{
      if(current!==generation.current)return
      setMessages(page.items.map(normalize));setCursor(page.beforeSequence);setHasOlder(page.hasMore)
    }).catch(error=>current===generation.current&&errorHandler.current(error))
    return ()=>{generation.current++}
  },[request,conversationId,sending,setMessages,refreshKey])
  const loadOlder=async()=>{
    if(!conversationId||!hasOlder||loadingOlder||sending)return false
    const current=generation.current
    setLoadingOlder(true)
    try{
      const page=await loadMessagePage(request,conversationId,cursor)
      if(current!==generation.current)return false
      setMessages(rows=>[...page.items.filter(item=>!rows.some(row=>row.id===item.id)).map(normalize),...rows])
      setCursor(page.beforeSequence);setHasOlder(page.hasMore)
      return true
    }catch(error){if(current===generation.current)errorHandler.current(error);return false}
    finally{setLoadingOlder(false)}
  }
  return {hasOlder,loadingOlder,loadOlder}
}
const normalize=(message:Message & {id:string}) => message.role==='ASSISTANT_PARTIAL'
  ? {...message,role:'ASSISTANT',completionState:'PARTIAL' as const} : message
