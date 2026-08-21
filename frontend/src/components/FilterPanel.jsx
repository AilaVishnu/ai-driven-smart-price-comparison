import { api } from '../api/client.js'
import { useApi } from '../hooks/useApi.js'

export default function FilterPanel({ filters, onChange }) {
  const { data: categories } = useApi(() => api.categories(), [])
  const { data: platforms } = useApi(() => api.platforms(), [])

  function set(key, value) {
    onChange({ ...filters, [key]: value })
  }

  const active = Object.values(filters).some((v) => v !== '' && v !== null && v !== undefined)

  return (
    <div className="card card-padded stack filter-panel">
      <div className="row">
        <h2 style={{ fontSize: 'var(--text-base)' }}>Filters</h2>
        <span className="spacer" />
        {active && (
          <button
            type="button"
            className="btn btn-ghost btn-sm"
            onClick={() =>
              onChange({
                minPrice: '', maxPrice: '', brand: '', category: '',
                minRating: '', platform: '', inStock: '', discounted: '',
              })
            }
          >
            Clear
          </button>
        )}
      </div>

      <div className="field">
        <span className="label">Price range</span>
        <div className="row" style={{ gap: 'var(--space-2)' }}>
          <input
            className="input"
            type="number"
            inputMode="numeric"
            min="0"
            placeholder="Min"
            value={filters.minPrice}
            onChange={(e) => set('minPrice', e.target.value)}
            aria-label="Minimum price in rupees"
          />
          <input
            className="input"
            type="number"
            inputMode="numeric"
            min="0"
            placeholder="Max"
            value={filters.maxPrice}
            onChange={(e) => set('maxPrice', e.target.value)}
            aria-label="Maximum price in rupees"
          />
        </div>
      </div>

      <div className="field">
        <label className="label" htmlFor="filter-brand">Brand</label>
        <input
          id="filter-brand"
          className="input"
          type="text"
          placeholder="Any brand"
          value={filters.brand}
          onChange={(e) => set('brand', e.target.value)}
        />
      </div>

      <div className="field">
        <label className="label" htmlFor="filter-category">Category</label>
        <select
          id="filter-category"
          className="select"
          value={filters.category}
          onChange={(e) => set('category', e.target.value)}
        >
          <option value="">Any category</option>
          {(categories || []).map((c) => (
            <option key={c.slug} value={c.slug}>{c.name}</option>
          ))}
        </select>
      </div>

      <div className="field">
        <label className="label" htmlFor="filter-rating">Minimum rating</label>
        <select
          id="filter-rating"
          className="select"
          value={filters.minRating}
          onChange={(e) => set('minRating', e.target.value)}
        >
          <option value="">Any rating</option>
          <option value="4.5">4.5 and above</option>
          <option value="4">4 and above</option>
          <option value="3.5">3.5 and above</option>
          <option value="3">3 and above</option>
        </select>
      </div>

      <div className="field">
        <label className="label" htmlFor="filter-platform">Platform</label>
        <select
          id="filter-platform"
          className="select"
          value={filters.platform}
          onChange={(e) => set('platform', e.target.value)}
        >
          <option value="">Any platform</option>
          {(platforms || []).map((p) => (
            <option key={p.code} value={p.code}>{p.displayName}</option>
          ))}
        </select>
      </div>

      <label className="row filter-check">
        <input
          type="checkbox"
          checked={filters.inStock === 'true'}
          onChange={(e) => set('inStock', e.target.checked ? 'true' : '')}
        />
        <span className="small">In stock only</span>
      </label>

      <label className="row filter-check">
        <input
          type="checkbox"
          checked={filters.discounted === 'true'}
          onChange={(e) => set('discounted', e.target.checked ? 'true' : '')}
        />
        <span className="small">Discounted only</span>
      </label>
    </div>
  )
}
