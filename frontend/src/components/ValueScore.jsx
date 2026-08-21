/**
 * The TOPSIS value score, as a hero number inside a thin arc.
 *
 * <p>Magnitude is carried by arc length, not by hue: colouring the ring by score
 * would imply a categorical meaning the number does not have, and would put a
 * red/green judgement on what is a relative ranking within one result set. The
 * arc is a single accent throughout; the number does the talking.
 */
export default function ValueScore({ score, size = 56, showLabel = false }) {
  const value = Math.max(0, Math.min(100, Number(score) || 0))
  const stroke = size < 50 ? 4 : 5
  const radius = (size - stroke) / 2
  const circumference = 2 * Math.PI * radius
  const filled = (value / 100) * circumference

  return (
    <div className="value-score viz" title={`Value score ${value.toFixed(0)} out of 100`}>
      <svg
        width={size}
        height={size}
        viewBox={`0 0 ${size} ${size}`}
        role="img"
        aria-label={`Value score ${value.toFixed(0)} out of 100`}
      >
        <circle
          cx={size / 2}
          cy={size / 2}
          r={radius}
          fill="none"
          stroke="var(--viz-grid)"
          strokeWidth={stroke}
        />
        <circle
          cx={size / 2}
          cy={size / 2}
          r={radius}
          fill="none"
          stroke="var(--viz-series-1)"
          strokeWidth={stroke}
          strokeLinecap="round"
          strokeDasharray={`${filled} ${circumference - filled}`}
          /* Start the arc at twelve o clock rather than three. */
          transform={`rotate(-90 ${size / 2} ${size / 2})`}
        />
        <text
          x="50%"
          y="50%"
          textAnchor="middle"
          dominantBaseline="central"
          style={{
            fontSize: size < 50 ? 14 : 17,
            fontWeight: 650,
            fill: 'var(--viz-ink)',
          }}
        >
          {value.toFixed(0)}
        </text>
      </svg>
      {showLabel && <span className="xs subtle">Value score</span>}
    </div>
  )
}
