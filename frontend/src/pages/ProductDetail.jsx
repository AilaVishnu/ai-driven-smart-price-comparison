import { Link, useParams } from 'react-router-dom'
import OfferTable from '../components/OfferTable.jsx'
import SentimentPanel from '../components/SentimentPanel.jsx'
import PriceHistoryChart from '../components/PriceHistoryChart.jsx'
import BuySignal from '../components/BuySignal.jsx'
import ProductCard from '../components/ProductCard.jsx'
import { ErrorState, Loading } from '../components/Common.jsx'
import { api } from '../api/client.js'
import { useApi } from '../hooks/useApi.js'
import { useApp } from '../hooks/AppContext.jsx'
import { formatCount, formatPrice, formatRating } from '../utils/format.js'

export default function ProductDetail() {
  const { id } = useParams()
  const { data, error, loading, reload } = useApi((signal) => api.product(id, signal), [id])
  const { inCompare, toggleCompare, user, favoriteIds, toggleFavorite } = useApp()

  if (loading) {
    return (
      <div className="page container">
        <Loading rows={6} label="Loading product" />
      </div>
    )
  }
  if (error) {
    return (
      <div className="page container">
        <ErrorState error={error} onRetry={reload} />
      </div>
    )
  }
  if (!data) return null

  const { summary, offers, sentiment, forecast, priceHistory, similar, description, reviews } = data
  const productId = summary.id
  const selected = inCompare(productId)
  const favorited = favoriteIds.includes(productId)
  const rating = formatRating(summary.rating)
  const saving = Number(summary.potentialSaving || 0)

  return (
    <div className="page">
      <div className="container stack" style={{ gap: 'var(--space-6)' }}>
        <nav className="xs subtle">
          <Link to="/">Home</Link> / <Link to="/search">Search</Link> / <span>{summary.title}</span>
        </nav>

        <section className="detail-hero">
          <div className="detail-media card">
            {summary.imageUrl ? (
              <img src={summary.imageUrl} alt={summary.title} />
            ) : (
              <div className="product-card-placeholder" aria-hidden="true">📦</div>
            )}
          </div>

          <div className="detail-summary stack">
            <div>
              {summary.brand && <span className="badge">{summary.brand}</span>}
              <h1 className="detail-title">{summary.title}</h1>
            </div>

            <div className="row-wrap">
              {rating && (
                <span className="small">
                  ★ {rating}
                  <span className="subtle"> from {formatCount(summary.ratingCount)} ratings</span>
                </span>
              )}
              <span className="badge">
                {summary.offerCount} listing{summary.offerCount === 1 ? '' : 's'}
              </span>
              {summary.platformCount > 1 && (
                <span className="badge badge-accent">
                  on {summary.platformCount} platforms
                </span>
              )}
            </div>

            <div className="detail-price-block">
              <div className="detail-price">{formatPrice(summary.bestPrice)}</div>
              <div className="xs subtle">
                best of {summary.offerCount} listing{summary.offerCount === 1 ? '' : 's'}
                {summary.bestPlatformName && <> · {summary.bestPlatformName}</>}
              </div>
              {saving > 0 && (
                <div className="small detail-saving">
                  Buying the cheapest saves {formatPrice(saving)} against the dearest listing.
                </div>
              )}
            </div>

            <div className="row-wrap">
              <button
                type="button"
                className={selected ? 'btn btn-primary' : 'btn'}
                onClick={() => toggleCompare(productId)}
                aria-pressed={selected}
              >
                {selected ? 'In comparison' : 'Add to compare'}
              </button>
              {user && (
                <button
                  type="button"
                  className="btn"
                  onClick={() => toggleFavorite(productId)}
                  aria-pressed={favorited}
                >
                  {favorited ? '♥ Saved' : '♡ Save'}
                </button>
              )}
            </div>

            {description && <p className="small muted clamp-4">{description}</p>}
          </div>
        </section>

        <section className="stack">
          <h2 className="section-head">Where to buy</h2>
          <div className="card">
            <OfferTable offers={offers} />
          </div>
        </section>

        <section className="detail-two-col">
          <div className="card card-padded stack">
            <h2 style={{ fontSize: 'var(--text-lg)' }}>Price history</h2>
            <PriceHistoryChart
              points={priceHistory}
              containsSimulated={forecast && forecast.containsSimulatedData}
            />
          </div>

          <div className="card card-padded stack">
            <h2 style={{ fontSize: 'var(--text-lg)' }}>Buy now or wait?</h2>
            <BuySignal forecast={forecast} />
          </div>
        </section>

        <section className="card card-padded stack">
          <h2 style={{ fontSize: 'var(--text-lg)' }}>What reviewers say</h2>
          <SentimentPanel sentiment={sentiment} />

          {reviews && reviews.length > 0 && (
            <details className="review-details">
              <summary className="small">Read {reviews.length} review{reviews.length === 1 ? '' : 's'}</summary>
              <ul className="review-list">
                {reviews.slice(0, 20).map((review) => (
                  <li key={review.id} className="review-item">
                    <div className="row-wrap">
                      <strong className="small">{review.author || 'Anonymous'}</strong>
                      {review.rating && <span className="xs subtle">★ {formatRating(review.rating)}</span>}
                      {review.sentimentLabel && (
                        <span
                          className={
                            review.sentimentLabel === 'POSITIVE'
                              ? 'badge badge-positive'
                              : review.sentimentLabel === 'NEGATIVE'
                                ? 'badge badge-negative'
                                : 'badge'
                          }
                        >
                          {review.sentimentLabel.toLowerCase()}
                        </span>
                      )}
                    </div>
                    <p className="small muted" style={{ margin: 0 }}>{review.body}</p>
                  </li>
                ))}
              </ul>
            </details>
          )}
        </section>

        {similar && similar.length > 0 && (
          <section className="stack">
            <h2 className="section-head">Similar products</h2>
            <div className="product-grid">
              {similar.map((product) => (
                <ProductCard key={product.id} product={product} />
              ))}
            </div>
          </section>
        )}
      </div>
    </div>
  )
}
