import { Link } from 'react-router-dom'
import { EmptyState, ErrorState, Loading, SignInPrompt } from '../components/Common.jsx'
import { api } from '../api/client.js'
import { useApi } from '../hooks/useApi.js'
import { useApp } from '../hooks/AppContext.jsx'
import { formatRelative } from '../utils/format.js'

export default function History() {
  const { user, authChecked } = useApp()

  const searches = useApi(() => api.searchHistory(), [user], { skip: !user })
  const comparisons = useApi(() => api.compareHistory(), [user], { skip: !user })

  if (!authChecked) return <div className="page container"><Loading rows={4} /></div>
  if (!user) return <div className="page container"><SignInPrompt feature="history" /></div>

  return (
    <div className="page container stack" style={{ gap: 'var(--space-6)' }}>
      <div>
        <h1>History</h1>
        <p className="muted small">What you have searched for and compared.</p>
      </div>

      <section className="stack">
        <h2 className="section-head">Recent searches</h2>
        {searches.loading && <Loading rows={3} />}
        {searches.error && <ErrorState error={searches.error} onRetry={searches.reload} />}
        {!searches.loading && !searches.error && searches.data && searches.data.length === 0 && (
          <EmptyState title="No searches recorded yet">
            Searches you run while signed in appear here.
          </EmptyState>
        )}
        {!searches.loading && searches.data && searches.data.length > 0 && (
          <ul className="history-list card">
            {searches.data.map((entry) => (
              <li key={entry.id} className="history-item">
                <Link to={`/search?q=${encodeURIComponent(entry.query)}`} className="history-query">
                  {entry.query}
                </Link>
                <span className="xs subtle">
                  {entry.resultCount} result{entry.resultCount === 1 ? '' : 's'}
                </span>
                <span className="xs subtle">{formatRelative(entry.createdAt)}</span>
              </li>
            ))}
          </ul>
        )}
      </section>

      <section className="stack">
        <h2 className="section-head">Recent comparisons</h2>
        {comparisons.loading && <Loading rows={3} />}
        {comparisons.error && <ErrorState error={comparisons.error} onRetry={comparisons.reload} />}
        {!comparisons.loading && !comparisons.error && comparisons.data && comparisons.data.length === 0 && (
          <EmptyState title="No comparisons recorded yet">
            Comparisons you run while signed in appear here, along with which product won.
          </EmptyState>
        )}
        {!comparisons.loading && comparisons.data && comparisons.data.length > 0 && (
          <ul className="history-list card">
            {comparisons.data.map((entry) => (
              <li key={entry.id} className="history-item">
                <span className="small">
                  {entry.productIds.length} products compared
                </span>
                {entry.winnerProductId && (
                  <Link to={`/product/${entry.winnerProductId}`} className="xs">
                    winner: #{entry.winnerProductId}
                  </Link>
                )}
                <span className="xs subtle">{formatRelative(entry.createdAt)}</span>
              </li>
            ))}
          </ul>
        )}
      </section>
    </div>
  )
}
