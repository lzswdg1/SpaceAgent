import { useEffect, useRef, type RefObject } from 'react'

type Particle = {
  x: number; y: number; vx: number; vy: number
  angleSeed: number; targetRadius: number; z: number; phase: number
  speed: number; spin: number; size: number; alpha: number
  temperature: number; flicker: number; reveal: number; diskAffinity: number
  prevX: number; prevY: number
}

type Props = { progressRef?: RefObject<number>; variant?: 'hero' | 'auth' }
type TextBounds = { left: number; right: number; top: number; bottom: number; midY: number }

const TAU = Math.PI * 2
const clamp = (value: number, min = 0, max = 1) => Math.min(max, Math.max(min, value))
const mix = (from: number, to: number, amount: number) => from + (to - from) * amount
const smooth = (from: number, to: number, value: number) => {
  const amount = clamp((value - from) / (to - from))
  return amount * amount * (3 - 2 * amount)
}
const random = (from: number, to: number) => from + Math.random() * (to - from)

export function ParticleField({ progressRef, variant = 'hero' }: Props) {
  const canvasRef = useRef<HTMLCanvasElement>(null)

  useEffect(() => {
    const canvas = canvasRef.current
    if (!canvas) return
    const context = canvas.getContext('2d', { alpha: true })
    if (!context) return

    const reducedMotion = window.matchMedia('(prefers-reduced-motion: reduce)').matches
    let width = 0
    let height = 0
    let frame = 0
    let particles: Particle[] = []
    let pointerX = 0
    let pointerY = 0
    let pointerTargetX = 0
    let pointerTargetY = 0
    let last = performance.now()
    let textBounds: TextBounds | null = null

    const angleDensity = (angle: number) => {
      const first = Math.pow(Math.abs(Math.sin(angle * 1.21 + 0.68)), 0.72)
      const second = Math.pow(Math.abs(Math.sin(angle * 2.97 - 1.31)), 1.38)
      const third = Math.pow(Math.abs(Math.cos(angle * 0.57 + 2.08)), 1.74)
      return clamp(0.1 + first * 0.47 + second * 0.27 + third * 0.16, 0.08, 1)
    }
    const sampleAngle = () => {
      for (let attempt = 0; attempt < 14; attempt += 1) {
        const angle = Math.random() * TAU
        if (Math.random() < angleDensity(angle)) return angle
      }
      return Math.random() * TAU
    }
    const geometry = () => {
      const mobile = width < 768
      const offsetX = reducedMotion ? 0 : pointerX * width * (mobile ? 0.01 : 0.015)
      const offsetY = reducedMotion ? 0 : pointerY * height * (mobile ? 0.01 : 0.015)
      if (variant === 'auth') {
        return { cx: width * 0.82 + offsetX, cy: height * 0.74 + offsetY, scale: Math.max(width, height) * 0.86, flatten: 0.42, diskThickness: 0.055, outerFalloff: 1.8, horizonRadius: 0.16, photonRadius: 0.19 }
      }
      return { cx: width * (mobile ? 0.75 : 0.85) + offsetX, cy: height * (mobile ? 0.45 : 0.52) + offsetY, scale: Math.max(width, height) * (mobile ? 0.8 : 1.15), flatten: 0.38, diskThickness: 0.05, outerFalloff: 1.8, horizonRadius: 0.16, photonRadius: 0.19 }
    }
    const createParticle = (index: number): Particle => {
      const angle = sampleAngle()
      const stream = Math.random() < 0.25
      const distribution = Math.pow(Math.random(), 1.6)
      const base = stream ? mix(0.5, 1.4, Math.pow(Math.random(), 0.8)) : mix(0.2, 1.2, distribution)
      const irregularity = Math.sin(angle * 2.4 + index * 0.01) * 0.1 + Math.sin(angle * 0.8 + 1.2) * 0.05
      return {
        x: random(-0.2, 1.2) * width, y: random(-0.2, 1.2) * height, vx: 0, vy: 0,
        angleSeed: angle, targetRadius: clamp(base + irregularity, 0.12, 1.5), z: random(-1, 1),
        phase: Math.random() * TAU, speed: random(0.7, 1.4), spin: (Math.random() < 0.05 ? -1 : 1) * random(0.8, 1.2),
        size: Math.pow(Math.random(), 2.2) * 2 + 0.3, alpha: Math.pow(Math.random(), 1.5) * 0.8 + 0.1,
        temperature: Math.random(), flicker: random(0.4, 1.2), reveal: Math.pow(Math.random(), 1.2) * 0.8,
        diskAffinity: clamp(Math.pow(Math.random(), 0.6) * (stream ? 1.2 : 1), 0, 1), prevX: 0, prevY: 0,
      }
    }
    const build = () => {
      const count = width < 768 ? Math.min(900, Math.floor(width * height / 420)) : Math.min(3000, Math.floor(width * height / 500))
      particles = Array.from({ length: count }, (_, index) => {
        const particle = createParticle(index)
        particle.x = Math.random() * width
        particle.y = Math.random() * height
        particle.prevX = particle.x
        particle.prevY = particle.y
        return particle
      })
    }
    const updateTextBounds = (heroAlpha: number) => {
      if (heroAlpha < 0.05) { textBounds = null; return }
      const selector = variant === 'auth' ? '.auth-statement' : '.hero-content'
      const target = canvas.parentElement?.querySelector<HTMLElement>(selector) ?? document.querySelector<HTMLElement>(selector)
      if (!target) return
      const canvasRect = canvas.getBoundingClientRect()
      const targetRect = target.getBoundingClientRect()
      const paddingX = width < 768 ? 30 : variant === 'auth' ? 38 : 80
      const paddingY = width < 768 ? 30 : variant === 'auth' ? 42 : 60
      textBounds = { left: targetRect.left - canvasRect.left - paddingX, right: targetRect.right - canvasRect.left + paddingX, top: targetRect.top - canvasRect.top - paddingY, bottom: targetRect.bottom - canvasRect.top + paddingY, midY: (targetRect.top + targetRect.bottom) / 2 - canvasRect.top }
    }
    const resize = () => {
      const parentRect = canvas.parentElement?.getBoundingClientRect()
      width = variant === 'auth' ? Math.max(1, parentRect?.width ?? canvas.clientWidth) : Math.min(window.innerWidth, 2560)
      height = variant === 'auth' ? Math.max(1, parentRect?.height ?? canvas.clientHeight) : window.innerHeight
      const dpr = Math.min(window.devicePixelRatio || 1, width < 768 ? 1.2 : 1.75)
      canvas.width = Math.floor(width * dpr)
      canvas.height = Math.floor(height * dpr)
      canvas.style.width = `${width}px`
      canvas.style.height = `${height}px`
      context.setTransform(dpr, 0, 0, dpr, 0, 0)
      build()
    }
    const textForce = (particle: Particle, desired: { vx: number; vy: number }, deform: number, heroAlpha: number) => {
      if (!textBounds || heroAlpha < 0.05) return 1
      if (particle.x < textBounds.left || particle.x > textBounds.right || particle.y < textBounds.top || particle.y > textBounds.bottom) return 1
      const horizontal = clamp((textBounds.right - particle.x) / (textBounds.right - textBounds.left))
      const vertical = Math.abs(particle.y - textBounds.midY) / ((textBounds.bottom - textBounds.top) * 0.5)
      const force = smooth(1, 0.2, vertical) * smooth(0, 1, horizontal) * deform * heroAlpha
      desired.vx += 160 * force
      desired.vy += (particle.y < textBounds.midY ? -1 : 1) * 60 * force
      return 1 - force * 0.6
    }
    const draw = (now: number) => {
      const delta = Math.min(32, Math.max(1, now - last)) / 1000
      last = now
      pointerX = mix(pointerX, pointerTargetX, reducedMotion ? 1 : 0.04)
      pointerY = mix(pointerY, pointerTargetY, reducedMotion ? 1 : 0.04)
      context.clearRect(0, 0, width, height)

      const progress = variant === 'auth' ? 0.76 : progressRef?.current ?? 0
      const emerge = smooth(0.08, 0.25, progress)
      const deform = smooth(0.15, 0.45, progress)
      const collapse = smooth(0.35, 0.65, progress)
      const disk = smooth(0.5, 0.85, progress)
      const lens = smooth(0.6, 0.9, progress)
      const heroAlpha = variant === 'auth' ? 1 : smooth(0.72, 0.86, progress) * (1 - smooth(0.95, 1, progress))
      const time = reducedMotion ? 0 : now * 0.00015
      const shape = geometry()
      updateTextBounds(heroAlpha)
      canvas.style.opacity = variant === 'auth' ? '1' : String(1 - smooth(0.92, 0.98, progress))

      for (const particle of particles) {
        const flow = Math.sin(particle.x / width * 3 + time) + Math.cos(particle.y / height * 2.5 - time) + particle.phase
        const free = mix(10, 2, deform) * particle.speed
        const desired = { vx: Math.cos(flow) * free, vy: Math.sin(flow) * free }
        let dx = particle.x - shape.cx
        let dy = (particle.y - shape.cy) / shape.flatten
        let radius = Math.hypot(dx, dy) / shape.scale
        const angle = Math.atan2(dy, dx)
        const radialX = Math.cos(angle)
        const radialY = Math.sin(angle) * shape.flatten
        const tangentX = -Math.sin(angle)
        const tangentY = Math.cos(angle) * shape.flatten
        const preferred = particle.angleSeed + time * 0.15 * particle.spin
        const desiredRadius = clamp(particle.targetRadius * (1 + Math.sin(preferred * 2.4 + particle.phase) * 0.1) * mix(1.5, 0.95, collapse), 0.15, 1.6)
        let radial = (desiredRadius - radius) * shape.scale * mix(0.4, 1.2, deform) - deform * (5 + 12 / (radius + 0.2))
        if (radius < shape.horizonRadius + 0.02) radial += (shape.horizonRadius + 0.02 - radius) * shape.scale * 5
        let orbit = deform * mix(8, 28, (particle.z + 1) * 0.5) * particle.spin * (0.8 + 0.4 / (radius + 0.3))
        const plane = (particle.y - shape.cy) / (shape.scale * shape.flatten)
        const diskWidth = mix(0.3, shape.diskThickness * 2.5, disk)
        const close = Math.exp(-Math.pow(plane / Math.max(0.01, diskWidth), 2))
        desired.vy += -plane * shape.scale * disk * particle.diskAffinity * 0.5
        orbit *= 1 + close * disk * 1.5
        desired.vx += (radialX * radial + tangentX * orbit) * deform
        desired.vy += (radialY * radial + tangentY * orbit) * deform
        const exclusion = textForce(particle, desired, deform, heroAlpha)
        const response = mix(1.5, 6, deform)
        particle.vx += (desired.vx - particle.vx) * clamp(response * delta)
        particle.vy += (desired.vy - particle.vy) * clamp(response * delta)
        const drag = Math.pow(mix(0.99, 0.96, deform), delta * 60)
        particle.vx *= drag
        particle.vy *= drag
        particle.x += particle.vx * delta
        particle.y += particle.vy * delta

        const margin = width * 0.4
        if (particle.x < -margin || particle.x > width + margin || particle.y < -margin || particle.y > height + margin) {
          if (deform > 0.3) {
            const resetAngle = sampleAngle()
            const resetRadius = random(1, shape.outerFalloff)
            particle.x = shape.cx + Math.cos(resetAngle) * resetRadius * shape.scale
            particle.y = shape.cy + Math.sin(resetAngle) * resetRadius * shape.scale * shape.flatten
          }
          particle.prevX = particle.x
          particle.prevY = particle.y
        }

        dx = particle.x - shape.cx
        dy = (particle.y - shape.cy) / shape.flatten
        radius = Math.hypot(dx, dy) / shape.scale
        if (deform > 0.5 && radius < shape.horizonRadius) { particle.prevX = particle.x; particle.prevY = particle.y; continue }

        const hot = Math.exp(-Math.pow((radius - shape.photonRadius) / 0.15, 2)) * close
        let red: number; let green: number; let blue: number
        if (particle.temperature < 0.25) [red, green, blue] = [170, 182, 194]
        else if (particle.temperature < 0.5) [red, green, blue] = [143, 162, 179]
        else if (particle.temperature < 0.8) [red, green, blue] = [213, 222, 229]
        else [red, green, blue] = [244, 242, 236]
        const heat = hot * disk
        const finalRed = Math.round(mix(red, 255, heat * 0.9))
        const finalGreen = Math.round(mix(green, 248, heat * 0.9))
        const finalBlue = Math.round(mix(blue, 240, heat * 0.9))
        const depth = (particle.z + 1) * 0.5
        let alpha = particle.alpha * smooth(particle.reveal, particle.reveal + 0.2, emerge) * exclusion * mix(0.15, 1, depth)
        alpha *= reducedMotion ? 1 : 0.85 + Math.sin(now * 0.002 * particle.flicker + particle.phase) * 0.15
        if (alpha < 0.02) { particle.prevX = particle.x; particle.prevY = particle.y; continue }
        const size = particle.size * mix(0.3, 2.5, depth) * mix(0.8, 1.2, deform) * mix(1, 1.4, heat)
        const x = particle.x + pointerX * depth * 40
        const y = particle.y + pointerY * depth * 40
        context.beginPath()
        context.arc(x, y, Math.max(0.2, size), 0, TAU)
        context.fillStyle = `rgba(${finalRed},${finalGreen},${finalBlue},${alpha})`
        context.fill()

        const travelled = Math.hypot(x - particle.prevX, y - particle.prevY)
        if (deform > 0.5 && depth > 0.6 && alpha > 0.1 && travelled > 1 && travelled < 40) {
          context.beginPath(); context.moveTo(particle.prevX, particle.prevY); context.lineTo(x, y)
          context.strokeStyle = `rgba(${finalRed},${finalGreen},${finalBlue},${alpha * 0.4})`
          context.lineWidth = size * 0.8; context.stroke()
        }
        if (particle.z < 0 && lens > 0.1) {
          const lensStrength = lens * particle.diskAffinity * Math.exp(-Math.pow(radius / 0.8, 2))
          if (lensStrength > 0.05) {
            const relative = (particle.x - shape.cx) / shape.scale
            const mirroredY = shape.cy + (particle.y < shape.cy ? -1 : 1) * shape.scale * shape.flatten * (0.3 + 0.4 * (1 - clamp(Math.abs(relative) / 0.6)))
            const mirroredX = shape.cx + relative * shape.scale * 1.1
            context.beginPath(); context.arc(mirroredX, mirroredY, Math.max(0.2, size * mix(1, 1.5, lensStrength)), 0, TAU)
            context.fillStyle = `rgba(${finalRed},${finalGreen},${finalBlue},${alpha * lensStrength * 0.6})`; context.fill()
          }
        }
        particle.prevX = x
        particle.prevY = y
      }
      if (!reducedMotion) frame = window.requestAnimationFrame(draw)
    }

    const onPointerMove = (event: PointerEvent) => {
      if (reducedMotion) return
      const bounds = canvas.getBoundingClientRect()
      pointerTargetX = ((event.clientX - bounds.left) / Math.max(1, bounds.width) - 0.5) * 2
      pointerTargetY = ((event.clientY - bounds.top) / Math.max(1, bounds.height) - 0.5) * 2
    }
    const onPointerLeave = () => { pointerTargetX = 0; pointerTargetY = 0 }
    const onScroll = () => { if (reducedMotion) draw(performance.now()) }
    const onVisibilityChange = () => { last = performance.now() }

    resize()
    const resizeObserver = variant === 'auth' && canvas.parentElement ? new ResizeObserver(resize) : null
    resizeObserver?.observe(canvas.parentElement!)
    window.addEventListener('resize', resize, { passive: true })
    window.addEventListener('pointermove', onPointerMove, { passive: true })
    window.addEventListener('pointerleave', onPointerLeave, { passive: true })
    window.addEventListener('scroll', onScroll, { passive: true })
    document.addEventListener('visibilitychange', onVisibilityChange)
    if (reducedMotion) draw(0)
    else frame = window.requestAnimationFrame(draw)

    return () => {
      window.cancelAnimationFrame(frame)
      resizeObserver?.disconnect()
      window.removeEventListener('resize', resize)
      window.removeEventListener('pointermove', onPointerMove)
      window.removeEventListener('pointerleave', onPointerLeave)
      window.removeEventListener('scroll', onScroll)
      document.removeEventListener('visibilitychange', onVisibilityChange)
    }
  }, [progressRef, variant])

  return <canvas ref={canvasRef} className={variant === 'auth' ? 'auth-particle-field' : 'particle-field'} aria-hidden="true" />
}
