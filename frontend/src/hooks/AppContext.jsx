import { createContext, useCallback, useContext, useEffect, useMemo, useState } from 'react'
import { api, getToken, setToken } from '../api/client'
import { useStoredState } from './useApi'

const AppContext = createContext(null)

/** Comparison is capped to keep the table readable; the backend enforces the same limit. */
export const MAX_COMPARE = 4

export function AppProvider({ children }) {
  // ------------------------------------------------------------------ auth
  const [user, setUser] = useState(null)
  const [authChecked, setAuthChecked] = useState(false)

  useEffect(() => {
    // A stored token may be expired or belong to a deleted account, so it is
    // verified against the server rather than trusted on sight.
    if (!getToken()) {
      setAuthChecked(true)
      return
    }
    api
      .me()
      .then(setUser)
      .catch(() => {
        setToken(null)
        setUser(null)
      })
      .finally(() => setAuthChecked(true))
  }, [])

  const login = useCallback(async (email, password) => {
    const result = await api.login({ email, password })
    setToken(result.token)
    setUser(result.user)
    return result.user
  }, [])

  const register = useCallback(async (name, email, password) => {
    const result = await api.register({ name, email, password })
    setToken(result.token)
    setUser(result.user)
    return result.user
  }, [])

  const logout = useCallback(() => {
    setToken(null)
    setUser(null)
  }, [])

  // --------------------------------------------------------- compare tray
  const [compareIds, setCompareIds] = useStoredState('spc.compare', [])

  const toggleCompare = useCallback(
    (productId) => {
      setCompareIds((current) => {
        if (current.includes(productId)) {
          return current.filter((id) => id !== productId)
        }
        if (current.length >= MAX_COMPARE) {
          // Drop the oldest rather than refusing: silently ignoring a click
          // reads as a broken button.
          return [...current.slice(1), productId]
        }
        return [...current, productId]
      })
    },
    [setCompareIds]
  )

  const clearCompare = useCallback(() => setCompareIds([]), [setCompareIds])
  const inCompare = useCallback((id) => compareIds.includes(id), [compareIds])

  // ----------------------------------------------------------------- theme
  const [theme, setTheme] = useStoredState('spc.theme', 'system')

  useEffect(() => {
    const root = document.documentElement
    if (theme === 'system') {
      root.removeAttribute('data-theme')
    } else {
      root.setAttribute('data-theme', theme)
    }
  }, [theme])

  const cycleTheme = useCallback(
    () => setTheme((t) => (t === 'system' ? 'light' : t === 'light' ? 'dark' : 'system')),
    [setTheme]
  )

  // ------------------------------------------------------------- favorites
  const [favoriteIds, setFavoriteIds] = useState([])

  const refreshFavorites = useCallback(() => {
    if (!user) {
      setFavoriteIds([])
      return
    }
    api
      .favorites()
      .then((list) => setFavoriteIds(list.map((f) => f.product.id)))
      .catch(() => setFavoriteIds([]))
  }, [user])

  useEffect(() => {
    refreshFavorites()
  }, [refreshFavorites])

  const toggleFavorite = useCallback(
    async (productId) => {
      if (!user) return false
      const isFavorite = favoriteIds.includes(productId)
      // Optimistic: the heart responds immediately and is reverted on failure.
      setFavoriteIds((current) =>
        isFavorite ? current.filter((id) => id !== productId) : [...current, productId]
      )
      try {
        if (isFavorite) await api.removeFavorite(productId)
        else await api.addFavorite(productId)
        return !isFavorite
      } catch {
        setFavoriteIds((current) =>
          isFavorite ? [...current, productId] : current.filter((id) => id !== productId)
        )
        return isFavorite
      }
    },
    [user, favoriteIds]
  )

  const value = useMemo(
    () => ({
      user,
      authChecked,
      login,
      register,
      logout,
      compareIds,
      toggleCompare,
      clearCompare,
      inCompare,
      theme,
      cycleTheme,
      favoriteIds,
      toggleFavorite,
      refreshFavorites,
    }),
    [
      user, authChecked, login, register, logout,
      compareIds, toggleCompare, clearCompare, inCompare,
      theme, cycleTheme, favoriteIds, toggleFavorite, refreshFavorites,
    ]
  )

  return <AppContext.Provider value={value}>{children}</AppContext.Provider>
}

export function useApp() {
  const context = useContext(AppContext)
  if (!context) {
    throw new Error('useApp must be used inside AppProvider')
  }
  return context
}
