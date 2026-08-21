# API reference

Base URL: `http://localhost:8080`

All responses are JSON. Prices are in **INR**. Authentication is a bearer token:

```
Authorization: Bearer <token>
```

Browsing, searching and comparing are open to anonymous callers. Only
favourites, history and the admin diagnostics require an account.

Errors share one shape:

```json
{
  "status": 404,
  "error": "Not Found",
  "message": "No product with id 42",
  "path": "/api/products/42",
  "timestamp": "2026-08-21T10:12:03.221Z",
  "fieldErrors": { "email": "must be a well-formed email address" }
}
```

`fieldErrors` appears only on validation failures.

---

## Search

### `GET /api/products/search`

| Parameter | Type | Notes |
|---|---|---|
| `q` | string | Free text. Budget, brand, category and rating are parsed out of plain phrasing. |
| `page` | int | Zero-based. Default `0`. |
| `size` | int | Default `20`, capped at `100`. |
| `minPrice`, `maxPrice` | number | INR. |
| `brand` | string | Exact, case-insensitive. |
| `category` | string | Category slug — see `/api/categories`. |
| `minRating` | number | 0–5. |
| `platform` | string | Platform code, e.g. `AMAZON_IN`. |
| `inStock` | `true` | In-stock listings only. |
| `discounted` | `true` | Discounted listings only. |
| `sort` | enum | `relevance` (value score, default), `price_asc`, `price_desc`, `rating_desc`, `discount_desc`, `savings_desc`. |

An explicit parameter overrides the same filter parsed from the phrase.

```json
{
  "query": "gaming laptop under 60k from dell",
  "interpretedAs": ["Budget up to Rs 60000", "Brand: Dell", "Category: laptops"],
  "products": [
    {
      "id": 42,
      "title": "Dell G15 Gaming Laptop",
      "brand": "Dell",
      "category": "laptops",
      "imageUrl": "https://...",
      "bestPrice": 58990.00,
      "highestPrice": 62990.00,
      "potentialSaving": 4000.00,
      "bestPlatformCode": "FLIPKART",
      "bestPlatformName": "Flipkart",
      "offerCount": 2,
      "platformCount": 2,
      "rating": 4.30,
      "ratingCount": 1284,
      "maxDiscountPct": 18.50,
      "inStock": true,
      "valueScore": 82.4
    }
  ],
  "page": 0, "size": 20, "totalResults": 7, "totalPages": 1,
  "fetchedLive": false,
  "sourcesUsed": ["Amazon.in", "Flipkart"]
}
```

`interpretedAs` is what the phrase was understood to mean — surface it so the
parse is visible. `valueScore` is the TOPSIS closeness coefficient (0–100),
computed across the whole result set before paging; it is `null` when fewer than
two products matched, since there is then no ideal to measure against.

---

## Product

### `GET /api/products/{id}`

Optional `historyDays` (default `90`).

Returns `summary` (as above), `description`, `offers[]`, `reviews[]`,
`sentiment`, `forecast`, `priceHistory[]` and `similar[]`.

**Offer**

```json
{
  "id": 88, "platformCode": "AMAZON_IN", "platformName": "Amazon.in",
  "title": "Dell G15 ...", "url": "https://...", "price": 58990.00,
  "originalPrice": 72390.00, "discountPct": 18.50,
  "rating": 4.30, "ratingCount": 1284, "inStock": true,
  "deliveryDays": 2, "warranty": "1 year", "returnPolicy": "7 days",
  "seller": "Appario Retail", "fetchedAt": "2026-08-21T09:00:00Z",
  "bestPrice": true
}
```

`bestPrice` marks the cheapest **in-stock** offer — an out-of-stock bargain is
not something a buyer can act on.

**Sentiment**

```json
{
  "averageScore": 0.42, "overallLabel": "POSITIVE",
  "positiveCount": 18, "neutralCount": 5, "negativeCount": 3, "reviewCount": 26,
  "aspects": {
    "battery":  { "aspect": "battery",  "score":  0.61, "mentions": 9, "label": "POSITIVE" },
    "delivery": { "aspect": "delivery", "score": -0.38, "mentions": 4, "label": "NEGATIVE" }
  }
}
```

Scores run −1 to +1.

**Forecast**

```json
{
  "observations": 90,
  "currentPrice": 58990.00, "minPrice": 56990.00,
  "maxPrice": 64990.00, "averagePrice": 60120.00, "movingAverage7d": 59210.00,
  "slopePerDay": -12.40, "rSquared": 0.66, "volatility": 0.041,
  "trend": "FALLING",
  "predicted14d": 58816.00, "predicted30d": 58618.00,
  "signal": "BUY_NOW",
  "rationale": "Current price is within 5% of the lowest price seen in this period",
  "containsSimulatedData": true
}
```

`signal` is `BUY_NOW`, `WAIT`, `HOLD`, or `INSUFFICIENT_DATA` (fewer than five
observations). Read `rSquared` as the confidence in any trend-based advice.
`containsSimulatedData` means part of the series was backfilled for
demonstration — show it as such.

### Sub-resources

| Endpoint | Returns |
|---|---|
| `GET /api/products/{id}/offers` | Offers, cheapest first |
| `GET /api/products/{id}/reviews` | Reviews with per-review sentiment |
| `GET /api/products/{id}/sentiment` | The sentiment summary alone |
| `GET /api/products/{id}/price-history?days=90` | `{at, price, platformCode, source}` — `source` is `OBSERVED` or `SIMULATED` |
| `GET /api/products/{id}/forecast` | The forecast alone |
| `GET /api/products/{id}/similar?limit=6` | Content-based recommendations |

---

## Comparison

### `POST /api/compare`

```json
{
  "productIds": [42, 51, 63],
  "weights": { "price": 40, "rating": 30, "discount": 20, "delivery": 10 }
}
```

Two to four products. Weights are optional, may name any subset, and are
normalised server-side — so slider positions can be sent raw. Criterion keys:
`price`, `rating`, `ratingCount`, `discount`, `sentiment`, `delivery`,
`availability`.

```json
{
  "products": [ /* full detail per product */ ],
  "ranking": [
    {
      "productId": 51, "label": "…", "score": 78.4, "rank": 1,
      "breakdown": {
        "price": {
          "criterion": "price", "label": "Price", "benefit": false,
          "rawValue": 58990.0, "weight": 0.4,
          "weightedNormalized": 0.213, "criterionScore": 100.0, "isBest": true
        }
      }
    }
  ],
  "winnerProductId": 51,
  "winnerReason": "Best on price, rating and delivery speed",
  "weightsUsed": { "price": 0.4, "rating": 0.3, "discount": 0.2, "delivery": 0.1 },
  "note": null
}
```

`criterionScore` is how close this product came to the best value on that
criterion alone (0–100), before weighting. `note` is populated for degenerate
cases, such as a single alternative where comparison against an ideal is not
meaningful.

### `GET /api/compare/history`

Requires authentication.

---

## Catalogue

### `GET /api/platforms`

```json
[
  {
    "code": "AMAZON_IN", "displayName": "Amazon.in",
    "primary": true, "live": false, "requiresKey": true,
    "quotaRemaining": 200, "quotaUsedThisMonth": 0, "monthlyQuota": 200,
    "note": "No RapidAPI key configured - see docs/api-keys-setup.md"
  }
]
```

Reports the true state of every source. `quotaRemaining` is `-1` for sources
with no quota limit. Surface `note` — it is what tells a user why a marketplace
is missing.

### `GET /api/categories` · `GET /api/deals?limit=24`

---

## Authentication

| Endpoint | Body | Returns |
|---|---|---|
| `POST /api/auth/register` | `{name, email, password}` — password ≥ 8 chars | `201` with `{token, user, expiresInMs}` |
| `POST /api/auth/login` | `{email, password}` | `{token, user, expiresInMs}` |
| `GET /api/auth/me` | — | The current user |

Login returns the same message for an unknown address and a wrong password, so
the endpoint cannot be used to discover which addresses are registered.

---

## Account

All require authentication.

| Endpoint | Notes |
|---|---|
| `GET /api/favorites` | Saved products |
| `POST /api/favorites/{productId}` | Idempotent — favouriting twice is not an error |
| `DELETE /api/favorites/{productId}` | `204` |
| `GET /api/favorites/{productId}/status` | `{ "favorite": true }` |
| `GET /api/history/search` | Recent searches |

---

## Diagnostics (dev profile only)

Not mapped outside the `dev` profile, and role-restricted if ever enabled.

| Endpoint | Purpose |
|---|---|
| `GET /api/admin/providers/probe?q=iphone` | Raw, unparsed provider responses — use this to confirm real field names on the first live call |
| `GET /api/admin/matching/preview?q=iphone` | Runs a live search through the matcher and shows the clusters formed, without persisting |
| `GET /api/admin/matching/explain?a=<title>&b=<title>` | Component scores for one pair, for tuning the threshold |
| `GET /api/admin/quota` | Per-provider quota state |
