export const animationDuration = (value: number) => Math.min(1800, Math.max(650, 700 + Math.log10(Math.max(1, value)) * 220))

export const animatedValueAt = (target: number, elapsed: number, duration: number) => {
  if (target <= 0 || elapsed <= 0) return 0
  if (elapsed >= duration) return target
  const progress = elapsed / duration
  const eased = 1 - Math.pow(1 - progress, 3)
  return Math.min(target, Math.round(target * eased))
}
