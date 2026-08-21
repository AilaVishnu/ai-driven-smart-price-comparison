import { CRITERION_LABELS } from '../utils/format.js'

export const DEFAULT_WEIGHTS = {
  price: 30,
  rating: 20,
  ratingCount: 10,
  discount: 15,
  sentiment: 10,
  delivery: 10,
  availability: 5,
}

const HINTS = {
  price: 'lower is better',
  delivery: 'faster is better',
}

/**
 * Weight controls for the TOPSIS ranking.
 *
 * <p>The centrepiece of the comparison page. A decision support tool that hides
 * its assumptions is just an opinion with a number attached, so the assumptions
 * are handed to the user: move a slider and the ranking recomputes against what
 * they actually care about.
 *
 * <p>Values are passed to the server raw and normalised there, so the sliders
 * are free to be plain 0-50 controls rather than a set that must sum to
 * anything.
 */
export default function WeightSliders({ weights, onChange, onReset }) {
  const total = Object.values(weights).reduce((sum, w) => sum + Number(w || 0), 0) || 1

  return (
    <div className="weight-sliders card card-padded stack">
      <div className="row">
        <div>
          <h3 style={{ fontSize: 'var(--text-base)' }}>What matters to you</h3>
          <p className="xs subtle" style={{ margin: 0 }}>
            Adjust the weights and the ranking updates.
          </p>
        </div>
        <span className="spacer" />
        <button type="button" className="btn btn-sm" onClick={onReset}>
          Reset
        </button>
      </div>

      <div className="stack" style={{ gap: 'var(--space-3)' }}>
        {Object.keys(DEFAULT_WEIGHTS).map((key) => {
          const value = Number(weights[key] || 0)
          const share = Math.round((value / total) * 100)
          return (
            <div className="weight-row" key={key}>
              <label className="weight-label" htmlFor={`weight-${key}`}>
                {CRITERION_LABELS[key] || key}
                {HINTS[key] && <span className="xs subtle"> ({HINTS[key]})</span>}
              </label>
              <input
                id={`weight-${key}`}
                className="weight-range"
                type="range"
                min="0"
                max="50"
                step="1"
                value={value}
                onChange={(e) => onChange({ ...weights, [key]: Number(e.target.value) })}
              />
              <span className="weight-value numeric xs">{share}%</span>
            </div>
          )
        })}
      </div>
    </div>
  )
}
