import { Link } from 'react-router-dom'

export default function NotFound() {
  return (
    <div className="page container">
      <div className="empty-state empty-state-boxed">
        <span className="empty-state-icon" aria-hidden="true" style={{ fontSize: 'var(--text-sm)', fontWeight: 700 }}>404</span>
        <h1 style={{ fontSize: 'var(--text-2xl)' }}>Page not found</h1>
        <p className="muted small">That link does not lead anywhere.</p>
        <Link className="btn btn-primary" to="/" style={{ marginTop: 'var(--space-3)' }}>
          Back to search
        </Link>
      </div>
    </div>
  )
}
