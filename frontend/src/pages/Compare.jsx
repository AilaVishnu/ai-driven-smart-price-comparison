import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import WeightSliders, { DEFAULT_WEIGHTS } from '../components/WeightSliders.jsx'
import ScoreBreakdown from '../components/ScoreBreakdown.jsx'
import ValueScore from '../components/ValueScore.jsx'
import { EmptyState, ErrorState, Loading } from '../components/Common.jsx'
import { api } from '../api/client.js'
import { useApp } from '../hooks/AppContext.jsx'
import {
  formatCount,
  formatDelivery,
  formatPercent,
  formatPrice,
  formatRating,
} from '../utils/format.js'

/** Rows of the comparison matrix, with how to read each and whether high is good. */
const ROWS = [
  { key: 'price', label: 'Best price', better: 'low', get: (p) => Number(p.summary.bestPrice),
    render: (p) => formatPrice(p.summary.bestPrice) },
  { key: 'rating', label: 'Rating', better: 'high', get: (p) => Number(p.summary.rating || 0),
    render: (p) => (formatRating(p.summary.rating) ? `★ ${formatRating(p.summary.rating)}` : '--') },
  { key: 'ratingCount', label: 'Number of ratings', better: 'high',
    get: (p) => Number(p.summary.ratingCount || 0), render: (p) => formatCount(p.summary.ratingCount) },
  { key: 'discount', label: 'Best discount', better: 'high',
    get: (p) => Number(p.summary.maxDiscountPct || 0),
    render: (p) => (p.summary.maxDiscountPct ? formatPercent(p.summary.maxDiscountPct) : '--') },
  { key: 'sentiment', label: 'Review sentiment', better: 'high',
    get: (p) => Number((p.sentiment && p.sentiment.averageScore) || 0),
    render: (p) => (p.sentiment && p.sentiment.reviewCount
      ? `${Number(p.sentiment.averageScore).toFixed(2)} (${p.sentiment.reviewCount})`
      : 'no reviews') },
  { key: 'delivery', label: 'Fastest delivery', better: 'low',
    get: (p) => {
      const days = p.offers.map((o) => o.deliveryDays).filter((d) => d !== null && d !== undefined)
      return days.length ? Math.min(...days) : Number.POSITIVE_INFINITY
    },
    render: (p) => {
      const days = p.offers.map((o) => o.deliveryDays).filter((d) => d !== null && d !== undefined)
      return days.length ? formatDelivery(Math.min(...days)) : 'Not stated'
    } },
  { key: 'platforms', label: 'Available on', better: 'high',
    get: (p) => Number(p.summary.platformCount || 0),
    render: (p) => `${p.summary.platformCount} platform${p.summary.platformCount === 1 ? '' : 's'}` },
]

export default function Compare() {
  const { compareIds, toggleCompare, clearCompare } = useApp()
  const [weights, setWeights] = useState(DEFAULT_WEIGHTS)
  const [result, setResult] = useState(null)
  const [error, setError] = useState(null)
  const [loading, setLoading] = useState(false)

  useEffect(() => {
    if (compareIds.length < 2) {
      setResult(null)
      return undefined
    }
    let active = true
    setLoading(true)
    setError(null)
    api
      .compare(compareIds, weights)
      .then((r) => { if (active) { setResult(r); setError(null) } })
      .catch((e) => { if (active) { setError(e); setResult(null) } })
      .finally(() => { if (active) setLoading(false) })
    return () => { active = false }
  }, [compareIds, weights])

  if (compareIds.length < 2) {
    return (
      <div className="page container">
        <EmptyState
          title="Pick at least two products to compare"
          action={<Link className="btn btn-primary" to="/search" style={{ marginTop: 'var(--space-3)' }}>
            Browse products
          </Link>}
        >
          Add products from search results or a product page, then come back here.
        </EmptyState>
      </div>
    )
  }

  const products = result ? result.products : []
  const rankById = new Map((result ? result.ranking : []).map((r) => [r.productId, r]))

  /** Which column wins a given row, so the matrix can mark it. */
  function bestIndexFor(row) {
    if (products.length === 0) return -1
    let bestIndex = -1
    let bestValue = row.better === 'low' ? Number.POSITIVE_INFINITY : Number.NEGATIVE_INFINITY
    products.forEach((p, i) => {
      const value = row.get(p)
      if (!Number.isFinite(value)) return
      if (row.better === 'low' ? value < bestValue : value > bestValue) {
        bestValue = value
        bestIndex = i
      }
    })
    return bestIndex
  }

  return (
    <div className="page">
      <div className="container stack" style={{ gap: 'var(--space-5)' }}>
        <div className="row-wrap">
          <h1>Compare</h1>
          <span className="spacer" />
          <button type="button" className="btn btn-sm" onClick={clearCompare}>Clear all</button>
        </div>

        {error && <ErrorState error={error} />}
        {loading && !result && <Loading rows={5} label="Ranking products" />}

        {result && (
          <>
            {result.winnerProductId && (
              <div className="winner-banner card card-padded">
                <div className="row-wrap">
                  <span className="badge badge-positive">Best overall</span>
                  <strong>
                    {(products.find((p) => p.summary.id === result.winnerProductId) || {}).summary
                      ?.title || `Product #${result.winnerProductId}`}
                  </strong>
                </div>
                <p className="small muted" style={{ margin: 'var(--space-2) 0 0' }}>
                  {result.winnerReason}
                </p>
                {result.note && <p className="xs subtle" style={{ margin: 0 }}>{result.note}</p>}
              </div>
            )}

            <div className="compare-layout">
              <WeightSliders
                weights={weights}
                onChange={setWeights}
                onReset={() => setWeights(DEFAULT_WEIGHTS)}
              />

              <div className="scroll-x">
                <table className="compare-matrix">
                  <thead>
                    <tr>
                      <th scope="col" className="compare-rowhead">Criterion</th>
                      {products.map((p) => {
                        const scored = rankById.get(p.summary.id)
                        return (
                          <th scope="col" key={p.summary.id}>
                            <div className="compare-col-head">
                              {p.summary.imageUrl && (
                                <img src={p.summary.imageUrl} alt="" className="compare-thumb" />
                              )}
                              <Link to={`/product/${p.summary.id}`} className="compare-col-title clamp-2">
                                {p.summary.title}
                              </Link>
                              {scored && (
                                <div className="row" style={{ justifyContent: 'center', gap: 'var(--space-2)' }}>
                                  <ValueScore score={scored.score} size={44} />
                                  <span className="xs subtle">rank {scored.rank}</span>
                                </div>
                              )}
                              <button
                                type="button"
                                className="btn btn-ghost btn-sm"
                                onClick={() => toggleCompare(p.summary.id)}
                              >
                                Remove
                              </button>
                            </div>
                          </th>
                        )
                      })}
                    </tr>
                  </thead>
                  <tbody>
                    {ROWS.map((row) => {
                      const best = bestIndexFor(row)
                      return (
                        <tr key={row.key}>
                          <th scope="row" className="compare-rowhead">{row.label}</th>
                          {products.map((p, i) => (
                            <td key={p.summary.id} className={i === best ? 'compare-best' : undefined}>
                              {row.render(p)}
                              {i === best && (
                                <span className="badge badge-positive compare-best-badge">best</span>
                              )}
                            </td>
                          ))}
                        </tr>
                      )
                    })}
                  </tbody>
                </table>
              </div>
            </div>

            <div className="compare-breakdowns">
              {products.map((p) => {
                const scored = rankById.get(p.summary.id)
                if (!scored) return null
                return (
                  <div className="card card-padded" key={p.summary.id}>
                    <div className="row-wrap" style={{ marginBottom: 'var(--space-2)' }}>
                      <ValueScore score={scored.score} size={40} />
                      <strong className="small clamp-2">{p.summary.title}</strong>
                    </div>
                    <ScoreBreakdown breakdown={scored.breakdown} compact />
                  </div>
                )
              })}
            </div>

            <p className="xs subtle">
              Ranked with TOPSIS over the weights above. Each criterion is normalised before
              weighting, so a figure on a large scale such as review count cannot swamp the rest.
            </p>
          </>
        )}
      </div>
    </div>
  )
}
