import { useNavigate } from 'react-router-dom'
import { MAX_COMPARE, useApp } from '../hooks/AppContext.jsx'

/**
 * The sticky comparison tray.
 *
 * <p>Stays out of the way until something is selected, then follows the user
 * across pages. Comparison is a task you assemble while browsing, so the
 * selection has to survive navigation, which is why it lives in context and in
 * localStorage rather than in a single page.
 */
export default function CompareTray() {
  const { compareIds, clearCompare, toggleCompare } = useApp()
  const navigate = useNavigate()

  if (compareIds.length === 0) return null

  const ready = compareIds.length >= 2

  return (
    <div className="compare-tray" role="region" aria-label="Comparison tray">
      <div className="container compare-tray-inner">
        <div className="row-wrap compare-tray-items">
          <span className="small strong">
            Comparing {compareIds.length} of {MAX_COMPARE}
          </span>
          {compareIds.map((id) => (
            <button
              key={id}
              type="button"
              className="badge compare-chip"
              onClick={() => toggleCompare(id)}
              title="Remove from comparison"
            >
              #{id} <span aria-hidden="true">×</span>
            </button>
          ))}
        </div>

        <div className="row compare-tray-actions">
          <button type="button" className="btn btn-sm" onClick={clearCompare}>
            Clear
          </button>
          <button
            type="button"
            className="btn btn-primary btn-sm"
            disabled={!ready}
            onClick={() => navigate('/compare')}
            title={ready ? 'Compare selected products' : 'Select at least two products'}
          >
            Compare
          </button>
        </div>
      </div>
      {!ready && (
        <div className="container xs subtle compare-tray-hint">
          Add one more product to compare.
        </div>
      )}
    </div>
  )
}
