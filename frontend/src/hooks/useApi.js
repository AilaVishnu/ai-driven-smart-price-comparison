import { useCallback, useEffect, useRef, useState } from 'react'

/**
 * Runs an API call and tracks its lifecycle.
 *
 * <p>Two things it gets right that a naive useEffect does not: the request is
 * aborted when dependencies change or the component unmounts, so a slow search
 * cannot overwrite a newer one with stale results; and state is never set after
 * unmount.
 *
 * @param fetcher receives an AbortSignal and returns a promise
 * @param deps    re-runs when these change, like useEffect
 */
export function useApi(fetcher, deps = [], { skip = false } = {}) {
  const [data, setData] = useState(null)
  const [error, setError] = useState(null)
  const [loading, setLoading] = useState(!skip)
  const [reloadToken, setReloadToken] = useState(0)

  const fetcherRef = useRef(fetcher)
  fetcherRef.current = fetcher

  useEffect(() => {
    if (skip) {
      setLoading(false)
      return undefined
    }

    const controller = new AbortController()
    let active = true

    setLoading(true)
    setError(null)

    Promise.resolve(fetcherRef.current(controller.signal))
      .then((result) => {
        if (active) {
          setData(result)
          setError(null)
        }
      })
      .catch((e) => {
        // An abort is the expected outcome of a superseded request, not a fault.
        if (active && e.name !== 'AbortError') {
          setError(e)
          setData(null)
        }
      })
      .finally(() => {
        if (active) setLoading(false)
      })

    return () => {
      active = false
      controller.abort()
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [...deps, reloadToken, skip])

  const reload = useCallback(() => setReloadToken((n) => n + 1), [])

  return { data, error, loading, reload }
}

/** Debounces a value, so typing does not fire a request per keystroke. */
export function useDebounced(value, delay = 350) {
  const [debounced, setDebounced] = useState(value)

  useEffect(() => {
    const timer = setTimeout(() => setDebounced(value), delay)
    return () => clearTimeout(timer)
  }, [value, delay])

  return debounced
}

/** localStorage-backed state that degrades to in-memory when storage is unavailable. */
export function useStoredState(key, initial) {
  const [value, setValue] = useState(() => {
    try {
      const raw = localStorage.getItem(key)
      return raw === null ? initial : JSON.parse(raw)
    } catch {
      return initial
    }
  })

  useEffect(() => {
    try {
      localStorage.setItem(key, JSON.stringify(value))
    } catch {
      // Storage blocked; the value still works for this session.
    }
  }, [key, value])

  return [value, setValue]
}
