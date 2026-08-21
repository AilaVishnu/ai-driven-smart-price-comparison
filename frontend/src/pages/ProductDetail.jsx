import { Link, useParams } from 'react-router-dom'
import OfferTable from '../components/OfferTable.jsx'
import SentimentPanel from '../components/SentimentPanel.jsx'
import PriceHistoryChart from '../components/PriceHistoryChart.jsx'
import PriceComparison from '../components/PriceComparison.jsx'
import BuySignal from '../components/BuySignal.jsx'
import ProductCard from '../components/ProductCard.jsx'
import { ErrorState, Loading } from '../components/Common.jsx'
import { api } from '../api/client.js'
import { useApi } from '../hooks/useApi.js'
import { useApp } from '../hooks/AppContext.jsx'
import {
  formatCount,
  formatPercent,
  formatPrice,
  formatRating,
  platformAccent,
} from '../utils/format.js'

export default function ProductDetail() {
  const { id } = useParams()
  const { data, error, loading, reload } = useApi((signal) => api.product(id, signal), [id])
  const { inCompare, toggleCompare, user, favoriteIds, toggleFavorite } = useApp()

  if (loading) {
    return <div className="page container"><Loading rows={6} label="Loading product" /></div>
  }
  if (error) {
    return <div className="page container"><ErrorState error={error} onRetry={reload} /></div>
  }
  if (!data) return null

  const { summary, offers, sentiment, forecast, priceHistory, similar, description, reviews } = data
  const productId = summary.id
  const selected = inCompare(productId)
  const favorited = favoriteIds.includes(productId)
  const rating = formatRating(summary.rating)
  const saving = Number(summary.potentialSaving || 0)
  const discount = Number(summary.maxDiscountPct || 0)

  return (
    <div className="page">
      <div className="container stack" style={{ gap: 'var(--space-6)' }}>
        <nav className="breadcrumb xs" aria-label="Breadcrumb">
          <Link to="/">Home</Link>
          <span aria-hidden="true">/</span>
          {summary.category ? (
            <>
              <Link to={`/search?category=${summary.category}`}>{summary.category}</Link>
              <span aria-hidden="true">/</span>
            </>
          ) : null}
          <span className="subtle truncate">{summary.title}</span>
        </nav>

        <section className="detail-hero">
          <div className="detail-media">
            {summary.imageUrl ? (
              <img src={summary.imageUrl} alt={summary.title} />
            ) : (
              <div className="pcard-noimage">📦</div>
            )}
            {discount > 0 && <span className="flag flag-save detail-flag">{formatPercent(discount)} off</span>}
          </div>

          <div className="detail-summary">
            <div className="detail-head">
              {summary.brand && <span className="pcard-brand">{summary.brand}</span>}
              <h1 className="detail-title">{summary.title}</h1>

              <div className="row-wrap detail-meta">
                {rating && (
                  <span className="detail-rating">
                    <span className="stars" aria-hidden="true">★</span>
                    <strong>{rating}</strong>
                    <span className="subtle">{formatCount(summary.ratingCount)} ratings</span>
                  </span>
                )}
                <span className="badge">
                  {summary.offerCount} listing{summary.offerCount === 1 ? '' : 's'}
                </span>
                {summary.platformCount > 1 && (
                  <span className="badge badge-accent">on {summary.platformCount} platforms</span>
                )}
                {summary.inStock === false && <span className="badge badge-negative">Out of stock</span>}
              </div>
            </div>

            <div className="detail-price-card">
              <div className="detail-price-top">
                <div>
                  <span className="detail-price">{formatPrice(summary.bestPrice)}</span>
                  <span className="xs subtle detail-price-note">
                    lowest of {summary.offerCount} listing{summary.offerCount === 1 ? '' : 's'}
                    {summary.bestPlatformName && (
                      <>
                        {' on '}
                        <span
                          className="platform-dot"
                          style={{ background: platformAccent(summary.bestPlatformCode) }}
                          aria-hidden="true"
                        />
                        {summary.bestPlatformName}
                      </>
                    )}
                  </span>
                </div>
                {saving > 0 && (
                  <div className="detail-saving-pill">
                    <span className="xs">You save</span>
                    <strong>{formatPrice(saving)}</strong>
                  </div>
                )}
              </div>

              <PriceComparison
                platformPrices={summary.platformPrices}
                saving={summary.potentialSaving}
              />
            </div>

            <div className="row-wrap detail-actions">
              <button
                type="button"
                className={selected ? 'btn btn-primary' : 'btn'}
                onClick={() => toggleCompare(productId)}
                aria-pressed={selected}
              >
                {selected ? '✓ In comparison' : 'Add to compare'}
              </button>
              {user && (
                <button
                  type="button"
                  className="btn"
                  onClick={() => toggleFavorite(productId)}
                  aria-pressed={favorited}
                >
                  <span className={favorited ? 'heart heart-on' : 'heart'} aria-hidden="true">
                    {favorited ? '♥' : '♡'}
                  </span>
                  {favorited ? 'Saved' : 'Save'}
                </button>
              )}
            </div>

            {description && <p className="small muted clamp-4 detail-desc">{description}</p>}
          </div>
        </section>

        <section>
          <h2 className="section-title">Where to buy</h2>
          <div className="card">
            <OfferTable offers={offers} />
          </div>
        </section>

        <section className="detail-two-col">
          <div className="card card-padded stack">
            <h2 className="section-title">Price history</h2>
            <PriceHistoryChart
              points={priceHistory}
              containsSimulated={forecast && forecast.containsSimulatedData}
            />
          </div>

          <div className="card card-padded stack">
            <h2 className="section-title">Buy now, or wait?</h2>
            <BuySignal forecast={forecast} />
          </div>
        </section>

        <section className="card card-padded stack">
          <h2 className="section-title">What reviewers say</h2>
          <SentimentPanel sentiment={sentiment} />

          {reviews && reviews.length > 0 && (
            <details className="review-details">
              <summary className="small">
                Read {reviews.length} review{reviews.length === 1 ? '' : 's'}
              </summary>
              <ul className="review-list">
                {reviews.slice(0, 20).map((review) => (
                  <li key={review.id} className="review-item">
                    <div className="row-wrap">
                      <strong className="small">{review.author || 'Anonymous'}</strong>
                      {review.rating && (
                        <span className="xs subtle">★ {formatRating(review.rating)}</span>
                      )}
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
          <section>
            <h2 className="section-title">Similar products</h2>
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
