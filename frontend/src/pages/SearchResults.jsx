import { useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import ProductCard from '../components/ProductCard.jsx'
import FilterPanel from '../components/FilterPanel.jsx'
import PlatformStatus from '../components/PlatformStatus.jsx'
import { CardsLoading, EmptyState, ErrorState } from '../components/Common.jsx'
import { api } from '../api/client.js'
import { useApi } from '../hooks/useApi.js'

const SORTS = [
  { value: 'relevance', label: 'Best value' },
  { value: 'price_asc', label: 'Price: low to high' },
  { value: 'price_desc', label: 'Price: high to low' },
  { value: 'rating_desc', label: 'Highest rated' },
  { value: 'discount_desc', label: 'Biggest discount' },
  { value: 'savings_desc', label: 'Biggest saving' },
]

export default function SearchResults() {
  const [params, setParams] = useSearchParams()
  const query = params.get('q') || ''
  const sort = params.get('sort') || 'relevance'
  const page = Number(params.get('page') || 0)

  const [filters, setFilters] = useState({
    minPrice: params.get('minPrice') || '',
    maxPrice: params.get('maxPrice') || '',
    brand: params.get('brand') || '',
    category: params.get('category') || '',
    minRating: params.get('minRating') || '',
    platform: params.get('platform') || '',
    inStock: params.get('inStock') || '',
    discounted: params.get('discounted') || '',
  })

  const { data, error, loading, reload } = useApi(
    (signal) => api.search({ q: query, page, size: 24, sort, ...filters }, signal),
    [query, page, sort, JSON.stringify(filters)]
  )

  function updateParam(key, value) {
    const next = new URLSearchParams(params)
    if (value) next.set(key, value)
    else next.delete(key)
    // Any change to the query shape invalidates the current page number.
    if (key !== 'page') next.delete('page')
    setParams(next)
  }

  const products = data ? data.products : []
  const totalPages = data ? data.totalPages : 0

  return (
    <div className="page">
      <div className="container stack">
        <div className="row-wrap search-head">
          <h1 className="search-title">
            {query ? <>Results for <span className="accent-text">{query}</span></> : 'All products'}
          </h1>
          {data && (
            <span className="small muted">
              {data.totalResults} product{data.totalResults === 1 ? '' : 's'}
              {data.fetchedLive && <span className="badge badge-accent" style={{ marginLeft: 8 }}>
                fetched live
              </span>}
            </span>
          )}
        </div>

        {/* How the phrase was parsed. Showing the interpretation is what makes
            the natural-language search trustworthy rather than magic. */}
        {data && data.interpretedAs && data.interpretedAs.length > 0 && (
          <div className="row-wrap">
            <span className="xs subtle">Understood as:</span>
            {data.interpretedAs.map((chip) => (
              <span className="badge badge-accent" key={chip}>{chip}</span>
            ))}
          </div>
        )}

        <PlatformStatus compact />

        <div className="search-layout">
          <aside className="search-filters">
            <FilterPanel
              filters={filters}
              onChange={(next) => {
                setFilters(next)
                const p = new URLSearchParams(params)
                p.delete('page')
                setParams(p)
              }}
            />
          </aside>

          <div className="search-results stack">
            <div className="row-wrap">
              <label className="xs subtle" htmlFor="sort">Sort by</label>
              <select
                id="sort"
                className="select"
                style={{ width: 'auto' }}
                value={sort}
                onChange={(e) => updateParam('sort', e.target.value)}
              >
                {SORTS.map((option) => (
                  <option key={option.value} value={option.value}>{option.label}</option>
                ))}
              </select>
            </div>

            {loading && <CardsLoading count={9} />}
            {error && <ErrorState error={error} onRetry={reload} />}

            {!loading && !error && products.length === 0 && (
              <EmptyState title="Nothing matched that search">
                Try a broader phrase, or relax the filters. If the marketplaces are switched
                off, the catalogue only covers the fallback sources.
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
                  <div className="row pagination">
                    <button
                      type="button"
                      className="btn btn-sm"
                      disabled={page <= 0}
                      onClick={() => updateParam('page', String(page - 1))}
                    >
                      Previous
                    </button>
                    <span className="small muted">
                      Page {page + 1} of {totalPages}
                    </span>
                    <button
                      type="button"
                      className="btn btn-sm"
                      disabled={page >= totalPages - 1}
                      onClick={() => updateParam('page', String(page + 1))}
                    >
                      Next
                    </button>
                  </div>
                )}
              </>
            )}
          </div>
        </div>
      </div>
    </div>
  )
}
