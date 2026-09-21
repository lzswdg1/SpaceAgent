import { useEffect, useState } from 'react'
import { animatedValueAt, animationDuration } from './countAnimation'

export function useAnimatedNumber(target: number, replayKey: string) {
  const [value, setValue] = useState(0)
  useEffect(() => {
    if (window.matchMedia('(prefers-reduced-motion: reduce)').matches) {
      setValue(target)
      return
    }
    setValue(0)
    const duration = animationDuration(target)
    const startedAt = performance.now()
    let frame = 0
    const tick = (time: number) => {
      setValue(animatedValueAt(target, time - startedAt, duration))
      if (time - startedAt < duration) frame = requestAnimationFrame(tick)
    }
    frame = requestAnimationFrame(tick)
    return () => cancelAnimationFrame(frame)
  }, [target, replayKey])
  return value
}
