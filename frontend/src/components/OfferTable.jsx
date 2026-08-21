import {
  formatCount,
  formatDelivery,
  formatPercent,
  formatPrice,
  formatRating,
  formatRelative,
  platformAccent,
} from '../utils/format.js'

/**
 * Every platform listing of one product, side by side.
 *
 * <p>This is the table the whole application exists to produce. The cheapest
 * in-stock row is marked rather than merely sorted first, because a reader
 * scanning quickly needs the answer flagged, not inferred from position.
 */
export default function OfferTable({ offers }) {
  if (!offers || offers.length === 0) {
    return <div className="empty-state small">No live listings for this product.</div>
  }

  return (
    <div className="scroll-x">
      <table className="offer-table">
        <thead>
          <tr>
            <th scope="col">Platform</th>
            <th scope="col" className="numeric">Price</th>
            <th scope="col" className="numeric">Discount</th>
            <th scope="col" className="numeric">Rating</th>
            <th scope="col">Delivery</th>
            <th scope="col">Availability</th>
            <th scope="col" />
          </tr>
        </thead>
        <tbody>
          {offers.map((offer) => (
            <tr key={offer.id} className={offer.bestPrice ? 'offer-row-best' : undefined}>
              <td>
                <span className="row" style={{ gap: 'var(--space-2)' }}>
                  <span
                    className="platform-dot"
                    style={{ background: platformAccent(offer.platformCode) }}
                    aria-hidden="true"
                  />
                  <span>
                    {offer.platformName}
                    {offer.seller && (
                      <span className="xs subtle" style={{ display: 'block' }}>
                        {offer.seller}
                      </span>
                    )}
                  </span>
                </span>
              </td>

              <td className="numeric">
                <div className="offer-price">
                  {formatPrice(offer.price)}
                  {offer.bestPrice && (
                    <span className="badge badge-positive offer-best-badge">Best price</span>
                  )}
                </div>
                {offer.originalPrice && Number(offer.originalPrice) > Number(offer.price) && (
                  <div className="xs subtle offer-strike">{formatPrice(offer.originalPrice)}</div>
                )}
              </td>

              <td className="numeric">
                {offer.discountPct ? formatPercent(offer.discountPct) : '--'}
              </td>

              <td className="numeric">
                {formatRating(offer.rating) ? (
                  <>
                    ★ {formatRating(offer.rating)}
                    <div className="xs subtle">{formatCount(offer.ratingCount)} ratings</div>
                  </>
                ) : (
                  '--'
                )}
              </td>

              <td>{formatDelivery(offer.deliveryDays)}</td>

              <td>
                {offer.inStock ? (
                  <span className="badge badge-positive">In stock</span>
                ) : (
                  <span className="badge badge-negative">Out of stock</span>
                )}
                {offer.fetchedAt && (
                  <div className="xs subtle">checked {formatRelative(offer.fetchedAt)}</div>
                )}
              </td>

              <td>
                {offer.url && (
                  <a
                    className="btn btn-sm"
                    href={offer.url}
                    target="_blank"
                    rel="noopener noreferrer"
                  >
                    Visit
                  </a>
                )}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}
