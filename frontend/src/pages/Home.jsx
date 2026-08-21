import { useMemo } from 'react'
import { Link } from 'react-router-dom'
import SearchBar from '../components/SearchBar.jsx'
import ProductCard from '../components/ProductCard.jsx'
import PlatformStatus from '../components/PlatformStatus.jsx'
import { CardsLoading, ErrorState } from '../components/Common.jsx'
import { api } from '../api/client.js'
import { useApi } from '../hooks/useApi.js'
import { formatCount, formatPrice } from '../utils/format.js'

export default function Home() {
  const deals = useApi(() => api.deals(8), [])
  // One broad page, used both for the headline counts and to surface products
  // that genuinely appear on more than one platform.
  const catalogue = useApi(() => api.search({ q: '', size: 100 }), [])
  const platforms = useApi(() => api.platforms(), [])

  const crossPlatform = useMemo(() => {
    if (!catalogue.data) return []
    return catalogue.data.products
      .filter((p) => p.platformCount > 1)
      .sort((a, b) => Number(b.potentialSaving || 0) - Number(a.potentialSaving || 0))
      .slice(0, 4)
  }, [catalogue.data])

  const biggestSaving = crossPlatform.length ? crossPlatform[0].potentialSaving : null
  const liveCount = platforms.data ? platforms.data.filter((p) => p.live).length : null

  return (
    <div className="page">
      <section className="hero">
        <div className="container hero-inner">
          <span className="hero-eyebrow">Across Amazon.in, Flipkart and more</span>
          <h1 className="hero-title">
            One search.<br />Every price.
          </h1>
          <p className="hero-sub muted">
            The same product, found on every platform that stocks it, ranked by what matters to
            you — not by who paid to be first.
          </p>

          <div className="hero-search">
            <SearchBar autoFocus />
          </div>

          {/* Real figures from the running catalogue, not marketing copy. */}
          <dl className="hero-stats">
            <div>
              <dt>Products</dt>
              <dd>{catalogue.data ? formatCount(catalogue.data.totalResults) : '—'}</dd>
            </div>
            <div>
              <dt>Live sources</dt>
              <dd>{liveCount === null ? '—' : liveCount}</dd>
            </div>
            <div>
              <dt>Biggest saving</dt>
              <dd>{biggestSaving ? formatPrice(biggestSaving) : '—'}</dd>
            </div>
          </dl>
        </div>
      </section>

      <div className="container stack" style={{ gap: 'var(--space-7)' }}>
        <PlatformStatus />

        {crossPlatform.length > 0 && (
          <section>
            <div className="row section-head">
              <div>
                <h2>Same product, different price</h2>
                <p className="small muted" style={{ margin: 0 }}>
                  Found on more than one platform, so the gap is real money.
                </p>
              </div>
            </div>
            <div className="product-grid">
              {crossPlatform.map((product) => (
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
              No discounted listings yet. Search for something and the catalogue fills in.
            </p>
          )}
        </section>

        <section className="how-it-works">
          <h2 className="section-head">How the ranking works</h2>
          <div className="how-grid">
            <article className="how-card">
              <span className="how-step">1</span>
              <h3>Listings are matched</h3>
              <p className="small muted">
                Platforms title the same product differently. A TF-IDF and model-signature matcher
                works out which listings are genuinely the same item, so prices compare like for
                like — and refuses to merge an iPhone 16 with a 16e.
              </p>
            </article>
            <article className="how-card">
              <span className="how-step">2</span>
              <h3>Reviews are read</h3>
              <p className="small muted">
                Customer reviews are scored for sentiment and split by aspect, so you can see the
                battery is well liked even where delivery is not.
              </p>
            </article>
            <article className="how-card">
              <span className="how-step">3</span>
              <h3>Options are ranked</h3>
              <p className="small muted">
                A TOPSIS model weighs price, rating, discount, sentiment and delivery. You set the
                weights, and every score shows its working.
              </p>
            </article>
          </div>
        </section>
      </div>
    </div>
  )
}
