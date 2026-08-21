import { useMemo, useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import ProductCard from '../components/ProductCard.jsx'
import FilterPanel from '../components/FilterPanel.jsx'
import PlatformStatus from '../components/PlatformStatus.jsx'
import { CardsLoading, EmptyState, ErrorState } from '../components/Common.jsx'
import { api } from '../api/client.js'
import { useApi } from '../hooks/useApi.js'
import { formatPrice } from '../utils/format.js'

const SORTS = [
  { value: 'relevance', label: 'Best value' },
  { value: 'price_asc', label: 'Price: low to high' },
  { value: 'price_desc', label: 'Price: high to low' },
  { value: 'rating_desc', label: 'Highest rated' },
  { value: 'discount_desc', label: 'Biggest discount' },
  { value: 'savings_desc', label: 'Biggest saving' },
]

const EMPTY_FILTERS = {
  minPrice: '', maxPrice: '', brand: '', category: '',
  minRating: '', platform: '', inStock: '', discounted: '',
}

/** Human label for an active filter, so it can be shown as a removable chip. */
function filterLabel(key, value) {
  switch (key) {
    case 'minPrice': return `From ${formatPrice(value)}`
    case 'maxPrice': return `Up to ${formatPrice(value)}`
    case 'brand': return `Brand: ${value}`
    case 'category': return `Category: ${value}`
    case 'minRating': return `${value}★ and above`
    case 'platform': return `On ${value.replace('_', '.').toLowerCase()}`
    case 'inStock': return 'In stock only'
    case 'discounted': return 'Discounted only'
    default: return `${key}: ${value}`
  }
}

export default function SearchResults() {
  const [params, setParams] = useSearchParams()
  const query = params.get('q') || ''
  const sort = params.get('sort') || 'relevance'
  const page = Number(params.get('page') || 0)

  const [filters, setFilters] = useState(() => ({
    ...EMPTY_FILTERS,
    ...Object.fromEntries(
      Object.keys(EMPTY_FILTERS).map((k) => [k, params.get(k) || ''])
    ),
  }))

  const { data, error, loading, reload } = useApi(
    (signal) => api.search({ q: query, page, size: 24, sort, ...filters }, signal),
    [query, page, sort, JSON.stringify(filters)]
  )

  const activeFilters = useMemo(
    () => Object.entries(filters).filter(([, v]) => v !== '' && v != null),
    [filters]
  )

  function updateParam(key, value) {
    const next = new URLSearchParams(params)
    if (value) next.set(key, value)
    else next.delete(key)
    // Any change to the query shape invalidates the current page number.
    if (key !== 'page') next.delete('page')
    setParams(next)
  }

  function changeFilters(next) {
    setFilters(next)
    const p = new URLSearchParams(params)
    p.delete('page')
    setParams(p)
  }

  const products = data ? data.products : []
  const totalPages = data ? data.totalPages : 0

  return (
    <div className="page">
      <div className="container stack" style={{ gap: 'var(--space-5)' }}>
        <header className="results-head">
          <div>
            <h1 className="results-title">
              {query ? <>Results for <span className="accent-text">{query}</span></> : 'All products'}
            </h1>
            <p className="results-meta small muted">
              {loading
                ? 'Searching…'
                : data
                  ? `${data.totalResults} product${data.totalResults === 1 ? '' : 's'}`
                  : ''}
              {data && data.fetchedLive && (
                <span className="badge badge-accent results-live">fetched live</span>
              )}
            </p>
          </div>

          <label className="results-sort">
            <span className="xs subtle">Sort</span>
            <select
              className="select"
              value={sort}
              onChange={(e) => updateParam('sort', e.target.value)}
              aria-label="Sort results by"
            >
              {SORTS.map((option) => (
                <option key={option.value} value={option.value}>{option.label}</option>
              ))}
            </select>
          </label>
        </header>

        {/* What the phrase was understood to mean. Showing the interpretation is
            what makes the natural-language search trustworthy rather than magic. */}
        {data && data.interpretedAs && data.interpretedAs.length > 0 && (
          <div className="row-wrap chips-row">
            <span className="xs subtle">Understood as</span>
            {data.interpretedAs.map((chip) => (
              <span className="badge badge-accent" key={chip}>{chip}</span>
            ))}
          </div>
        )}

        {/* Active filters, each removable in one click. */}
        {activeFilters.length > 0 && (
          <div className="row-wrap chips-row">
            <span className="xs subtle">Filters</span>
            {activeFilters.map(([key, value]) => (
              <button
                key={key}
                type="button"
                className="badge chip-removable"
                onClick={() => changeFilters({ ...filters, [key]: '' })}
                title="Remove this filter"
              >
                {filterLabel(key, value)}
                <span aria-hidden="true" className="chip-x">×</span>
              </button>
            ))}
            <button
              type="button"
              className="btn btn-ghost btn-sm"
              onClick={() => changeFilters({ ...EMPTY_FILTERS })}
            >
              Clear all
            </button>
          </div>
        )}

        <PlatformStatus compact />

        <div className="search-layout">
          <aside className="search-filters">
            <FilterPanel filters={filters} onChange={changeFilters} />
          </aside>

          <div className="search-results stack">
            {loading && <CardsLoading count={9} />}
            {error && <ErrorState error={error} onRetry={reload} />}

            {!loading && !error && products.length === 0 && (
              <EmptyState title="Nothing matched that search">
                Try a broader phrase, or clear a filter. If the marketplaces are switched off or
                out of quota, only products already stored can be shown.
              </EmptyState>
            )}

            {!loading && !error && products.length > 0 && (
              <>
                <div className="product-grid">
                  {products.map((product) => (
                    <ProductCard key={product.id} product={product} />
                  ))}
                </div>

                {totalPages > 1 && (
                  <nav className="pagination" aria-label="Pagination">
                    <button
                      type="button"
                      className="btn btn-sm"
                      disabled={page <= 0}
                      onClick={() => updateParam('page', String(page - 1))}
                    >
                      ← Previous
                    </button>
                    <span className="small muted">
                      Page <strong>{page + 1}</strong> of {totalPages}
                    </span>
                    <button
                      type="button"
                      className="btn btn-sm"
                      disabled={page >= totalPages - 1}
                      onClick={() => updateParam('page', String(page + 1))}
                    >
                      Next →
                    </button>
                  </nav>
                )}
              </>
            )}
          </div>
        </div>
      </div>
    </div>
  )
}
