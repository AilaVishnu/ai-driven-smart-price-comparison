# Getting real Amazon.in and Flipkart data

The application runs without any API key. It just cannot show you real
marketplace data until you add one, and cross-platform price comparison — the
whole point of the system — needs it. This page walks through the one signup.

**Cost: nothing. No credit card is asked for on the free tiers used here.**

---

## Why RapidAPI rather than an official API

This was researched before the adapters were written, and the short version is
that the official routes are closed:

| Source | Status |
|---|---|
| **Flipkart Affiliate API** | Public signups **discontinued**. The documentation is still online but you cannot register for access. |
| **Amazon India PA-API 5.0** | Requires an Amazon Associates account with **3–10 qualifying sales in the last 30 days** *before* API access is granted — and PA-API was **deprecated on 15 May 2026**. Blocked twice over. |
| **Myntra / Ajio / Croma / Reliance Digital** | No public API of any kind. |
| **RapidAPI marketplace adapters** | ✅ Free tier, free key, no card. What this project uses. |

So RapidAPI is not a shortcut taken for convenience; it is the only realistic
free route to genuine Amazon.in and Flipkart listings.

---

## Step 1 — Create a free RapidAPI account

1. Go to <https://rapidapi.com> and sign up (GitHub or Google sign-in is fastest).
2. No payment details are required for the free tiers below.

## Step 2 — Subscribe to the two APIs

**Amazon**

1. Open <https://rapidapi.com/letscrape-6bRBa3QguO5/api/real-time-amazon-data>
2. Click **Subscribe to Test**, then pick the **Basic / free** plan.
3. Confirm. You are now subscribed; nothing is charged.

**Flipkart — subscribe to "Real-time Flipkart Data" (publisher: Ayush Somani)**

This is the one the project is configured for, and it works on the **free BASIC
plan** — with one important caveat that the adapter is built around.

Its `/product-search` endpoint is **paid-only**: on BASIC it answers
`401 This endpoint is disabled for your subscription`, despite the listing
advertising "Search functionality". What *is* included on BASIC:

| Endpoint | Free plan | Used for |
|---|---|---|
| `/products-by-category?categoryId=…&page=…` | ✅ | **How this project gets Flipkart products** |
| `/sub-categories?categoryId=…` | ✅ | Discovering category ids |
| `/product-details?…` | ✅ | Per-product detail |
| `/product-search?…` | ❌ paid | Not used |

So the Flipkart adapter **browses categories instead of searching**. That fits
the architecture rather than fighting it: everything fetched is persisted and
searches are served from the database first, so Flipkart populates the catalogue
by category at startup and its products are then matched against live Amazon
results. Cross-platform comparison works; only the route the data takes differs.

The free plan allows **300 requests/month** (reported in the
`x-ratelimit-requests-limit` header), and a full category seed costs seven.

Category ids were read from the live `/sub-categories` tree:

| Our category | Flipkart id |
|---|---|
| smartphones | `tyy/4io` |
| tablets | `tyy/hry` |
| laptops | `6bo/b5g` |
| headphones | `0pm/fcn` |
| televisions | `ckf/czl` |
| smartwatches | `ajy/buh` |
| accessories | `tyy/4mr` |

To add more, walk the tree: `/sub-categories?categoryId=6bo` lists Computers and
its children. Add the pair to `CATEGORY_IDS` in `FlipkartProvider`.

**A note on timeouts.** This API averages around five seconds and returns ~50KB
per category, so the HTTP socket timeout (`providers.http-timeout-ms`, default
30s) is deliberately much larger than the per-search deadline
(`providers.request-timeout-ms`, default 8s). Seeding can afford to wait; a user
typing in the search box cannot. Conflating the two caused every seed call to
time out.

---

**The other Flipkart APIs — there are several confusingly similar ones**

RapidAPI lists several Flipkart APIs with near-identical names, and **they are not
interchangeable**. Verified on this project:

| API on RapidAPI | Host | Free plan includes product search? |
|---|---|---|
| Real-Time Flipkart **Data** | `real-time-flipkart-data2.p.rapidapi.com` | ❌ **No** — `/product-search` returns `401 This endpoint is disabled for your subscription`. Only endpoints like `/get-subcategories` are included. |
| Real-Time Flipkart **API** (OpenDataPoint) | `real-time-flipkart-api.p.rapidapi.com` | Documents `/product-search`, `/product-details`, `/products-by-brand`. **This is what the project is configured for.** |

Subscribing to the wrong one produces an API that answers 200 on its sample
endpoint while the search this application needs stays locked — which looks like
a bug in the application and is not one.

1. Search RapidAPI rather than following a deep link — listing URLs change and
   go stale: <https://rapidapi.com/search/flipkart>
2. Subscribe to the **Basic / free** plan of a listing that offers product
   search.
3. Verify it before touching any config:

   ```powershell
   .\scripts\test-provider.ps1
   ```

   That probes every known Flipkart host with your key and prints exactly which
   situation each is in — subscribed and working, subscribed but paywalled, or
   not subscribed. When one works it prints the two lines to paste.

> Choosing a different Flipkart API is fine, but note its **host** and **search
> path** and set them in `application-local.properties` (step 4). Still no code
> change.

**How to tell which situation you are in**, from the backend log or
`GET /api/platforms`:

| Response | Meaning |
|---|---|
| `403 You are not subscribed to this API` | Not subscribed to *that host*. Subscribe, or point at the host you did subscribe to. |
| `401 This endpoint is disabled for your subscription` | Subscribed, but the free plan does not include this endpoint. Use a different API. |
| `404 Endpoint '/x' does not exist` | Host is right, path is wrong. Fix `search-path`. |
| `404 API doesn't exists` | The host itself is wrong. |

## Step 3 — Copy your key

On any API page, open the **Endpoints** tab and look at the code sample. The
value of the `X-RapidAPI-Key` header is your key. It is the same key for every
API you subscribe to.

## Step 4 — Give the key to the application

**Recommended — the local properties file (already created for you)**

Open:

```
backend/src/main/resources/application-local.properties
```

Find this line and paste your key straight after the `=`, with no quotes and no
spaces:

```properties
providers.rapidapi-key=
```

So it reads:

```properties
providers.rapidapi-key=a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6ab
```

Then restart the backend. **No command-line flag is needed** — the `dev` and
`mysql` profiles are mapped to include `local`, so the file is picked up
automatically. It is gitignored, so the key never enters version control.

The same file is also the right place for any other local override: quota
budgets, a different Flipkart host or search path, or a real JWT secret. Each is
listed there, commented out.

**Alternative — an environment variable**

If you would rather not have the key on disk at all:

```powershell
$env:RAPIDAPI_KEY = "your-key"     # current terminal only
setx RAPIDAPI_KEY "your-key"       # permanent; open a new terminal afterwards
```

The properties file wins if both are set, because a profile-specific file
outranks the default in `application.properties`.

> **Never** put the key in `application.properties` itself. That file is
> committed; a key in it is a key published.

### If the key is not taking effect

The startup log tells you which of the three states you are in:

| Log message | Meaning |
|---|---|
| `RapidAPI key detected; Amazon.in and Flipkart adapters are enabled.` | Working. |
| `The RapidAPI key still contains placeholder text, so it is being ignored.` | The template text is still in the file. Replace it with the real key. |
| `No RapidAPI key configured...` | The file is empty, or the key never reached the application. |

Placeholder text is deliberately ignored rather than sent, because a bad
credential produces a stream of 403s that look like a broken application instead
of an unfinished setup. A key that is much shorter than about 50 characters also
gets flagged, in case it was truncated while pasting.

## Step 5 — Confirm it worked

Start the backend and watch the startup log. It reports every source honestly:

```
--- Provider status ---
  Amazon.in [marketplace] configured - Live
  Flipkart [marketplace] configured - Live
```

Or ask the API:

```powershell
Invoke-RestMethod http://localhost:8080/api/platforms | Format-Table code,live,quotaRemaining,note
```

`live` should now be `True` for `AMAZON_IN` and `FLIPKART`.

---

## Step 6 — Check the response shapes (do this once, on the first run with a key)

RapidAPI serves its documentation from JavaScript-rendered pages that could not
be read without a key, so the adapters were written to match **several candidate
field names** rather than one guessed set. That makes them resilient, but it also
means the first live call is worth inspecting:

```powershell
Invoke-RestMethod "http://localhost:8080/api/admin/providers/probe?q=iphone" | ConvertTo-Json -Depth 6
```

This prints exactly what each marketplace returned. Compare it against
`AmazonIndiaProvider.toListing` and `FlipkartProvider.toListing`. If a field is
named differently, add the real name to the candidate list in that one method —
it is a one-line change, and every other part of the system is insulated from it.

If a search path is wrong, no code change is needed at all:

```properties
providers.sources.flipkart.search-path=/your-path?q={q}&page=1
```

Then confirm the matching engine is linking listings across the two platforms:

```powershell
Invoke-RestMethod "http://localhost:8080/api/admin/matching/preview?q=iphone%2015" |
  Select-Object listingsFetched,productsFormed,multiPlatformProducts
```

`multiPlatformProducts` above zero means an Amazon listing and a Flipkart listing
were recognised as the same product. That is the system working.

---

## About the quota

Free tiers run to a few hundred calls a month. The application is built around
that rather than in spite of it:

- **Everything fetched is stored.** A product costs quota once, then serves from
  the database forever.
- **Two caches sit in front of the providers** — a 10-minute in-memory cache and
  the database behind it. Providers are called only on a genuine miss.
- **`QuotaGuard` counts every call** and refuses to exceed the configured budget,
  so a loop cannot burn the month in seconds.
- **`/api/platforms` reports what is left**, and the interface shows it.
- **Tests never touch the network.** The build cannot spend your quota.

Adjust the budget if your plan differs:

```properties
providers.sources.amazon-in.monthly-quota=200
providers.sources.flipkart.monthly-quota=200
```

When a budget runs out the app does not break: it serves everything already
stored and says so plainly on the platforms endpoint rather than pretending the
source is still live.

---

