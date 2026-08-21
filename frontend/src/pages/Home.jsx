import { Link } from 'react-router-dom'
import SearchBar from '../components/SearchBar.jsx'
import ProductCard from '../components/ProductCard.jsx'
import PlatformStatus from '../components/PlatformStatus.jsx'
import { CardsLoading, ErrorState } from '../components/Common.jsx'
import { api } from '../api/client.js'
import { useApi } from '../hooks/useApi.js'

export default function Home() {
  const { data: deals, error, loading, reload } = useApi((signal) => api.deals(8), [])

  return (
    <div className="page">
      <section className="hero">
        <div className="container stack" style={{ alignItems: 'center', textAlign: 'center' }}>
          <h1 className="hero-title">
            One search. Every price.
          </h1>
          <p className="hero-sub muted">
            Compare prices, ratings and reviews across shopping platforms, ranked by what
            actually matters to you.
          </p>
          <div className="hero-search">
            <SearchBar autoFocus />
          </div>
        </div>
      </section>

      <section className="container" style={{ marginTop: 'var(--space-6)' }}>
        <PlatformStatus />
      </section>

      <section className="container" style={{ marginTop: 'var(--space-6)' }}>
        <div className="row section-head">
          <h2>Biggest discounts</h2>
          <span className="spacer" />
          <Link to="/deals" className="small">See all deals</Link>
        </div>

        {loading && <CardsLoading count={8} />}
        {error && <ErrorState error={error} onRetry={reload} />}
        {!loading && !error && deals && deals.length > 0 && (
          <div className="product-grid">
            {deals.map((product) => (
              <ProductCard key={product.id} product={product} />
            ))}
          </div>
        )}
        {!loading && !error && deals && deals.length === 0 && (
          <p className="muted small">
            No discounted listings yet. Search for something and the catalogue will fill in.
          </p>
        )}
      </section>

      <section className="container how-it-works">
        <h2 className="section-head">How the ranking works</h2>
        <div className="how-grid">
          <div className="card card-padded">
            <h3>1. Listings are matched</h3>
            <p className="small muted">
              Platforms title the same product differently. A TF-IDF and model-signature
              matcher works out which listings are genuinely the same item, so prices can be
              compared like for like.
            </p>
          </div>
          <div className="card card-padded">
            <h3>2. Reviews are read</h3>
            <p className="small muted">
              Customer reviews are scored for sentiment and broken down by aspect, so you can
              see that the battery is well liked even where delivery is not.
            </p>
          </div>
          <div className="card card-padded">
            <h3>3. Options are ranked</h3>
            <p className="small muted">
              A TOPSIS multi-criteria model weighs price, rating, discount, sentiment and
              delivery. You set the weights, and every score shows its working.
            </p>
          </div>
        </div>
      </section>
    </div>
  )
}
