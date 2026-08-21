import { Link } from 'react-router-dom'
import { useApp } from '../hooks/AppContext.jsx'
import PriceComparison from './PriceComparison.jsx'
import ValueScore from './ValueScore.jsx'
import {
  formatCount,
  formatPercent,
  formatPrice,
  formatRating,
  platformAccent,
} from '../utils/format.js'

/**
 * A product in a results grid.
 *
 * <p>Laid out so the answer is readable without stopping to parse it: price is
 * the largest thing on the card, and directly beneath it sits the per-platform
 * comparison. Previously the card said only that two platforms carried the
 * product and left the shopper to open it to find out what either charged,
 * which buried the one thing the application exists to tell them.
 */
export default function ProductCard({ product }) {
  const { inCompare, toggleCompare, user, favoriteIds, toggleFavorite } = useApp()

  const selected = inCompare(product.id)
  const favorited = favoriteIds.includes(product.id)
  const rating = formatRating(product.rating)
  const multiPlatform = product.platformCount > 1
  const discount = Number(product.maxDiscountPct || 0)

  return (
    <article className={`pcard${selected ? ' pcard-selected' : ''}`}>
      <Link to={`/product/${product.id}`} className="pcard-media" tabIndex={-1} aria-hidden="true">
        {product.imageUrl ? (
          <img src={product.imageUrl} alt="" loading="lazy" />
        ) : (
          <div className="pcard-noimage">📦</div>
        )}

        <div className="pcard-flags">
          {discount > 0 && <span className="flag flag-save">{formatPercent(discount)} off</span>}
          {multiPlatform && (
            <span className="flag flag-compare">on {product.platformCount} platforms</span>
          )}
        </div>

        {product.inStock === false && (
          <div className="pcard-oos"><span>Out of stock</span></div>
        )}
      </Link>

      <div className="pcard-body">
        <div className="pcard-heading">
          {product.brand && <span className="pcard-brand">{product.brand}</span>}
          <Link to={`/product/${product.id}`} className="pcard-title clamp-2">
            {product.title}
          </Link>
        </div>

        {rating && (
          <div className="pcard-rating">
            <span className="stars" aria-hidden="true">★</span>
            <strong>{rating}</strong>
            {product.ratingCount > 0 && (
              <span className="subtle">{formatCount(product.ratingCount)} ratings</span>
            )}
          </div>
        )}

        <div className="pcard-price-row">
          <div className="pcard-price-block">
            <span className="pcard-price">{formatPrice(product.bestPrice)}</span>
            {multiPlatform ? (
              <span className="xs subtle">best of {product.platformCount} platforms</span>
            ) : (
              product.bestPlatformName && (
                <span className="xs subtle pcard-only">
                  <span
                    className="platform-dot"
                    style={{ background: platformAccent(product.bestPlatformCode) }}
                    aria-hidden="true"
                  />
                  {product.bestPlatformName} only
                </span>
              )
            )}
          </div>

          {product.valueScore !== null && product.valueScore !== undefined && (
            <ValueScore score={product.valueScore} size={46} />
          )}
        </div>

        {/* The comparison itself, not merely a count of platforms. */}
        <PriceComparison
          platformPrices={product.platformPrices}
          saving={product.potentialSaving}
          compact
        />

        <div className="pcard-actions">
          <button
            type="button"
            className={selected ? 'btn btn-primary btn-sm pcard-compare' : 'btn btn-sm pcard-compare'}
            onClick={() => toggleCompare(product.id)}
            aria-pressed={selected}
          >
            {selected ? 'Added' : 'Compare'}
          </button>
          {user && (
            <button
              type="button"
              className="btn btn-sm pcard-fav"
              onClick={() => toggleFavorite(product.id)}
              aria-pressed={favorited}
              aria-label={favorited ? 'Remove from saved' : 'Save this product'}
              title={favorited ? 'Remove from saved' : 'Save this product'}
            >
              <span aria-hidden="true" className={favorited ? 'heart heart-on' : 'heart'}>
                {favorited ? '♥' : '♡'}
              </span>
            </button>
          )}
        </div>
      </div>
    </article>
  )
}
