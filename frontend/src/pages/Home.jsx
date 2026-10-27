import { Link } from 'react-router-dom'
import SearchBar from '../components/SearchBar.jsx'
import ProductCard from '../components/ProductCard.jsx'
import PlatformStatus from '../components/PlatformStatus.jsx'
import { CardsLoading, ErrorState } from '../components/Common.jsx'
import { api } from '../api/client.js'
import { useApi } from '../hooks/useApi.js'

export default function Home() {
  const deals = useApi(() => api.deals(8), [])
  // Asked of the server rather than filtered out of one page of search results,
  // which found one of six because the rest sat on later pages.
  const crossPlatform = useApi(() => api.crossPlatform(4), [])


  return (
    <div className="page">
      <section className="hero">
        <div className="container hero-inner">
          <h1 className="hero-title">
            Compare prices before you buy.
          </h1>
          <div className="hero-rule" />
          <p className="hero-sub">
            Search for a product and see what Amazon and Flipkart each charge for it,
            with ratings and reviews on the same page.
          </p>

          <div className="hero-search">
            <SearchBar autoFocus />
          </div>
        </div>
      </section>

      <div className="container stack" style={{ gap: 'var(--space-7)' }}>
        <PlatformStatus />

        {crossPlatform.data && crossPlatform.data.length > 0 && (
          <section>
            <div className="row section-head">
              <div>
                <h2>Sold on more than one site</h2>
                <p className="small muted" style={{ margin: 0 }}>
                  The same product on different platforms, so you can compare the price directly.
                </p>
              </div>
            </div>
            <div className="product-grid">
              {crossPlatform.data.map((product) => (
                <ProductCard key={product.id} product={product} />
              ))}
            </div>
          </section>
        )}

        <section>
          <div className="row section-head">
            <h2>Biggest discounts</h2>
            <span className="spacer" />
            <Link to="/deals" className="small">All deals →</Link>
          </div>

          {deals.loading && <CardsLoading count={8} />}
          {deals.error && <ErrorState error={deals.error} onRetry={deals.reload} />}
          {!deals.loading && !deals.error && deals.data && deals.data.length > 0 && (
            <div className="product-grid">
              {deals.data.map((product) => (
                <ProductCard key={product.id} product={product} />
              ))}
            </div>
          )}
          {!deals.loading && !deals.error && deals.data && deals.data.length === 0 && (
            <p className="muted small">
              Nothing discounted right now. Search for a product to get started.
            </p>
          )}
        </section>
      </div>
    </div>
  )
}
