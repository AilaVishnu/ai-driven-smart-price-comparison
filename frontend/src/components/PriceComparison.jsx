import { formatPercent, formatPrice, platformAccent } from '../utils/format.js'

/**
 * What each platform charges for the same product.
 *
 * <p>The single most useful thing this application can show, so it appears on
 * the result card rather than being hidden behind a click. Rows are ordered
 * cheapest first and the winner is marked, because a shopper scanning a grid
 * should not have to compare two numbers themselves.
 *
 * <p>Renders nothing for a single-platform product. A one-row "comparison" is
 * not a comparison, and drawing the chrome anyway would imply a choice that
 * does not exist.
 */
export default function PriceComparison({ platformPrices, saving, compact = false }) {
  if (!platformPrices || platformPrices.length < 2) {
    return null
  }

  const rows = compact ? platformPrices.slice(0, 3) : platformPrices
  const hidden = platformPrices.length - rows.length

  const cheapest = platformPrices[0]
  const dearest = platformPrices[platformPrices.length - 1]
  const savingValue = Number(saving || 0)
  const savingPct =
    dearest && Number(dearest.price) > 0
      ? (savingValue / Number(dearest.price)) * 100
      : 0

  return (
    <div className={compact ? 'price-compare price-compare-compact' : 'price-compare'}>
      <ul className="price-compare-rows">
        {rows.map((row) => (
          <li
            key={row.platformCode}
            className={row.cheapest ? 'price-compare-row price-compare-win' : 'price-compare-row'}
          >
            <span
              className="platform-dot"
              style={{ background: platformAccent(row.platformCode) }}
              aria-hidden="true"
            />
            <span className="price-compare-name truncate">{row.platformName}</span>
            <span className="price-compare-value numeric">
              {formatPrice(row.price)}
              {!row.inStock && <span className="xs subtle"> (out of stock)</span>}
            </span>
          </li>
        ))}
      </ul>

      {hidden > 0 && (
        <p className="xs subtle price-compare-more">
          and {hidden} more platform{hidden === 1 ? '' : 's'}
        </p>
      )}

      {savingValue > 0 && (
        <p className="price-compare-saving">
          Save <strong>{formatPrice(savingValue)}</strong>
          {savingPct >= 1 && <span className="subtle"> ({formatPercent(savingPct)})</span>}
          {' on '}
          {cheapest.platformName}
        </p>
      )}
    </div>
  )
}
