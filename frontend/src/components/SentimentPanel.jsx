import { formatCount } from '../utils/format.js'

const ASPECT_LABELS = {
  battery: 'Battery',
  camera: 'Camera',
  display: 'Display',
  performance: 'Performance',
  sound: 'Sound',
  build: 'Build quality',
  price: 'Value for money',
  delivery: 'Delivery',
  service: 'Service',
  storage: 'Storage',
}

/**
 * What the reviews say, split by aspect.
 *
 * <p>Polarity is a diverging scale, so it gets a diverging encoding: one hue
 * either side of a neutral centre line. Blue and red rather than green and red,
 * because green/red is exactly the pairing red-green colour blindness collapses,
 * and this is the panel a reader is most likely to skim on colour alone. Every
 * bar also carries its signed score, so the hue is a second channel rather than
 * the only one.
 */
export default function SentimentPanel({ sentiment }) {
  if (!sentiment || !sentiment.reviewCount) {
    return (
      <div className="empty-state small">
        No customer reviews have been collected for this product yet.
      </div>
    )
  }

  const { positiveCount, neutralCount, negativeCount, reviewCount, averageScore, overallLabel } =
    sentiment

  const aspects = Object.entries(sentiment.aspects || {})
    .map(([key, value]) => ({ key, ...value }))
    .sort((a, b) => b.mentions - a.mentions)

  const total = Math.max(1, positiveCount + neutralCount + negativeCount)
  const pct = (n) => (n / total) * 100

  return (
    <div className="viz sentiment-panel stack">
      <div className="row-wrap sentiment-headline">
        <span
          className={
            overallLabel === 'POSITIVE'
              ? 'badge badge-positive'
              : overallLabel === 'NEGATIVE'
                ? 'badge badge-negative'
                : 'badge'
          }
        >
          {overallLabel === 'POSITIVE'
            ? 'Positively reviewed'
            : overallLabel === 'NEGATIVE'
              ? 'Negatively reviewed'
              : 'Mixed reviews'}
        </span>
        <span className="xs subtle">
          from {formatCount(reviewCount)} review{reviewCount === 1 ? '' : 's'} · mean score{' '}
          {Number(averageScore).toFixed(2)}
        </span>
      </div>

      {/* Overall split. A 2px surface gap keeps adjacent segments legible. */}
      <div className="sentiment-stack" role="img"
           aria-label={`${positiveCount} positive, ${neutralCount} neutral, ${negativeCount} negative reviews`}>
        {positiveCount > 0 && (
          <span
            className="sentiment-stack-seg"
            style={{ width: `${pct(positiveCount)}%`, background: 'var(--viz-pos)' }}
          />
        )}
        {neutralCount > 0 && (
          <span
            className="sentiment-stack-seg"
            style={{ width: `${pct(neutralCount)}%`, background: 'var(--viz-mid)' }}
          />
        )}
        {negativeCount > 0 && (
          <span
            className="sentiment-stack-seg"
            style={{ width: `${pct(negativeCount)}%`, background: 'var(--viz-neg)' }}
          />
        )}
      </div>

      <div className="viz-legend">
        <span className="viz-legend-item">
          <span className="viz-swatch" style={{ background: 'var(--viz-pos)' }} />
          Positive {positiveCount}
        </span>
        <span className="viz-legend-item">
          <span className="viz-swatch" style={{ background: 'var(--viz-mid)' }} />
          Neutral {neutralCount}
        </span>
        <span className="viz-legend-item">
          <span className="viz-swatch" style={{ background: 'var(--viz-neg)' }} />
          Negative {negativeCount}
        </span>
      </div>

      {aspects.length > 0 && (
        <div className="stack" style={{ gap: 'var(--space-2)' }}>
          <div className="viz-title">What reviewers focused on</div>
          <div className="aspect-list">
            {aspects.map((aspect) => {
              const score = Number(aspect.score) || 0
              const magnitude = Math.min(100, Math.abs(score) * 100)
              const positive = score >= 0
              return (
                <div className="aspect-row" key={aspect.key}>
                  <span className="aspect-label">
                    {ASPECT_LABELS[aspect.key] || aspect.key}
                    <span className="subtle xs"> ({aspect.mentions})</span>
                  </span>

                  <div className="aspect-track" aria-hidden="true">
                    <span className="aspect-center" />
                    <span
                      className="aspect-bar"
                      style={{
                        width: `${magnitude / 2}%`,
                        background: positive ? 'var(--viz-pos)' : 'var(--viz-neg)',
                        left: positive ? '50%' : undefined,
                        right: positive ? undefined : '50%',
                        borderRadius: positive ? '0 4px 4px 0' : '4px 0 0 4px',
                      }}
                    />
                  </div>

                  {/* The signed number is the second channel: colour never carries
                      the verdict alone. */}
                  <span className="aspect-value numeric xs">
                    {score > 0 ? '+' : ''}
                    {score.toFixed(2)}
                  </span>
                </div>
              )
            })}
          </div>
        </div>
      )}
    </div>
  )
}
