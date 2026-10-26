import { Link } from 'react-router-dom'

/*
 * State icons.
 *
 * Drawn as inline SVG rather than pulled from an emoji font so they inherit
 * currentColor and stay monochrome and on-brand in both themes, matching the
 * approach already used for the charts.
 */
const ICONS = {
  search: (
    <>
      <circle cx="11" cy="11" r="7" />
      <line x1="16.5" y1="16.5" x2="21" y2="21" />
    </>
  ),
  alert: (
    <>
      <path d="M12 3 22 20 2 20 Z" />
      <line x1="12" y1="10" x2="12" y2="14" />
      <line x1="12" y1="17" x2="12" y2="17.01" />
    </>
  ),
  offline: (
    <>
      <path d="M4 14a5 5 0 0 1 5-5 6 6 0 0 1 11 2 3.5 3.5 0 0 1 1 6.8" />
      <line x1="3" y1="3" x2="21" y2="21" />
    </>
  ),
  lock: (
    <>
      <rect x="5" y="11" width="14" height="9" rx="2" />
      <path d="M8 11V8a4 4 0 0 1 8 0v3" />
    </>
  ),
}

function StateIcon({ name = 'search', tone }) {
  return (
    <span className={tone === 'negative' ? 'empty-state-icon empty-state-icon-negative' : 'empty-state-icon'} aria-hidden="true">
      <svg
        width="24"
        height="24"
        viewBox="0 0 24 24"
        fill="none"
        stroke="currentColor"
        strokeWidth="1.7"
        strokeLinecap="round"
        strokeLinejoin="round"
      >
        {ICONS[name] || ICONS.search}
      </svg>
    </span>
  )
}

export function Loading({ label = 'Loading', rows = 3 }) {
  return (
    <div className="stack" aria-busy="true" aria-live="polite">
      <span className="visually-hidden">{label}</span>
      {Array.from({ length: rows }, (_, i) => (
        <div key={i} className="skeleton" style={{ height: 18, width: `${90 - i * 14}%` }} />
      ))}
    </div>
  )
}

export function CardsLoading({ count = 8 }) {
  return (
    <div className="product-grid" aria-busy="true">
      {Array.from({ length: count }, (_, i) => (
        <div key={i} className="card" style={{ overflow: 'hidden' }}>
          <div className="skeleton" style={{ height: 150, borderRadius: 0 }} />
          <div className="stack" style={{ padding: 'var(--space-3)', gap: 'var(--space-2)' }}>
            <div className="skeleton" style={{ height: 14, width: '92%' }} />
            <div className="skeleton" style={{ height: 14, width: '60%' }} />
            <div className="skeleton" style={{ height: 22, width: '45%' }} />
          </div>
        </div>
      ))}
    </div>
  )
}

/**
 * Error state.
 *
 * <p>The most common failure by far during local development is the backend not
 * running, and a bare "failed to fetch" sends people hunting in the wrong place.
 * When the request never reached a server, say so and name the fix.
 */
export function ErrorState({ error, onRetry }) {
  const offline = error && error.status === 0

  return (
    <div className="empty-state empty-state-boxed">
      <StateIcon name={offline ? 'offline' : 'alert'} tone="negative" />
      <p className="strong">{offline ? 'Cannot reach the server' : 'Something went wrong'}</p>
      <p className="small muted">{error && error.message ? error.message : 'Unknown error'}</p>
      {offline && (
        <p className="xs subtle">
          Start the backend with <code className="mono">mvnw spring-boot:run</code> in the backend
          folder, then try again.
        </p>
      )}
      {onRetry && (
        <button type="button" className="btn" onClick={onRetry} style={{ marginTop: 'var(--space-3)' }}>
          Try again
        </button>
      )}
    </div>
  )
}

export function EmptyState({ title, children, action, icon = 'search' }) {
  return (
    <div className="empty-state empty-state-boxed">
      {icon && <StateIcon name={icon} />}
      <p className="strong">{title}</p>
      {children && <p className="small muted">{children}</p>}
      {action}
    </div>
  )
}

export function SignInPrompt({ feature }) {
  return (
    <EmptyState
      icon="lock"
      title={`Sign in to use ${feature}`}
      action={
        <div className="row" style={{ justifyContent: 'center', marginTop: 'var(--space-3)' }}>
          <Link className="btn btn-primary" to="/login">Sign in</Link>
          <Link className="btn" to="/register">Create an account</Link>
        </div>
      }
    >
      Your {feature} are kept to your account.
    </EmptyState>
  )
}
