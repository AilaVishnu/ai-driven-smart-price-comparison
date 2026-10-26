# AI-Driven Smart Price Comparison System — Engineering Handbook

Search one product; see what every stocking platform charges for it, ranked by what
you actually care about. Two Indian marketplaces, one canonical catalogue, five
decision components — none of which call out to a model.

| | |
|---|---|
| Products | 881 |
| Offers | 1,127 |
| Cross-platform matches | 6 |
| Tests | 80 (all passing) |
| Commits | 162 |
| Java classes | 75 |
| React pages / components | 10 / 15 |

---

## Contents

**System** — [What it solves](#1-what-it-solves) · [The request path](#2-the-request-path) · [Technology](#3-technology) · [Data model](#4-data-model) · [Where the data comes from](#5-where-the-data-comes-from)

**Intelligence** — [1 Product matching](#61-cross-platform-product-matching) · [2 Review sentiment](#62-review-sentiment-by-aspect) · [3 TOPSIS ranking](#63-multi-criteria-ranking--topsis) · [4 Price forecasting](#64-price-forecasting-and-a-buy-signal) · [5 Query parsing](#65-natural-language-query-parsing)

**Engineering** — [Living on a free tier](#7-living-on-a-free-tier) · [The API](#8-the-api) · [Frontend](#9-frontend) · [Testing](#10-testing)

**Record** — [How it was built](#11-how-it-was-built) · [Known limitations](#12-known-limitations) · [Running it](#13-running-it)

---

## 1. What it solves

Amazon and Flipkart both sell the Samsung Galaxy Buds4 Pro. Neither will tell you the
other charges **₹2,260 more**. Neither shares an identifier with the other, and neither
titles the product the same way. Every interesting part of this system falls out of that
one fact.

| Platform | Price | MRP | Discount | Rating |
|---|---:|---:|---:|---:|
| Amazon.in | **₹20,739** | ₹29,999 | 30.9% | 4.3 (411) |
| Flipkart | ₹22,999 | ₹29,999 | 23.3% | 4.6 (370) |

Real figures, pulled live. The two listings were recognised as the same product by the
matching engine — the platforms provide no key that says so.

---

## 2. The request path

The database is the primary source and the marketplaces are consulted only when it cannot
answer. That ordering is forced by the free API tiers: a few hundred calls a month does not
survive a provider round trip per keystroke.

```
search "galaxy buds"
   │
   ├─ QueryIntentParser        budget? brand? category? → structured filters
   │
   ├─ database (token search)  ── enough results? ──► serve
   │                                    │ no
   │                                    ▼
   ├─ ProviderRegistry         parallel fan-out, per-provider deadline
   │     ├─ Amazon.in          keyword search
   │     └─ Flipkart           category browse
   │                                    │
   ├─ IngestionService         normalise → MATCH → persist
   │     └─ ProductMatchingService   which listings are the same product?
   │                                    │
   ├─ TopsisScoringService     rank the whole result set
   │
   └─ response                 products + per-platform prices + value score
```

Everything fetched is written down. A product costs quota **once**; from then on it serves
from the database, and the same query never spends a call twice.

---

## 3. Technology

### Backend

| Layer | Choice | Version |
|---|---|---|
| Language | Java (Oracle JDK, LTS) | 17.0.12 |
| Framework | Spring Boot | 4.1.1 |
| Web | Spring Web MVC | 4.1.1 |
| Persistence | Spring Data JPA · Hibernate | 7.4 |
| Security | Spring Security — stateless JWT, BCrypt | 7.x |
| Tokens | jjwt (api / impl / **gson**) | 0.12.6 |
| Caching | Spring Cache · Caffeine | 3.2.4 |
| Migrations | Flyway | 12.4.0 |
| Database (dev) | H2, file-based, MySQL compatibility mode | 2.4.240 |
| Database (prod) | MySQL Connector/J | 9.7.0 |
| Build | Maven, via wrapper | 3.9.x |

> The jjwt **gson** serializer is deliberate. The default `jjwt-jackson` pulls Jackson 2,
> while Spring Boot 4 ships Jackson 3 — the two collide at runtime. Gson sidesteps the
> conflict entirely.

### Frontend

| Layer | Choice | Version |
|---|---|---|
| Library | React | 19.2.8 |
| Build | Vite | 8.2.2 |
| Routing | React Router | 7.18.2 |
| Language | Plain JavaScript | ES2022 |
| Styling | Plain CSS, custom properties | — |
| Charts | Hand-written inline SVG | — |

### What was deliberately not used

Every absence here is a decision, and each one is defensible in a viva.

| Not used | Why |
|---|---|
| TensorFlow · scikit-learn | Every algorithm is written out so it can be explained line by line. |
| Any LLM or AI API | No key, no network dependency, nothing to fail mid-demo. |
| Tailwind | The brief specifies plain CSS. |
| TypeScript | The brief specifies JavaScript. |
| Chart.js · Recharts | Hand-written SVG keeps the charts explainable. |
| springdoc / Swagger | Its Boot 4 line needs Java 21; a written API reference replaces it. |
| Apache Commons Math | Least squares is twenty lines. |
| Lucene | TF-IDF is written directly. |

---

## 4. Data model

One distinction carries the whole system. A **Product** is one canonical real-world item.
An **Offer** is one platform's listing of it. The matching engine populates the link between
them, and that link is what makes side-by-side comparison possible at all — without it there
is no comparison, only two unrelated lists.

```
Product  ──1:n──►  Offer  ──n:1──►  Platform
   │                 │
   │                 ├──►  PriceHistory   (OBSERVED | SIMULATED)
   │                 │
   └──1:n──►  Review        (sentiment scored at write time)
```

Eleven tables in total:

| Table | Holds |
|---|---|
| `users` | account, BCrypt hash, role |
| `platforms` | Amazon.in, Flipkart — code, enabled, requires_key |
| `categories` | 18 slugs incl. `speakers`, `smartbands` |
| `products` | canonical title, normalised title, brand, category, specs |
| `offers` | per-platform listing — price, MRP, discount, rating, stock; `UNIQUE(platform_id, external_id)` |
| `reviews` | body, rating, sentiment score + label |
| `price_history` | price, recorded_at, `source ∈ OBSERVED \| SIMULATED` |
| `favorites` | `UNIQUE(user_id, product_id)` |
| `comparison_history` | product id set per comparison |
| `search_history` | query, filters, result count |
| `api_call_log` | every outbound call, status, quota remaining |

Migrations are written in portable SQL — no vendor-specific types, no JSON columns — so the
identical files apply to H2 in development and MySQL in production. Hibernate runs in
`validate` mode, making the migrations the single source of truth for the schema.

| Migration | Purpose |
|---|---|
| `V1__schema.sql` | the eleven tables |
| `V2__reference_data.sql` | platforms + 18 categories |
| `V3__remove_keyless_sources.sql` | deletes the retired demo sources, in dependency order |
| `V4__additional_categories.sql` | adds `speakers`, `smartbands` |

**Why prices are stored in INR only.** Both marketplaces quote INR natively. A conversion
service exists for any future source that does not, because a price silently displayed in the
wrong currency is worse than a little unused code.

---

## 5. Where the data comes from

The official routes are closed. This was researched before a line of adapter code was written:

| Source | Status |
|---|---|
| Flipkart Affiliate API | Public signups discontinued — documentation online, registration closed. |
| Amazon India PA-API 5.0 | Needs 3–10 qualifying **sales** before access, and was deprecated 15 May 2026. |
| Myntra · Ajio · Croma · Reliance | No public API of any kind. |
| **RapidAPI marketplace adapters** | ✅ Used. Free tier, free key, no card. |

### Two platforms, two different techniques

Each source sits behind a single `ProductProvider` interface, so adding or swapping a
marketplace touches one class and nothing else.

**Amazon.in** — keyword search via `/search`. Returns real INR prices, ratings and rating
counts, but **no brand and no category field**, which the title classifier has to supply.

**Flipkart** — **category browse**, not search. Its `/product-search` endpoint is paid-only;
`/products-by-category` is included free and returns richer objects — MRP alongside price, so
real discounts are computable.

| Category | Flipkart ID |
|---|---|
| smartphones | `tyy/4io` |
| tablets | `tyy/hry` |
| laptops | `6bo/b5g` |
| headphones | `0pm/fcn` |
| televisions | `ckf/czl` |
| smartwatches | `ajy/buh` |
| accessories | `tyy/4mr` |
| speakers | `0pm/0o7` |
| smartbands | `ajy/q7p` |

That asymmetry fits the architecture rather than fighting it. Because everything is persisted
and search is database-first, Flipkart populates the catalogue by category at startup and its
products are then matched against live Amazon results. The user still gets cross-platform
comparison; only the route the data takes differs.

> **Field names are matched loosely, on purpose.** RapidAPI documents its responses on
> JavaScript-rendered pages that cannot be read without a key, so each adapter tries several
> candidate names per field. A naming surprise costs one null field instead of throwing, and
> `GET /api/admin/providers/probe` prints the real response so the mapping can be corrected
> against reality.

### Seeding is resumable

Catalogue seeding computes how many products a category already has and starts from the next
page, so a restart never refetches what it already paid for:

```java
int target    = pages * PRODUCTS_PER_PAGE;          // PRODUCTS_PER_PAGE = 24
long existing = offerRepository.countByPlatformCodeAndCategorySlug("FLIPKART", slug);
int firstPage = (int) (existing / PRODUCTS_PER_PAGE) + 1;   // resume, don't repeat

if (flipkart.remainingQuota() <= reserve) return;   // leave budget for real searches
```

This grew the catalogue from 287 to 881 products on roughly **23 Flipkart calls**.

---

## 6. The AI layer

Five components. All hand-written Java, all unit-tested, no external model.

### 6.1 Cross-platform product matching

**The component everything else rests on.**

Amazon and Flipkart return disjoint catalogues with no shared identifier. "The same phone on
both sites" is not something either API can tell us — it has to be inferred from the text.

```
similarity = 0.40 · cosine(TF-IDF over normalised titles)
           + 0.35 · JaroWinkler(model signature)
           + 0.25 · brand agreement

accept if  similarity ≥ 0.72   OR   model codes match exactly
```

**Pipeline:**

1. `TextNormalizer` — lowercase, decode HTML entities, strip punctuation, standardise units
   (`16gb` → `16 gb`, `1tb` → `1024 gb`), resolve brand aliases, drop stopwords and Indian
   listing noise (`(Renewed)`, `with Offers`, seller suffixes).
2. Extract brand (lexicon + first-token heuristic) and a **model signature** — generation
   numbers, qualifiers, capacities, distinctive codes.
3. `TfIdfVectorizer` over normalised `title + brand + category`, smoothed IDF, L2-normalised.
4. **Blocking** — compare only within the same category and a 0.35×–3× price band, so the
   cost is O(n·k), not O(n²).
5. Score, veto, cluster.

#### Why scoring alone is not enough

Cosine similarity rates *iPhone 15 Pro* against *iPhone 15 Pro Max* at roughly **0.87** —
above any threshold that still matches genuine pairs. No weighting fixes that, because the one
differing token carries the entire meaning. So variant differences are **vetoes**, not weights:

| Gate | Rule | Catches |
|---|---|---|
| Qualifier | Must match exactly | Pro vs Pro Max |
| Generation | Leading model number must match | S24 vs S23 |
| Capacity | Blocks only when both are stated and differ | 128 GB vs 512 GB |
| Brand | Two known, different brands never merge | Dell vs HP |
| Price band | 0.35× – 3× of each other | A phone vs its case |

```java
static boolean hasVariantConflict(ModelSignature a, ModelSignature b) {
    if (!a.qualifiers().equals(b.qualifiers())) return true;          // Pro vs Pro Max

    String genA = a.primaryGeneration(), genB = b.primaryGeneration();
    if (genA != null && genB != null && !genA.equals(genB)) return true;   // S24 vs S23

    return !a.capacities().isEmpty() && !b.capacities().isEmpty()
            && !a.capacities().equals(b.capacities());
}
```

Only the **leading** model number is compared. Comparing the whole set would also pick up
chipset and RAM figures, rejecting genuine matches whenever one platform mentioned a spec the
other omitted — "Snapdragon 8 Gen 3" would make a phone fail to match itself.

Capacity blocks only when **both** sides state one, because Flipkart routinely omits it. An
absent value is unknown, not different.

#### The escape hatch

An exact match on a distinctive model code — `WH-1000XM5`, length ≥ 4 and containing a digit —
is treated as near-conclusive on its own, bypassing the score threshold. It has to be: Amazon
writes "Wireless Headphones" where Flipkart writes "Bluetooth Headset", sharing almost no
vocabulary, which drags cosine down to **0.43** for what is plainly the same product.

#### Category inference

Amazon returns no category field, so the category is read from the title — and the **order of
the checks is the algorithm**. Each ordering rule below came from a real bug:

1. **Accessories first** — charger, cable, case, cleaner, gel, fill light, tripod, sleeve,
   cooling pad. (`mobile-accessories` contains "mobile"; checking phones first filed chargers
   as smartphones.)
2. Headphones
3. **Speakers before laptops** — "Laptop/Desktop Speaker" is a speaker. (₹420 speakers were
   ranking above a real laptop in a laptop search.)
4. Laptops — including `windows 11`, `celeron`, `ryzen`, `core i5`, `thin and light`
5. Tablets, smartwatches, smartbands, televisions, monitors
6. **Storage requires a product noun** — `portable ssd`, `external hard`, never a bare `ssd`.
   (An Acer laptop was filed as storage because its title mentioned its SSD.)
7. **Smartphones last** — brand names appear in accessory titles.

The rule that emerged: **the noun a product *is* beats the noun it merely mentions.**

Inspect any clustering decision at `GET /api/admin/matching/preview`.

---

### 6.2 Review sentiment, by aspect

Lexicon scoring over real Amazon customer reviews. Three things make it more than a word count:

- **Negation** — a negator flips the next three tokens and damps the magnitude, so "not good"
  is mildly negative rather than as negative as "terrible".
- **Intensifiers** — "very poor" ×1.5, "slightly loose" ×0.5.
- **Aspect attribution** — each sentiment term is assigned to whichever aspect was mentioned
  nearest, within a five-token window.

```
Samsung Galaxy Buds4 Pro — 8 real reviews
POSITIVE   7 positive · 1 negative

  sound      +0.29   (8 mentions)
  price      +0.55   (2 mentions)
  battery    +0.50   (2 mentions)
  service    −0.40   (1 mention)     ← the useful signal
```

The **186-term lexicon** was authored for this project rather than borrowed. In review language
*cheap* usually means shoddy and *returned* is a complaint — general-purpose word lists score
both wrongly.

Reviews are scored once at write time, so a product page stays cheap to render and the stored
score is stable. Amazon reviews are fetched lazily, when a product is actually opened.

---

### 6.3 Multi-criteria ranking — TOPSIS

*Technique for Order of Preference by Similarity to Ideal Solution*: a published
decision-support method, not an invented formula.

```
1  decision matrix        alternatives × criteria
2  vector normalise       r = x / √Σx²          per column
3  apply weights          v = w · r
4  ideal best / worst     max or min per column, by benefit vs cost
5  separations            S⁺, S⁻ = Euclidean distance to each ideal
6  closeness              C = S⁻ / (S⁺ + S⁻)    reported 0–100
```

Step 2 is the one that matters. Review counts run to five figures while ratings top out at 5 —
a naive weighted average would let the count swamp everything else. Vector normalisation puts
every criterion on a common scale first.

| Criterion | Direction | Default weight |
|---|---|---:|
| Price | lower is better | 0.30 |
| Customer rating | higher is better | 0.20 |
| Discount | higher is better | 0.15 |
| Number of ratings | higher is better | 0.10 |
| Review sentiment | higher is better | 0.10 |
| Delivery speed | lower is better | 0.10 |
| In stock | higher is better | 0.05 |

**The weights belong to the user.** The comparison page exposes them as sliders and the ranking
recomputes live — the same four products legitimately produce different winners for different
shoppers. Every score ships with a per-criterion breakdown, so the verdict is auditable rather
than asserted. A decision support system that hides its assumptions is just an opinion with a
number attached.

---

### 6.4 Price forecasting and a buy signal

Ordinary least squares over the price history, plus a 7-day moving average and volatility.
Emits a 14- and 30-day projection and a **BUY_NOW / WAIT / HOLD** signal.

Two guards keep it honest:

- Below **five observations** no trend is claimed at all.
- Any conclusion drawn from the fitted line is **withdrawn when R² is poor**.

That second guard makes a distinction worth stating. A "near the period low" BUY_NOW *survives*
a weak fit, because it compares observed values and involves no model. A trend-derived signal
does not — **including a BUY_NOW inferred from a rising line**, which is exactly as dependent on
the fit as a WAIT is. Getting that wrong is a bug the tests caught: the original code exempted
BUY_NOW wholesale, so a scatter plot could still produce confident financial advice.

Price history rows are tagged `OBSERVED` or `SIMULATED` and the forecast declares which it used.

---

### 6.5 Natural-language query parsing

```
"gaming laptop under 60k from dell"
   ↓
   budget ≤ ₹60,000 · brand Dell · category laptops · terms "gaming"
```

Regex and lexicon, no model. Indian money vocabulary is handled directly because that is how
the users write — `60k`, `1.5 lakh`, `2 cr` all parse. Misspelled brands are corrected by
Levenshtein distance, with fuzzy matching restricted to words of four characters or more so
ordinary English does not become a brand.

The interface shows how the phrase was read, as removable chips. Showing the interpretation is
what makes the feature trustworthy rather than magic — and correctable when it guesses wrong.

**Search itself is token-based, not phrase-based.** Matching the literal phrase meant "smart
phone" returned nothing while "smartphone" worked. Now each token of length ≥ 3 is queried
separately, products are ranked by how many tokens they hit, and only the best-hit tier is kept.
If no token matches anything, it falls back to the category and brand filters alone.

---

## 7. Living on a free tier

Amazon allows **100 calls a month**, Flipkart **300**. The whole read path is built for that
rather than in spite of it.

| Technique | Effect |
|---|---|
| **Persist on fetch** | A product costs quota once, then serves from the database forever. |
| **Two caches** | A ten-minute Caffeine cache in front of the database, so refreshing and paging spend nothing. |
| **QuotaGuard** | Counts every outbound call and hard-stops before the budget is exceeded. Failed calls are not counted — a 4xx is not billed. |
| **Reserve** | Catalogue seeding stops with calls still in hand, so interactive searches always have budget. |
| **Lazy reviews** | Fetched when a product is actually opened, not for every search hit — and only once. |
| **Offline tests** | The suite makes no network calls, so a build can never spend the month's quota. |

### Two different deadlines

Conflating these was a real bug — every seeding call timed out.

| Deadline | Value | Why |
|---|---|---|
| HTTP socket timeout | **30 s** | Flipkart averages ~5 s and returns ~50 KB; seeding needs room. |
| Per-search deadline | **8 s** | After which a slow provider is dropped from *that response* only. |

Seeding can afford to wait; somebody typing in the search box cannot.

### Health notes expire

A provider that fails is labelled — but a *transient* failure label lapses after ten minutes,
so a source that has recovered stops looking broken. A *configuration* failure never lapses,
because it will not fix itself:

```java
private static final long HEALTH_NOTE_TTL_MS = 10 * 60 * 1000L;

public String healthNote() {
    String note = healthNote;
    if (note == null) return null;

    boolean configurationProblem = note.contains("not subscribed") || note.contains("invalid")
            || note.contains("plan covers") || note.contains("host and search-path");
    if (configurationProblem) return note;              // never lapses

    return System.currentTimeMillis() - healthNoteAt > HEALTH_NOTE_TTL_MS ? null : note;
}
```

`classifyFailure` turns each HTTP status into something actionable rather than a stack trace:
403 not-subscribed, 401 paywalled endpoint, 429 rate-limited, 404 wrong path, timeout.

---

## 8. The API

Browsing, searching and comparing need no account. Only favourites and history do — requiring
a login to look at prices would be hostile.

```
GET   /api/products/search?q=&page=&size=&minPrice=&maxPrice=&brand=
                          &category=&minRating=&platform=&sort=
GET   /api/products/{id}              detail · offers · sentiment · forecast
GET   /api/products/{id}/offers | /reviews | /price-history | /forecast | /similar
POST  /api/compare                    {productIds, weights} → TOPSIS ranking
GET   /api/cross-platform             products carried by more than one platform
GET   /api/platforms | /api/categories | /api/deals
POST  /api/auth/register | /api/auth/login        GET /api/auth/me
GET   /api/favorites | /api/history/search        (authenticated)
GET   /api/admin/providers/probe | /api/admin/matching/preview   (dev only)
```

Request DTOs are bean-validated; `GlobalExceptionHandler` returns RFC-7807-style problem
responses.

**Security** — stateless JWT (jjwt 0.12.6), BCrypt at strength 10, Spring Security 7 lambda
DSL, CORS locked to the Vite dev origin. CSRF is disabled because no cookies are used: the
token travels in an `Authorization` header the browser never attaches automatically.

> ⚠️ **Public read paths are allowlisted one by one.** A new endpoint not added to that list
> falls through to `anyRequest().authenticated()` and answers **403** — which, from the
> frontend, looks exactly like a query returning nothing. `/api/cross-platform` was missing
> from the list and cost a wasted debugging cycle chasing a query bug that did not exist. It
> is now commented in `SecurityConfig`.

---

## 9. Frontend

Ten pages, fifteen components, no state library — data fetching is a small `useApi` hook over
`fetch` that aborts superseded requests, so a slow search can never overwrite a newer one.

**Pages** — Home/Search · Results · Product Detail · Compare · Favorites · History · Deals ·
Login · Register · Profile

**Components worth naming** — `SearchBar` (parsed-intent chips) · `FilterSidebar` ·
`ProductCard` · `PriceComparison` · `CompareTray` · `CompareMatrix` · `WeightSliders` ·
`ValueScoreBreakdown` · `PriceHistoryChart` · `SentimentPanel` · `BuySignalBadge` ·
`PlatformStatus`

### Design decisions worth defending

- **Violet is the brand hue by elimination, not taste.** Amazon orange, Flipkart blue, and
  green and red for cheaper and dearer all carry fixed meaning. Violet is the one strong hue
  left that can never be mistaken for data.

- **Charts use blue↔red, not green↔red.** Sentiment is a diverging scale, and green/red is
  exactly the pairing red-green colour blindness collapses. The palette was checked with a
  contrast and CVD validator against both light and dark surfaces — worst-pair ΔE 21.6 and
  19.2 against a target of 8.

- **The product card leads with the comparison.** It previously said only that two platforms
  carried the product and what the saving was — never which platform charged what. That is the
  one thing the application exists to answer, so it should not need a click.

- **Status is stated in both directions.** An earlier version labelled only the failure case —
  a working source showed its remaining quota and nothing else — so a healthy row and a row
  that had failed to render looked identical:

  ```jsx
  {/* Stated either way, never inferred from what is missing. */}
  <span className={platform.live ? 'status-pill status-live' : 'status-pill status-off'}>
    {platform.live ? 'live' : 'off'}
  </span>
  ```

  When a marketplace is down, the reason is printed beneath it rather than left as a bare label.

---

## 10. Testing

**80 tests**, concentrated where correctness actually matters — the decision layer. They run
against an isolated in-memory database and make no network calls, so the suite is fast and
cannot spend quota.

| Suite | What it pins down |
|---|---|
| `ProductMatchingServiceTest` | Real pairs merge; Pro vs Pro Max, S24 vs S23 and differing capacities do not |
| `TextNormalizerTest` | Titles from different platforms reduce to the same tokens |
| `TfIdfVectorizerTest` | IDF matches values computed by hand |
| `SentimentAnalyzerTest` | Negation, intensifiers, aspect attribution |
| `TopsisScoringServiceTest` | A dominant option always wins; weights change the outcome |
| `PriceForecastServiceTest` | A known slope is recovered; a scatter plot yields no confident advice |
| `QueryIntentParserTest` | Indian money words, ranges, brand typos |
| `IngestionServiceTest` | Ingest visibility and idempotent re-ingest |

### Bugs the tests caught before a user could

These are the argument for writing them. Each was found by a test written for something else.

| Defect | Consequence had it shipped |
|---|---|
| iPhone 15 Pro merged with 15 Pro Max | Two different phones shown as one product with a fictional price spread |
| Confident buy signal from a scatter plot | Financial advice drawn through noise, presented as certainty |
| First search for a new term returned nothing | Every new query appeared to fail until run a second time |
| **Laptops classified as storage** | A large share of laptops invisible to category filters — and nothing on screen would say so |
| Speakers classified as laptops | ₹420 desktop speakers ranked above a real laptop in a laptop search |

The laptops-as-storage defect is the one to point at. It was found by the regression test
written for the *speaker* bug, it affected a large fraction of a category, and it was completely
invisible from the interface.

### The invisible-first-search bug

Worth recording because the cause was not where it looked. An `Offer` was being saved and the
transaction committed — but the in-memory `Product` it belonged to was never told:

```java
boolean alreadyLinked = product.getOffers().stream()
        .anyMatch(existing -> existing.getId() != null && existing.getId().equals(saved.getId()));

if (!alreadyLinked) product.getOffers().add(saved);   // JPA does not do this for you
```

The row was in the database, so a second search found it. Only the first one failed.

### Scripts

| Script | Does |
|---|---|
| `scripts/smoke.ps1` | 19 end-to-end checks with a pass/fail table |
| `scripts/test-provider.ps1` | Classifies each RapidAPI host — NOT SUBSCRIBED / PAYWALLED / WRONG PATH / NO SUCH HOST |

---

## 11. How it was built

The order matters here, because several later decisions were forced by what earlier steps
discovered.

**1 — Research before architecture.**
Candidate APIs were probed live before any adapter was written. That is what established that
Flipkart's affiliate programme is closed and Amazon's PA-API is both gated behind sales and
deprecated — findings that determined the entire provider design rather than being discovered
halfway through it.

**2 — Schema first, validated by the ORM.**
Flyway migrations define the schema; Hibernate runs in `validate` mode and refuses to start if
an entity disagrees. Every entity mismatch surfaced at startup rather than at first query.

**3 — The decision layer, test-first in effect.**
Matching, sentiment, TOPSIS and forecasting were each built with tests alongside. Two
significant defects were caught here, before either had reached a screen.

**4 — Adapters written defensively.**
Because the API documentation could not be read without a key, each adapter matches several
candidate field names and a probe endpoint prints the real response. The first live call then
corrected the mapping against reality instead of guesswork.

**5 — The Flipkart discovery.**
The subscribed API returned `401 endpoint is disabled for your subscription` on search —
paywalled — while category browse was included. Rather than pay or give up, the adapter was
rebuilt around categories, which the database-first architecture absorbed without changes
elsewhere. The correct sub-category path turned out to be `/sub-categories`, not the
`/get-subcategories` the publisher's own repository documented.

**6 — Running it exposed what tests could not.**
Two unrelated t-shirts merged into one product; the first search for any new term returned
nothing; classification mistakes put speakers above laptops. Each became a regression test, and
one of those tests immediately found a further bug.

**7 — Removing what stopped earning its place.**
Two keyless sources had been included so the app could demo before a key existed. Once both
marketplaces worked they were **42% of the catalogue** (191 + 20 products), contributed **zero**
cross-platform matches, and filled results with products no Indian price comparison would show.
They were deleted, along with the fallback machinery that supported them.

### Principles that emerged

- **Report state honestly.** Quota remaining, which source is live and why one is not, and
  whether a price point was observed or simulated — all surfaced rather than smoothed over.
- **Never fabricate data.** Backfilled price history is stored as `SIMULATED`, declared by the
  forecast and labelled in the interface. Observed prices are never invented.
- **Degrade, do not fail.** A provider that is slow, broken or out of quota drops out of that
  response. The database still answers.
- **The noun it *is* beats the noun it mentions.** Learned three times over from classification
  bugs, and now the ordering rule of the whole classifier.

---

## 12. Known limitations

Stated plainly, because they matter when judging what is on screen.

1. **Cross-platform matches appear only where the catalogues overlap.** Flipkart is browsed by
   category while Amazon is searched by keyword, so a match needs a product both happen to
   carry. Six exist today. Searching something visible in the Flipkart catalogue reliably
   produces one.

2. **Amazon's search relevance is poor.** The exact query "Apple iPhone 16 128GB" returns the
   16e, iPhone Air and a OnePlus. The matcher refusing to merge a 16e with a 16 is correct
   behaviour, not a failure.

3. **Flipkart's upstream is intermittent.** Roughly one call in four returns a 500 originating
   at Flipkart, surfaced through RapidAPI. Seeding resumes from where it stopped on the next
   start, and the health label expires after ten minutes.

4. **Demo price history is simulated.** Forecasting needs a series that does not exist on day
   one, so ninety days are backfilled — stored as `SIMULATED`, declared by the forecast, and
   labelled in the interface.

5. **Sentiment is lexicon-based, not a trained classifier.** It handles negation and
   intensifiers; it will miss sarcasm and unusual phrasing.

---

## 13. Running it

```bash
# backend — http://localhost:8080
cd backend
.\mvnw.cmd spring-boot:run

# frontend — http://localhost:5173
cd frontend
npm install
npm run dev

# verify
.\scripts\smoke.ps1           # 19 end-to-end checks
.\scripts\test-provider.ps1   # classify each RapidAPI host state
cd backend && .\mvnw.cmd test # 80 unit tests
```

Nothing else needs installing: the Maven wrapper fetches Maven, and H2 means no database setup.

**The one manual step** is a free RapidAPI key, pasted into
`backend/src/main/resources/application-local.properties`:

```properties
providers.rapidapi-key=YOUR_KEY_HERE
```

That file is **gitignored** and is loaded automatically — no flag needed — because both the
`dev` and `mysql` profiles include the `local` profile:

```properties
spring.profiles.active=dev
spring.profiles.group.dev=local
spring.profiles.group.mysql=local
```

> ⚠️ The repository is **public**. The key must never be moved into `application.properties` or
> any other tracked file.

**For MySQL instead of H2**, the same migrations apply:

```bash
.\mvnw.cmd spring-boot:run -Dspring-boot.run.profiles=mysql
```

---

*881 products · 1,127 offers · 80 tests · 162 commits · Java 17 · Spring Boot 4.1.1 · React 19*
