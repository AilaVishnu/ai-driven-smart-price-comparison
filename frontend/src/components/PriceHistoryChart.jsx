import { useMemo, useRef, useState } from 'react'
import { formatPrice } from '../utils/format.js'

/**
 * Price over time.
 *
 * <p>One series, so there is no legend: the title names what is plotted. The
 * series is the cheapest price available on any platform each day, which is both
 * the figure a buyer cares about and the only one that behaves - pooling
 * platforms at different price levels would show violent swings when nothing had
 * moved.
 *
 * <p>A crosshair and tooltip ship by default; a line chart the reader cannot
 * interrogate is a picture of data rather than a view of it.
 */
export default function PriceHistoryChart({ points, height = 220, containsSimulated = false }) {
  const [hover, setHover] = useState(null)
  const wrapperRef = useRef(null)

  const width = 720
  const padding = { top: 16, right: 16, bottom: 28, left: 62 }

  const series = useMemo(() => {
    if (!points || points.length === 0) return []
    // One point per day, taking the lowest price seen that day.
    const byDay = new Map()
    points.forEach((p) => {
      const time = new Date(p.at).getTime()
      if (Number.isNaN(time)) return
      const day = Math.floor(time / 86400000)
      const price = Number(p.price)
      if (Number.isNaN(price)) return
      const existing = byDay.get(day)
      if (!existing || price < existing.price) {
        byDay.set(day, { day, time, price, source: p.source })
      }
    })
    return [...byDay.values()].sort((a, b) => a.time - b.time)
  }, [points])

  if (series.length < 2) {
    return (
      <div className="empty-state small">
        Not enough price history yet to draw a chart.
      </div>
    )
  }

  const prices = series.map((p) => p.price)
  const rawMin = Math.min(...prices)
  const rawMax = Math.max(...prices)
  // A little headroom, and never a zero-height band when the price never moved.
  const span = rawMax - rawMin || Math.max(1, rawMax * 0.02)
  const yMin = rawMin - span * 0.12
  const yMax = rawMax + span * 0.12

  const plotWidth = width - padding.left - padding.right
  const plotHeight = height - padding.top - padding.bottom

  const xOf = (i) => padding.left + (i / (series.length - 1)) * plotWidth
  const yOf = (price) => padding.top + (1 - (price - yMin) / (yMax - yMin)) * plotHeight

  const linePath = series.map((p, i) => `${i === 0 ? 'M' : 'L'}${xOf(i)},${yOf(p.price)}`).join(' ')
  const areaPath =
    `${linePath} L${xOf(series.length - 1)},${padding.top + plotHeight} ` +
    `L${xOf(0)},${padding.top + plotHeight} Z`

  const yTicks = 4
  const ticks = Array.from({ length: yTicks + 1 }, (_, i) => yMin + ((yMax - yMin) * i) / yTicks)

  function onMove(event) {
    const svg = event.currentTarget
    const rect = svg.getBoundingClientRect()
    // The SVG scales to its container, so map the pointer back into viewBox units.
    const x = ((event.clientX - rect.left) / rect.width) * width
    const ratio = (x - padding.left) / plotWidth
    const index = Math.round(ratio * (series.length - 1))
    if (index < 0 || index >= series.length) {
      setHover(null)
      return
    }
    setHover({ index, point: series[index] })
  }

  const firstDate = new Date(series[0].time)
  const lastDate = new Date(series[series.length - 1].time)
  const dateFmt = (d) => d.toLocaleDateString('en-IN', { day: 'numeric', month: 'short' })

  return (
    <figure className="viz viz-figure">
      <figcaption className="viz-title">
        Lowest available price, last {series.length} days
      </figcaption>

      <div className="viz-plot" ref={wrapperRef}>
        <svg
          className="viz-svg"
          viewBox={`0 0 ${width} ${height}`}
          preserveAspectRatio="none"
          role="img"
          aria-label={`Price history: from ${formatPrice(series[0].price)} to ${formatPrice(series[series.length - 1].price)}`}
          onMouseMove={onMove}
          onMouseLeave={() => setHover(null)}
        >
          {ticks.map((tick, i) => (
            <g key={i}>
              <line
                className="viz-grid-line"
                x1={padding.left}
                x2={width - padding.right}
                y1={yOf(tick)}
                y2={yOf(tick)}
              />
              <text className="viz-tick" x={padding.left - 8} y={yOf(tick)} textAnchor="end" dy="0.32em">
                {formatPrice(tick)}
              </text>
            </g>
          ))}

          <line
            className="viz-axis-line"
            x1={padding.left}
            x2={width - padding.right}
            y1={padding.top + plotHeight}
            y2={padding.top + plotHeight}
          />

          <path className="viz-area" d={areaPath} />
          <path className="viz-line" d={linePath} />

          <text className="viz-tick" x={padding.left} y={height - 8}>{dateFmt(firstDate)}</text>
          <text className="viz-tick" x={width - padding.right} y={height - 8} textAnchor="end">
            {dateFmt(lastDate)}
          </text>

          {hover && (
            <>
              <line
                className="viz-crosshair"
                x1={xOf(hover.index)}
                x2={xOf(hover.index)}
                y1={padding.top}
                y2={padding.top + plotHeight}
              />
              <circle className="viz-marker" cx={xOf(hover.index)} cy={yOf(hover.point.price)} r="5" />
            </>
          )}
        </svg>

        {hover && (
          <div
            className="viz-tooltip"
            style={{
              left: `${(xOf(hover.index) / width) * 100}%`,
              top: `${(yOf(hover.point.price) / height) * 100}%`,
            }}
          >
            <strong>{formatPrice(hover.point.price, { precise: true })}</strong>
            <br />
            <span className="subtle">
              {new Date(hover.point.time).toLocaleDateString('en-IN', {
                day: 'numeric',
                month: 'short',
                year: 'numeric',
              })}
            </span>
            {hover.point.source === 'SIMULATED' && (
              <>
                <br />
                <span className="subtle">simulated point</span>
              </>
            )}
          </div>
        )}
      </div>

      {containsSimulated && (
        <figcaption className="viz-caption">
          This series includes simulated points, generated so the forecast has a baseline before
          real history accumulates. Observed prices are recorded separately and never fabricated.
        </figcaption>
      )}
    </figure>
  )
}
