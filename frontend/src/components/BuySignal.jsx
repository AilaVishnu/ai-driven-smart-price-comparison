import { formatPercent, formatPrice, signalLabel, signalTone, trendLabel } from '../utils/format.js'

/**
 * The buy / wait recommendation from the price forecast.
 *
 * <p>Always shown with its confidence and its reasoning. A recommendation
 * drawn through scattered data is worth much less than one drawn through a clean
 * trend, and hiding that distinction would make the feature look more certain
 * than it is.
 */
export default function BuySignal({ forecast }) {
  if (!forecast) return null

  const tone = signalTone(forecast.signal)
  const badgeClass =
    tone === 'positive' ? 'badge badge-positive' : tone === 'warning' ? 'badge badge-warning' : 'badge'

  const confidence = Number(forecast.rSquared || 0)
  const confidenceLabel = confidence >= 0.7 ? 'high' : confidence >= 0.3 ? 'moderate' : 'low'
  const insufficient = forecast.signal === 'INSUFFICIENT_DATA'

  return (
    <div className="buy-signal stack">
      <div className="row-wrap">
        <span className={badgeClass}>{signalLabel(forecast.signal)}</span>
        {!insufficient && <span className="badge">{trendLabel(forecast.trend)}</span>}
        {forecast.containsSimulatedData && (
          <span className="badge" title="Part of this history was generated for demonstration">
            includes simulated history
          </span>
        )}
      </div>

      <p className="small muted" style={{ margin: 0 }}>{forecast.rationale}</p>

      {!insufficient && (
        <>
          <dl className="forecast-stats">
            <div>
              <dt>Current</dt>
              <dd>{formatPrice(forecast.currentPrice)}</dd>
            </div>
            <div>
              <dt>Period low</dt>
              <dd>{formatPrice(forecast.minPrice)}</dd>
            </div>
            <div>
              <dt>Period high</dt>
              <dd>{formatPrice(forecast.maxPrice)}</dd>
            </div>
            <div>
              <dt>Projected in 14 days</dt>
              <dd>{formatPrice(forecast.predicted14d)}</dd>
            </div>
          </dl>

          <p className="xs subtle" style={{ margin: 0 }}>
            Fitted by least squares over {forecast.observations} observations. Confidence{' '}
            {confidenceLabel} (R-squared {confidence.toFixed(2)}), volatility{' '}
            {formatPercent(Number(forecast.volatility || 0) * 100, 1)}.
          </p>
        </>
      )}
    </div>
  )
}
