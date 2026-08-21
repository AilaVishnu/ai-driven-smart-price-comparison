import { Link } from 'react-router-dom'

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
    <div className="empty-state">
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

export function EmptyState({ title, children, action }) {
  return (
    <div className="empty-state">
      <p className="strong">{title}</p>
      {children && <p className="small muted">{children}</p>}
      {action}
    </div>
  )
}

export function SignInPrompt({ feature }) {
  return (
    <EmptyState
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
