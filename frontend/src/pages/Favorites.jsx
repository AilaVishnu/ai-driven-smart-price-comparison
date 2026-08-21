import { Link } from 'react-router-dom'
import ProductCard from '../components/ProductCard.jsx'
import { CardsLoading, EmptyState, ErrorState, SignInPrompt } from '../components/Common.jsx'
import { api } from '../api/client.js'
import { useApi } from '../hooks/useApi.js'
import { useApp } from '../hooks/AppContext.jsx'

export default function Favorites() {
  const { user, authChecked, favoriteIds } = useApp()

  const { data, error, loading, reload } = useApi(() => api.favorites(), [user, favoriteIds.length], {
    skip: !user,
  })

  if (!authChecked) return <div className="page container"><CardsLoading count={4} /></div>
  if (!user) return <div className="page container"><SignInPrompt feature="saved products" /></div>

  return (
    <div className="page container stack">
      <div>
        <h1>Saved products</h1>
        <p className="muted small">Products you have saved to your account.</p>
      </div>

      {loading && <CardsLoading count={8} />}
      {error && <ErrorState error={error} onRetry={reload} />}

      {!loading && !error && data && data.length === 0 && (
        <EmptyState
          title="Nothing saved yet"
          action={<Link className="btn btn-primary" to="/search" style={{ marginTop: 'var(--space-3)' }}>
            Find something
          </Link>}
        >
          Use the heart on a product to keep it here.
        </EmptyState>
      )}

      {!loading && !error && data && data.length > 0 && (
        <div className="product-grid">
          {data.map((favorite) => (
            <ProductCard key={favorite.id} product={favorite.product} />
          ))}
        </div>
      )}
    </div>
  )
}
