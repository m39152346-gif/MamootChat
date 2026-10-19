import { avatarColorClass, initials } from '../lib/format'

export function Avatar({
  name,
  seed,
  online,
  size = 'md',
}: {
  name: string
  seed: string
  online?: boolean
  size?: 'sm' | 'md' | 'lg'
}) {
  return (
    <span className={`avatar avatar-${size} ${avatarColorClass(seed)}`} aria-hidden="true">
      <span className="avatar-initials">{initials(name)}</span>
      {online !== undefined && <span className={`presence-dot ${online ? 'is-online' : ''}`} />}
    </span>
  )
}
