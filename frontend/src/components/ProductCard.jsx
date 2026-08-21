import { Link } from 'react-router-dom'
import { useApp } from '../hooks/AppContext.jsx'
import { formatCount, formatPercent, formatPrice, formatRating, platformAccent } from '../utils/format.js'
import ValueScore from './ValueScore.jsx'

export default function ProductCard({ product }) {
  const { inCompare, toggleCompare, user, favoriteIds, toggleFavorite } = useApp()
  const selected = inCompare(product.id)
  const favorited = favoriteIds.includes(product.id)
  const rating = formatRating(product.rating)

  const saving = Number(product.potentialSaving || 0)

  return (
    <article className={`product-card${selected ? ' product-card-selected' : ''}`}>
      <Link to={`/product/${product.id}`} className="product-card-media">
        {product.imageUrl ? (
          <img src={product.imageUrl} alt="" loading="lazy" />
        ) : (
          <div className="product-card-placeholder" aria-hidden="true">📦</div>
        )}
        {product.maxDiscountPct > 0 && (
          <span className="badge badge-positive product-card-discount">
            {formatPercent(product.maxDiscountPct)} off
          </span>
        )}
      </Link>

      <div className="product-card-body">
        <Link to={`/product/${product.id}`} className="product-card-title clamp-2">
          {product.title}
        </Link>

        <div className="row-wrap product-card-meta">
          {product.brand && <span className="xs subtle">{product.brand}</span>}
          {rating && (
            <span className="xs product-card-rating" title={`${rating} out of 5`}>
              ★ {rating}
              {product.ratingCount > 0 && (
                <span className="subtle"> ({formatCount(product.ratingCount)})</span>
              )}
            </span>
          )}
        </div>

        <div className="product-card-price-row">
          <div>
            <div className="product-card-price">{formatPrice(product.bestPrice)}</div>
            {/* Only shown when platforms actually disagree - a zero saving line
                would be noise on every card. */}
            {saving > 0 && (
              <div className="xs product-card-saving">
                Save {formatPrice(saving)} vs highest
              </div>
            )}
          </div>
          {product.valueScore !== null && product.valueScore !== undefined && (
            <ValueScore score={product.valueScore} size={44} />
          )}
        </div>

        <div className="row-wrap product-card-platforms">
          {product.bestPlatformName && (
            <span
              className="badge product-card-platform"
              style={{ borderColor: platformAccent(product.bestPlatformCode) }}
            >
              <span
                className="platform-dot"
                style={{ background: platformAccent(product.bestPlatformCode) }}
                aria-hidden="true"
              />
              {product.bestPlatformName}
            </span>
          )}
          {product.platformCount > 1 && (
            <span className="badge badge-accent">on {product.platformCount} platforms</span>
          )}
          {product.inStock === false && <span className="badge badge-negative">Out of stock</span>}
        </div>

        <div className="row product-card-actions">
          <button
            type="button"
            className={selected ? 'btn btn-primary btn-sm' : 'btn btn-sm'}
            onClick={() => toggleCompare(product.id)}
            aria-pressed={selected}
          >
            {selected ? 'In compare' : 'Compare'}
          </button>
          {user && (
            <button
              type="button"
              className="btn btn-ghost btn-sm product-card-fav"
              onClick={() => toggleFavorite(product.id)}
              aria-pressed={favorited}
              aria-label={favorited ? 'Remove from saved' : 'Save this product'}
              title={favorited ? 'Remove from saved' : 'Save this product'}
            >
              <span aria-hidden="true" style={{ color: favorited ? 'var(--negative)' : 'inherit' }}>
                {favorited ? '♥' : '♡'}
              </span>
            </button>
          )}
        </div>
      </div>
    </article>
  )
}
