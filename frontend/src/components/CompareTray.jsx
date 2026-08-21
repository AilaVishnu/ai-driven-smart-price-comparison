import { useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { MAX_COMPARE, useApp } from '../hooks/AppContext.jsx'
import { api } from '../api/client.js'
import { formatPrice } from '../utils/format.js'

/**
 * The sticky comparison tray.
 *
 * <p>Stays out of the way until something is selected, then follows the user
 * across pages: comparison is a task you assemble while browsing, so the
 * selection has to survive navigation.
 *
 * <p>Shows the actual products rather than their ids. A row of "#320 #418"
 * tells you how many things you picked but not what they were, which is no use
 * when deciding whether to add a fourth.
 */
export default function CompareTray() {
  const { compareIds, clearCompare, toggleCompare } = useApp()
  const navigate = useNavigate()
  const [items, setItems] = useState([])

  useEffect(() => {
    if (compareIds.length === 0) {
      setItems([])
      return undefined
    }
    let active = true
    Promise.all(
      compareIds.map((id) =>
        api.product(id).then((d) => d.summary).catch(() => ({ id, title: `#${id}` }))
      )
    ).then((loaded) => {
      if (active) setItems(loaded)
    })
    return () => {
      active = false
    }
  }, [compareIds])

  if (compareIds.length === 0) return null

  const ready = compareIds.length >= 2
  const remaining = MAX_COMPARE - compareIds.length

  return (
    <div className="compare-tray" role="region" aria-label="Comparison tray">
      <div className="container compare-tray-inner">
        <div className="tray-items">
          {items.map((item) => (
            <div className="tray-item" key={item.id}>
              {item.imageUrl ? (
                <img src={item.imageUrl} alt="" className="tray-thumb" />
              ) : (
                <span className="tray-thumb tray-thumb-empty" aria-hidden="true">📦</span>
              )}
              <span className="tray-text">
                <span className="tray-title truncate">{item.title}</span>
                {item.bestPrice && (
                  <span className="xs subtle">{formatPrice(item.bestPrice)}</span>
                )}
              </span>
              <button
                type="button"
                className="tray-remove"
                onClick={() => toggleCompare(item.id)}
                aria-label={`Remove ${item.title} from comparison`}
                title="Remove"
              >
                ×
              </button>
            </div>
          ))}

          {remaining > 0 && (
            <div className="tray-slot">
              <span className="xs subtle">
                {ready
                  ? `Add up to ${remaining} more`
                  : `Add ${2 - compareIds.length} more to compare`}
              </span>
            </div>
          )}
        </div>

        <div className="row compare-tray-actions">
          <button type="button" className="btn btn-sm" onClick={clearCompare}>
            Clear
          </button>
          <button
            type="button"
            className="btn btn-primary"
            disabled={!ready}
            onClick={() => navigate('/compare')}
            title={ready ? 'Compare selected products' : 'Select at least two products'}
          >
            Compare {compareIds.length}
          </button>
        </div>
      </div>
    </div>
  )
}
