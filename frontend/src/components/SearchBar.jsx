import { useState } from 'react'
import { useNavigate, useSearchParams } from 'react-router-dom'

const EXAMPLES = [
  'gaming laptop under 60k from dell',
  'samsung phone between 20k and 40k',
  'headphones 4 star and above',
  'iphone 15 pro',
]

/**
 * The search box.
 *
 * <p>The example queries are not decoration: the backend parses budget, brand,
 * category and rating out of plain phrasing, and nobody discovers that from an
 * empty text field. Showing what it understands is what makes the feature
 * usable.
 */
export default function SearchBar({ compact = false, autoFocus = false }) {
  const navigate = useNavigate()
  const [params] = useSearchParams()
  const [value, setValue] = useState(params.get('q') || '')

  function submit(event) {
    event.preventDefault()
    const trimmed = value.trim()
    if (!trimmed) return
    navigate(`/search?q=${encodeURIComponent(trimmed)}`)
  }

  return (
    <div className={compact ? 'searchbar searchbar-compact' : 'searchbar'}>
      <form onSubmit={submit} role="search">
        <div className="searchbar-field">
          <span className="searchbar-icon" aria-hidden="true">⌕</span>
          <input
            className="input searchbar-input"
            type="search"
            value={value}
            onChange={(e) => setValue(e.target.value)}
            placeholder={compact ? 'Search products...' : 'Try: gaming laptop under 60k from dell'}
            aria-label="Search for a product"
            autoFocus={autoFocus}
          />
          <button type="submit" className="btn btn-primary searchbar-submit">
            Search
          </button>
        </div>
      </form>

      {!compact && (
        <div className="searchbar-examples">
          <span className="xs subtle">Understands plain phrasing:</span>
          {EXAMPLES.map((example) => (
            <button
              key={example}
              type="button"
              className="badge searchbar-example"
              onClick={() => {
                setValue(example)
                navigate(`/search?q=${encodeURIComponent(example)}`)
              }}
            >
              {example}
            </button>
          ))}
        </div>
      )}
    </div>
  )
}
