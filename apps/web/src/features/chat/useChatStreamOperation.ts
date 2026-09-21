import { useEffect, useRef, useState } from 'react'
import { ChatStreamOperation } from './streamOperation'
import type { AuthenticatedRequest, RuntimeEvent } from './types'

export function useChatStreamOperation(request: AuthenticatedRequest) {
  const current = useRef<ChatStreamOperation | null>(null)
  const [runId, setRunId] = useState<string | null>(null)
  const [stopping, setStopping] = useState(false)
  useEffect(() => () => { current.current?.controller.abort(); current.current = null }, [])
  return {
    isActive: () => current.current !== null,
    canStop: runId !== null,
    stopping,
    begin: () => {
      if (current.current) return null
      const operation = new ChatStreamOperation()
      current.current = operation
      setRunId(null)
      return operation
    },
    onEvent: (operation: ChatStreamOperation, event: RuntimeEvent) => {
      if (current.current !== operation) return
      operation.accept(event)
      setRunId(operation.runId)
    },
    finish: (operation: ChatStreamOperation) => {
      if (current.current !== operation) return
      current.current = null
      setRunId(null)
    },
    stop: async () => {
      const operation = current.current
      if (!operation?.runId) return
      setStopping(true)
      try { await operation.cancel(request) }
      finally { setStopping(false) }
    },
  }
}
