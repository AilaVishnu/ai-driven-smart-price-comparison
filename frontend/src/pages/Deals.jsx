import ProductCard from '../components/ProductCard.jsx'
import { CardsLoading, EmptyState, ErrorState } from '../components/Common.jsx'
import { api } from '../api/client.js'
import { useApi } from '../hooks/useApi.js'

export default function Deals() {
  const { data, error, loading, reload } = useApi(() => api.deals(36), [])

  return (
    <div className="page container stack">
      <div>
        <h1>Deals</h1>
        <p className="muted small">
          Live listings with the largest discount against their stated original price.
        </p>
      </div>

      {loading && <CardsLoading count={12} />}
      {error && <ErrorState error={error} onRetry={reload} />}

      {!loading && !error && data && data.length === 0 && (
        <EmptyState title="No discounted listings yet">
          Discounts appear as the catalogue fills in from the connected platforms.
        </EmptyState>
      )}

      {!loading && !error && data && data.length > 0 && (
        <div className="product-grid">
          {data.map((product) => (
            <ProductCard key={product.id} product={product} />
          ))}
        </div>
      )}
    </div>
  )
}
