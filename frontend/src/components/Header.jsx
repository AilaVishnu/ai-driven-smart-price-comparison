import { Link, NavLink, useNavigate } from 'react-router-dom'
import { useApp } from '../hooks/AppContext.jsx'
import SearchBar from './SearchBar.jsx'

export default function Header() {
  const { user, logout, theme, cycleTheme } = useApp()
  const navigate = useNavigate()

  const themeLabel = theme === 'system' ? 'Auto' : theme === 'light' ? 'Light' : 'Dark'
  const themeIcon = theme === 'system' ? '◐' : theme === 'light' ? '☀' : '☾'

  return (
    <header className="site-header">
      <div className="container site-header-inner">
        <Link to="/" className="brand" aria-label="Smart Price Comparison, home">
          <span className="brand-mark" aria-hidden="true">₹</span>
          <span className="brand-text">
            <strong>Smart Price</strong>
            <span className="brand-sub">Comparison</span>
          </span>
        </Link>

        <div className="header-search">
          <SearchBar compact />
        </div>

        <nav className="header-nav" aria-label="Main">
          <NavLink to="/deals" className="header-link">Deals</NavLink>
          <NavLink to="/compare" className="header-link">Compare</NavLink>
          {user && <NavLink to="/favorites" className="header-link">Saved</NavLink>}
          {user && <NavLink to="/history" className="header-link">History</NavLink>}
        </nav>

        <div className="header-actions">
          <button
            type="button"
            className="btn btn-ghost btn-sm"
            onClick={cycleTheme}
            title={`Theme: ${themeLabel}. Click to change.`}
            aria-label={`Theme: ${themeLabel}. Click to change.`}
          >
            <span aria-hidden="true">{themeIcon}</span>
          </button>

          {user ? (
            <div className="row" style={{ gap: 'var(--space-2)' }}>
              <span className="small muted user-name" title={user.email}>{user.name}</span>
              <button type="button" className="btn btn-sm" onClick={logout}>Sign out</button>
            </div>
          ) : (
            <div className="row" style={{ gap: 'var(--space-2)' }}>
              <button type="button" className="btn btn-sm" onClick={() => navigate('/login')}>
                Sign in
              </button>
              <button
                type="button"
                className="btn btn-primary btn-sm"
                onClick={() => navigate('/register')}
              >
                Register
              </button>
            </div>
          )}
        </div>
      </div>
    </header>
  )
}
