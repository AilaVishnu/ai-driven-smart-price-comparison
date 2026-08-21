/**
 * Why a product scored what it did, criterion by criterion.
 *
 * <p>Magnitude, so a single hue with length carrying the value - no rainbow, no
 * colour-by-rank. Bars are recessive by design: this panel exists to make the
 * score auditable, not to compete with the headline number.
 */
export default function ScoreBreakdown({ breakdown, compact = false }) {
  if (!breakdown) return null

  const rows = Object.values(breakdown)
    .filter((row) => row.weight > 0)
    .sort((a, b) => b.weight - a.weight)

  if (rows.length === 0) {
    return <p className="xs subtle">No criteria were given any weight.</p>
  }

  return (
    <div className="viz score-breakdown">
      {!compact && <div className="viz-title">How this score was reached</div>}
      <div className="stack" style={{ gap: 'var(--space-2)', marginTop: 'var(--space-2)' }}>
        {rows.map((row) => {
          const score = Math.max(0, Math.min(100, Number(row.criterionScore) || 0))
          return (
            <div className="breakdown-row" key={row.criterion}>
              <span className="breakdown-label xs">
                {row.label}
                {row.isBest && (
                  <span className="badge badge-positive breakdown-best">best</span>
                )}
              </span>
              <div className="breakdown-track" aria-hidden="true">
                <span
                  className="breakdown-bar"
                  style={{
                    width: `${score}%`,
                    background: 'var(--viz-series-1)',
                  }}
                />
              </div>
              <span className="breakdown-value numeric xs">{score.toFixed(0)}</span>
            </div>
          )
        })}
      </div>
      {!compact && (
        <p className="xs subtle" style={{ marginTop: 'var(--space-2)', marginBottom: 0 }}>
          Each bar is how close this product came to the best value seen on that criterion,
          before weighting. The headline score combines them by the weights above.
        </p>
      )}
    </div>
  )
}
