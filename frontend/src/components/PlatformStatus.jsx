import { api } from '../api/client.js'
import { useApi } from '../hooks/useApi.js'
import { platformAccent } from '../utils/format.js'

/**
 * Which platforms are actually live right now.
 *
 * <p>The state is always stated, in both directions. An earlier version only
 * labelled the bad case - a working source showed its remaining quota and
 * nothing else - so a healthy row and a row that had failed to render looked
 * identical, and there was no way to tell that a source was fine rather than
 * unreported.
 *
 * <p>Deliberately candid about the rest too: the marketplaces need a free API
 * key, and without one nothing new can be fetched at all. Saying so beats
 * leaving somebody to wonder why a search came back thin.
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
                background: platform.live
                  ? platformAccent(platform.code)
                  : 'var(--text-subtle)',
              }}
              aria-hidden="true"
            />
            {platform.displayName}

            {/* Stated either way, never inferred from what is missing. */}
            <span className={platform.live ? 'status-pill status-live' : 'status-pill status-off'}>
              {platform.live ? 'live' : 'off'}
            </span>

            {platform.live && platform.monthlyQuota > 0 && (
              <span className="subtle xs">{platform.quotaRemaining} left</span>
            )}
          </span>
        ))}
      </div>

      {/* When something is down, say why rather than leaving a bare label. */}
      {marketplaces.some((p) => !p.live) && (
        <p className="xs subtle platform-status-why">
          {marketplaces
            .filter((p) => !p.live)
            .map((p) => `${p.displayName}: ${p.note}`)
            .join(' · ')}
        </p>
      )}

      {!anyLive && !compact && (
        <p className="xs subtle platform-status-why">
          No marketplace is reachable, so searches return only products already stored.
          See <code className="mono">docs/api-keys-setup.md</code>.
        </p>
      )}
    </div>
  )
}
