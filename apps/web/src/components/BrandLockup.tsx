type Props = {
  className?: string
  showMotto?: boolean
}

export function BrandLockup({ className = '', showMotto = false }: Props) {
  return (
    <span className={`brand-lockup ${showMotto ? 'has-motto' : ''} ${className}`.trim()}>
      <span className="brand-wordmark"><span>Space</span><span>Agent</span></span>
      {showMotto && (
        <small className="brand-motto">
          <span>— Do not go gentle into that good night.</span>
          <span>Rage, rage against the dying of the light.</span>
        </small>
      )}
    </span>
  )
}
