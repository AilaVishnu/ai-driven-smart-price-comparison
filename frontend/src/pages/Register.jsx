import { useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { useApp } from '../hooks/AppContext.jsx'

const MIN_PASSWORD = 8

export default function Register() {
  const { register } = useApp()
  const navigate = useNavigate()

  const [name, setName] = useState('')
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState(null)
  const [busy, setBusy] = useState(false)

  const tooShort = password.length > 0 && password.length < MIN_PASSWORD

  async function submit(event) {
    event.preventDefault()
    if (tooShort) return
    setBusy(true)
    setError(null)
    try {
      await register(name, email, password)
      navigate('/')
    } catch (e) {
      setError(e.message || 'Could not create the account')
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="page container auth-page">
      <div className="card card-padded auth-card">
        <h1 style={{ fontSize: 'var(--text-2xl)' }}>Create an account</h1>
        <p className="small muted">
          Only needed for saved products and history. Search and comparison are open to
          everyone.
        </p>

        <form onSubmit={submit} className="stack" style={{ marginTop: 'var(--space-4)' }}>
          <div className="field">
            <label className="label" htmlFor="name">Name</label>
            <input
              id="name"
              className="input"
              type="text"
              autoComplete="name"
              required
              maxLength={120}
              value={name}
              onChange={(e) => setName(e.target.value)}
            />
          </div>

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
              autoComplete="new-password"
              required
              minLength={MIN_PASSWORD}
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              aria-describedby="password-hint"
            />
            <span
              id="password-hint"
              className={tooShort ? 'xs auth-error' : 'xs subtle'}
            >
              At least {MIN_PASSWORD} characters.
            </span>
          </div>

          {error && <p className="small auth-error" role="alert">{error}</p>}

          <button type="submit" className="btn btn-primary" disabled={busy || tooShort}>
            {busy ? 'Creating...' : 'Create account'}
          </button>
        </form>

        <p className="small muted" style={{ marginTop: 'var(--space-4)', marginBottom: 0 }}>
          Already registered? <Link to="/login">Sign in</Link>.
        </p>
      </div>
    </div>
  )
}
