import { describe, expect, it } from 'vitest'
import { animatedValueAt, animationDuration } from './countAnimation'

describe('animated metric values', () => {
  it('starts at zero, grows monotonically, and settles exactly', () => {
    const duration = animationDuration(1284)
    const values = [0, duration / 4, duration / 2, duration].map((elapsed) => animatedValueAt(1284, elapsed, duration))
    expect(values[0]).toBe(0)
    expect(values[1]).toBeGreaterThan(values[0])
    expect(values[2]).toBeGreaterThan(values[1])
    expect(values[3]).toBe(1284)
  })

  it('keeps zero and negative evidence at zero', () => {
    expect(animatedValueAt(0, 500, 1000)).toBe(0)
    expect(animatedValueAt(-1, 500, 1000)).toBe(0)
  })
})
