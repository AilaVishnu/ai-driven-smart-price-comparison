import { useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { useApp } from '../hooks/AppContext.jsx'

export default function Login() {
  const { login } = useApp()
  const navigate = useNavigate()

  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState(null)
  const [busy, setBusy] = useState(false)

  async function submit(event) {
    event.preventDefault()
    setBusy(true)
    setError(null)
    try {
      await login(email, password)
      navigate('/')
    } catch (e) {
      setError(e.message || 'Could not sign in')
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="page container auth-page">
      <div className="card card-padded auth-card">
        <h1 style={{ fontSize: 'var(--text-2xl)' }}>Sign in</h1>
        <p className="small muted">
          An account keeps your saved products, searches and comparisons. Browsing and
          comparing work without one.
        </p>

        <form onSubmit={submit} className="stack" style={{ marginTop: 'var(--space-4)' }}>
          <div className="field">
            <label className="label" htmlFor="email">Email</label>
            <input
              id="email"
              className="input"
              type="email"
              autoComplete="email"
              required
              value={email}
              onChange={(e) => setEmail(e.target.value)}
            />
          </div>

          <div className="field">
            <label className="label" htmlFor="password">Password</label>
            <input
              id="password"
              className="input"
              type="password"
              autoComplete="current-password"
              required
              value={password}
              onChange={(e) => setPassword(e.target.value)}
            />
          </div>

          {error && <p className="small auth-error" role="alert">{error}</p>}

          <button type="submit" className="btn btn-primary" disabled={busy}>
            {busy ? 'Signing in...' : 'Sign in'}
          </button>
        </form>

        <p className="small muted" style={{ marginTop: 'var(--space-4)', marginBottom: 0 }}>
          No account yet? <Link to="/register">Create one</Link>.
        </p>
      </div>
    </div>
  )
}
