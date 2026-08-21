import { api } from '../api/client.js'
import { useApi } from '../hooks/useApi.js'
import { platformAccent } from '../utils/format.js'

/**
 * Which platforms are actually live right now.
 *
 * <p>Deliberately candid. The marketplace adapters need a free API key, and
 * without one no new listings can be fetched at all. Saying so beats leaving a
 * user to wonder why a search came back thin, so the state of every source is
 * on the page, including how much monthly quota is left.
 */
export default function PlatformStatus({ compact = false }) {
  const { data: platforms, loading, error } = useApi(() => api.platforms(), [])

  if (loading || error || !platforms) return null

  const marketplaces = platforms.filter((p) => p.primary)
  const anyLive = marketplaces.some((p) => p.live)

  return (
    <div className={compact ? 'platform-status platform-status-compact' : 'platform-status card card-padded'}>
      <div className="row-wrap platform-status-list">
        <span className="small strong">Sources</span>
        {platforms.map((platform) => (
          <span
            key={platform.code}
            className={platform.live ? 'badge platform-live' : 'badge platform-offline'}
            title={platform.note}
          >
            <span
              className="platform-dot"
              style={{
                background: platform.live ? platformAccent(platform.code) : 'var(--text-subtle)',
              }}
              aria-hidden="true"
            />
            {platform.displayName}
            {platform.live && platform.monthlyQuota > 0 && (
              <span className="subtle xs"> · {platform.quotaRemaining} calls left</span>
            )}
            {!platform.live && <span className="subtle xs"> · off</span>}
          </span>
        ))}
      </div>

      {!anyLive && !compact && (
        <p className="xs subtle" style={{ marginTop: 'var(--space-2)', marginBottom: 0 }}>
          Amazon.in and Flipkart are switched off because no RapidAPI key is configured.
          Searches will only return products already stored; nothing new can be fetched
          until a key is added. See <code className="mono">docs/api-keys-setup.md</code>.
        </p>
      )}
    </div>
  )
}
