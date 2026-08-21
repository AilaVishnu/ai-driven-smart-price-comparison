/*
 * Display formatting.
 *
 * Prices use the en-IN locale throughout, which groups by lakh and crore
 * (1,29,900 rather than 129,900). Getting this wrong is immediately obvious to
 * the users this is built for.
 */

const inr = new Intl.NumberFormat('en-IN', {
  style: 'currency',
  currency: 'INR',
  maximumFractionDigits: 0,
})

const inrPrecise = new Intl.NumberFormat('en-IN', {
  style: 'currency',
  currency: 'INR',
  minimumFractionDigits: 2,
  maximumFractionDigits: 2,
})

const compactNumber = new Intl.NumberFormat('en-IN', {
  notation: 'compact',
  maximumFractionDigits: 1,
})

export function formatPrice(value, { precise = false } = {}) {
  if (value === null || value === undefined || value === '') return '--'
  const n = Number(value)
  if (Number.isNaN(n)) return '--'
  return precise ? inrPrecise.format(n) : inr.format(n)
}

export function formatCount(value) {
  if (value === null || value === undefined) return '0'
  const n = Number(value)
  if (Number.isNaN(n)) return '0'
  // Compact only once the exact figure stops mattering.
  return n < 10000 ? new Intl.NumberFormat('en-IN').format(n) : compactNumber.format(n)
}

export function formatPercent(value, digits = 0) {
  if (value === null || value === undefined) return '--'
  const n = Number(value)
  if (Number.isNaN(n)) return '--'
  return `${n.toFixed(digits)}%`
}

export function formatRating(value) {
  if (value === null || value === undefined) return null
  const n = Number(value)
  if (Number.isNaN(n)) return null
  return n.toFixed(1)
}

export function formatDate(value) {
  if (!value) return ''
  const d = new Date(value)
  if (Number.isNaN(d.getTime())) return ''
  return d.toLocaleDateString('en-IN', { day: 'numeric', month: 'short', year: 'numeric' })
}

export function formatRelative(value) {
  if (!value) return ''
  const d = new Date(value)
  if (Number.isNaN(d.getTime())) return ''
  const seconds = Math.floor((Date.now() - d.getTime()) / 1000)
  if (seconds < 60) return 'just now'
  const minutes = Math.floor(seconds / 60)
  if (minutes < 60) return `${minutes}m ago`
  const hours = Math.floor(minutes / 60)
  if (hours < 24) return `${hours}h ago`
  const days = Math.floor(hours / 24)
  if (days < 30) return `${days}d ago`
  return formatDate(value)
}

export function formatDelivery(days) {
  if (days === null || days === undefined) return 'Not stated'
  if (days === 0) return 'Today'
  if (days === 1) return 'Tomorrow'
  return `${days} days`
}

/** Small colour cue for the platform badges. */
export function platformAccent(code) {
  switch (code) {
    case 'AMAZON_IN':
      return 'var(--platform-amazon)'
    case 'FLIPKART':
      return 'var(--platform-flipkart)'
    default:
      return 'var(--platform-fallback)'
  }
}

export function sentimentTone(label) {
  switch (label) {
    case 'POSITIVE':
      return 'positive'
    case 'NEGATIVE':
      return 'negative'
    default:
      return 'neutral'
  }
}

export function signalTone(signal) {
  switch (signal) {
    case 'BUY_NOW':
      return 'positive'
    case 'WAIT':
      return 'warning'
    default:
      return 'neutral'
  }
}

export function signalLabel(signal) {
  switch (signal) {
    case 'BUY_NOW':
      return 'Good time to buy'
    case 'WAIT':
      return 'Worth waiting'
    case 'HOLD':
      return 'No clear signal'
    default:
      return 'Not enough history'
  }
}

export function trendLabel(trend) {
  switch (trend) {
    case 'FALLING':
      return 'Trending down'
    case 'RISING':
      return 'Trending up'
    case 'STABLE':
      return 'Broadly stable'
    default:
      return 'Unknown'
  }
}

/** Human label for a TOPSIS criterion key. */
export const CRITERION_LABELS = {
  price: 'Price',
  rating: 'Rating',
  ratingCount: 'Number of ratings',
  discount: 'Discount',
  sentiment: 'Review sentiment',
  delivery: 'Delivery speed',
  availability: 'In stock',
}
