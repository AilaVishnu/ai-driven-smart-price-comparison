/*
 * Thin wrapper over fetch.
 *
 * Vite proxies /api to the backend in development, so requests stay same-origin
 * and CORS never enters the picture during local work.
 */

const TOKEN_KEY = 'spc.token'

/**
 * localStorage throws outright in some contexts (private windows with site data
 * blocked, embedded previews), so every access is guarded. A missing token just
 * means signed out.
 */
export function getToken() {
  try {
    return localStorage.getItem(TOKEN_KEY)
  } catch {
    return null
  }
}

export function setToken(token) {
  try {
    if (token) localStorage.setItem(TOKEN_KEY, token)
    else localStorage.removeItem(TOKEN_KEY)
  } catch {
    // Not fatal: the session simply will not survive a reload.
  }
}

export class ApiError extends Error {
  constructor(message, status, body) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.body = body
  }
}

async function request(path, { method = 'GET', body, signal } = {}) {
  const headers = { Accept: 'application/json' }
  if (body !== undefined) headers['Content-Type'] = 'application/json'

  const token = getToken()
  if (token) headers.Authorization = `Bearer ${token}`

  let response
  try {
    response = await fetch(`/api${path}`, {
      method,
      headers,
      body: body === undefined ? undefined : JSON.stringify(body),
      signal,
    })
  } catch (e) {
    if (e.name === 'AbortError') throw e
    throw new ApiError('Could not reach the server. Is the backend running on port 8080?', 0, null)
  }

  if (response.status === 204) return null

  const text = await response.text()
  let payload = null
  if (text) {
    try {
      payload = JSON.parse(text)
    } catch {
      payload = text
    }
  }

  if (!response.ok) {
    // The backend returns a structured error body; prefer its message over a
    // generic status line so the user sees something actionable.
    const message =
      (payload && payload.message) ||
      (typeof payload === 'string' && payload) ||
      `Request failed (${response.status})`
    throw new ApiError(message, response.status, payload)
  }

  return payload
}

function query(params) {
  const search = new URLSearchParams()
  Object.entries(params || {}).forEach(([key, value]) => {
    if (value !== undefined && value !== null && value !== '') {
      search.set(key, value)
    }
  })
  const s = search.toString()
  return s ? `?${s}` : ''
}

export const api = {
  // --- catalogue ---
  search: (params, signal) => request(`/products/search${query(params)}`, { signal }),
  product: (id, signal) => request(`/products/${id}`, { signal }),
  offers: (id) => request(`/products/${id}/offers`),
  reviews: (id) => request(`/products/${id}/reviews`),
  priceHistory: (id, days = 90) => request(`/products/${id}/price-history?days=${days}`),
  forecast: (id) => request(`/products/${id}/forecast`),
  similar: (id, limit = 6) => request(`/products/${id}/similar?limit=${limit}`),
  categories: () => request('/categories'),
  platforms: () => request('/platforms'),
  deals: (limit = 24) => request(`/deals?limit=${limit}`),
  crossPlatform: (limit = 8) => request(`/cross-platform?limit=${limit}`),

  // --- comparison ---
  compare: (productIds, weights) =>
    request('/compare', { method: 'POST', body: { productIds, weights } }),
  compareHistory: () => request('/compare/history'),

  // --- auth ---
  register: (payload) => request('/auth/register', { method: 'POST', body: payload }),
  login: (payload) => request('/auth/login', { method: 'POST', body: payload }),
  me: () => request('/auth/me'),

  // --- account ---
  favorites: () => request('/favorites'),
  addFavorite: (productId) => request(`/favorites/${productId}`, { method: 'POST' }),
  removeFavorite: (productId) => request(`/favorites/${productId}`, { method: 'DELETE' }),
  searchHistory: () => request('/history/search'),
}
