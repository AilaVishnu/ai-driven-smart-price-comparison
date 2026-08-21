# AI-Driven Smart Price Comparison System

A web application that searches one product and shows its price, specifications,
ratings, reviews, discounts and availability across multiple shopping platforms
in a single view — with an intelligent comparison that ranks options by what the
shopper actually cares about.

**Stack:** React 19 + Vite (JavaScript, plain CSS) · Java 17 + Spring Boot 4 ·
MySQL (H2 for development) · in-app ML/NLP, no external AI service.

---

## Quick start

Two terminals. Nothing to install beyond what is already on the machine — the
Maven wrapper fetches Maven itself, and H2 means no database setup.

```powershell
# Terminal 1 — backend on http://localhost:8080
cd backend
.\mvnw.cmd spring-boot:run

# Terminal 2 — frontend on http://localhost:5173
cd frontend
npm install
npm run dev
```

Then open <http://localhost:5173>.

On first start the backend seeds a catalogue from the keyless sources so there
is something to browse immediately. To verify everything at once:

```powershell
.\scripts\smoke.ps1
```

---

## Read this before judging the demo

The application runs with **no API key at all**, but what it can show you
depends on which sources are switched on. It is upfront about this rather than
papering over it — `GET /api/platforms`, the startup log, and the interface all
report the true state of every source.

| | Without a key (default) | With a free RapidAPI key |
|---|---|---|
| Catalogue | ~211 products from keyless sources | Real Amazon.in and Flipkart listings |
| Prices | Real, converted to INR | Real, natively INR |
| Reviews | Real review text | Real customer reviews |
| **Cross-platform comparison** | **Not demonstrable** | **Working** |

The last row is the important one. The keyless fallback catalogues do not
overlap with each other, so "the same product on two platforms" has nothing to
match. Every other feature — matching, sentiment, TOPSIS ranking, forecasting,
natural-language search — works either way.

**Adding a key takes about two minutes and costs nothing.** Get one by following
[docs/api-keys-setup.md](docs/api-keys-setup.md), then paste it into the line
that is already waiting for it in:

```
backend/src/main/resources/application-local.properties
```

```properties
providers.rapidapi-key=your-key-here
```

Restart the backend — no flag needed, the `dev` and `mysql` profiles both pull
that file in. It is gitignored, so the key never reaches version control, and
placeholder text is ignored rather than sent so a half-finished setup fails
loudly instead of confusingly.

Official Amazon and Flipkart APIs are closed to new developers; that document
explains exactly why RapidAPI is the route.

---

## What makes it "AI-driven"

Five components, all plain Java, all unit-tested, no external model or API key.
The point is that every one of them can be explained and defended, not just
invoked.

### 1. Cross-platform product matching — the component everything rests on

Amazon and Flipkart return disjoint catalogues with no shared identifier, so
"the same phone on both sites" is not something either API can tell us. It has
to be inferred.

```
similarity = 0.40 · cosine(TF-IDF over titles)
           + 0.35 · JaroWinkler(model signature)
           + 0.25 · brand agreement
```

Scoring alone is not enough, and the tests are what proved it. Cosine similarity
rates *iPhone 15 Pro* against *iPhone 15 Pro Max* at about 0.87 — above any
threshold that still matches genuine pairs. So variant differences are **vetoes**
rather than weights: qualifiers (`pro`, `max`, `ultra`) and the leading model
number must agree exactly, brands must not conflict, and prices must sit within a
band. An exact match on a distinctive model code such as `WH-1000XM5` is treated
as near-conclusive on its own, because platforms describe the same headphones as
"Wireless Headphones" and "Bluetooth Headset" — sharing almost no vocabulary.

`GET /api/admin/matching/preview?q=iphone` shows the clusters it formed.

### 2. Review sentiment, by aspect

Lexicon-based scoring over real review text, with negation ("not good" is
negative, and milder than "terrible"), intensifiers ("very poor" beats "poor"),
and length normalisation so padding a review does not inflate it. Terms are
attributed to the aspect mentioned nearest — battery, camera, delivery, price —
so a product page can say the battery is well liked while delivery is not.

The lexicon (`resources/nlp/product-sentiment-lexicon.txt`) was written for this
project rather than borrowed: in review language "cheap" usually means shoddy and
"returned" is a complaint, and general-purpose word lists score both wrongly.

### 3. Multi-criteria ranking — TOPSIS

*Technique for Order of Preference by Similarity to Ideal Solution*, a published
decision-support method rather than an invented formula. Each criterion column is
vector-normalised before weighting, so review counts running to five figures
cannot swamp a 5-point rating scale — which a naive weighted average does.

Criteria: price ↓, rating ↑, review count ↑, discount ↑, sentiment ↑, delivery ↓,
availability ↑.

**The weights are yours.** The comparison page exposes them as sliders, and the
ranking recomputes live. Every score ships with a per-criterion breakdown, so the
verdict is auditable instead of asserted.

### 4. Price forecasting and a buy signal

Ordinary least squares over the price history, written out rather than imported,
plus a moving average and volatility. Emits a 14- and 30-day projection and a
**BUY_NOW / WAIT / HOLD** signal.

Two honesty guards: below five observations no trend is claimed at all, and any
conclusion drawn from the fitted line is withdrawn when R² is poor. A *near the
period low* BUY_NOW survives that check because it compares observed values and
involves no model — the distinction is deliberate.

### 5. Natural-language search

`"gaming laptop under 60k from dell"` becomes a budget ceiling of ₹60,000, brand
Dell, category laptops, and a residual search term of "gaming". Indian money
words are handled directly (`60k`, `1.5 lakh`, `2 cr`), and misspelled brands are
corrected by edit distance. No LLM, so nothing to fail during a demo — and every
rule can be pointed at. The interface shows how the phrase was read, so the
interpretation is visible and correctable.

---

## Architecture

```
backend/          Spring Boot 4.1.1, Java 17
  ai/             matching, sentiment, TOPSIS, forecasting, query parsing
  provider/       one adapter per platform behind a single interface
  service/        search, ingestion, comparison, accounts
  security/       stateless JWT
  web/            REST controllers
frontend/         React 19 + Vite, plain JavaScript and CSS
docs/             API reference, key setup, AI methodology
scripts/          smoke.ps1
```

### The Product / Offer split

A **Product** is one canonical real-world item; an **Offer** is one platform's
listing of it. The matching engine populates that link, and it is what makes
side-by-side comparison possible at all. Without it there is no comparison, only
two unrelated lists.

### Designed around a metered quota

Free marketplace tiers allow a few hundred calls a month, so the whole read path
is built for that rather than in spite of it:

- **Persist on fetch** — a product costs quota once, then serves from the
  database forever.
- **Two caches** — a 10-minute in-memory cache in front of the database.
- **`QuotaGuard`** counts every outbound call and refuses to exceed the budget.
- **Tests never touch the network**, so a build cannot spend your quota.
- **Graceful degradation** — when a marketplace is unavailable or spent, stored
  data still serves and the keyless sources step back in, with the swap stated
  plainly rather than hidden.

### Database

H2 in development (file-based, so a catalogue survives restarts), MySQL in
production. The *same* Flyway migrations run on both — they are written in
portable SQL with no vendor-specific types.

```powershell
# Run against MySQL instead
# CREATE DATABASE spcdb CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
cd backend
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=mysql"
```

Connection details come from `MYSQL_HOST`, `MYSQL_PORT`, `MYSQL_DB`,
`MYSQL_USER`, `MYSQL_PASSWORD`.

---

## Testing

```powershell
cd backend
.\mvnw.cmd test
```

69 tests. The AI layer is where correctness actually matters, so that is where
the tests are concentrated — with fixtures that pin down the failures found
during development:

| Suite | What it holds down |
|---|---|
| `ProductMatchingServiceTest` | Real pairs merge; Pro vs Pro Max, S24 vs S23, and different capacities do not |
| `TextNormalizerTest` | Titles from different platforms reduce to the same tokens |
| `TfIdfVectorizerTest` | IDF matches values computed by hand |
| `SentimentAnalyzerTest` | Negation, intensifiers, aspect attribution |
| `TopsisScoringServiceTest` | A dominant option always wins; weights change the outcome |
| `PriceForecastServiceTest` | A known slope is recovered; a scatter plot yields no confident advice |
| `QueryIntentParserTest` | Indian money words, ranges, brand typos |

Tests use an isolated in-memory database and make no network calls, so they run
alongside a running application and never spend API quota.

---

## API

Full reference in [docs/api-reference.md](docs/api-reference.md).

```
GET    /api/products/search?q=&page=&size=&minPrice=&maxPrice=&brand=&category=
                           &minRating=&platform=&inStock=&discounted=&sort=
GET    /api/products/{id}                    detail, offers, sentiment, forecast, history
GET    /api/products/{id}/offers | /reviews | /price-history | /forecast | /similar
POST   /api/compare        {productIds, weights}   TOPSIS ranking with breakdown
GET    /api/platforms | /api/categories | /api/deals
POST   /api/auth/register | /api/auth/login    GET /api/auth/me
GET|POST|DELETE /api/favorites          GET /api/history/search  /api/compare/history
GET    /api/admin/providers/probe | /api/admin/matching/preview     (dev profile only)
```

Browsing, searching and comparing need no account. Favourites and history do.

---

## Known limitations

Stated plainly, because they matter when judging what is on screen:

1. **Cross-platform comparison needs a key.** Without one the fallback
   catalogues do not overlap, so products show a single platform. The engine is
   proven by tests and by merges on real data; the demo of the headline feature
   is what is gated.
2. **RapidAPI response shapes are unverified.** Their docs are
   JavaScript-rendered and could not be read without a key, so the adapters match
   several candidate field names rather than one guessed set. On the first live
   call, run `GET /api/admin/providers/probe` to see the real shape — correcting
   a mismatch is a one-line change in one method.
3. **Demo price history is simulated.** Forecasting needs a series that does not
   exist on day one, so 90 days are backfilled — stored with
   `source = SIMULATED`, declared by the forecast, and labelled in the interface.
   Observed prices are never fabricated.
4. **Sentiment is lexicon-based**, not a trained classifier. It handles negation
   and intensifiers but will miss sarcasm and unusual phrasing.
