-- ---------------------------------------------------------------------------
-- AI-Driven Smart Price Comparison System - core schema
--
-- Written in portable SQL so the identical file applies on H2 (MySQL
-- compatibility mode, dev) and MySQL 8 (prod). That means: no ENGINE= clause,
-- no MySQL JSON type (JSON is stored as TEXT), no vendor-specific functions.
-- ---------------------------------------------------------------------------

CREATE TABLE users (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    name          VARCHAR(120)  NOT NULL,
    email         VARCHAR(190)  NOT NULL UNIQUE,
    password_hash VARCHAR(120)  NOT NULL,
    role          VARCHAR(20)   NOT NULL DEFAULT 'USER',
    created_at    TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- One row per shopping platform. `requires_key` drives the honest
-- live/disabled reporting on GET /api/platforms.
CREATE TABLE platforms (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    code         VARCHAR(40)  NOT NULL UNIQUE,
    display_name VARCHAR(80)  NOT NULL,
    logo_url     VARCHAR(500),
    base_url     VARCHAR(500),
    enabled      BOOLEAN      NOT NULL DEFAULT TRUE,
    requires_key BOOLEAN      NOT NULL DEFAULT FALSE,
    is_fallback  BOOLEAN      NOT NULL DEFAULT FALSE
);

CREATE TABLE categories (
    id   BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(120) NOT NULL,
    slug VARCHAR(120) NOT NULL UNIQUE
);

-- A canonical real-world item. Multiple platform listings collapse into one
-- row here via the TF-IDF matching engine; that link is what makes
-- side-by-side comparison possible at all.
CREATE TABLE products (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    canonical_title  VARCHAR(500) NOT NULL,
    normalized_title VARCHAR(500) NOT NULL,
    model_key        VARCHAR(200),
    brand            VARCHAR(120),
    category_id      BIGINT,
    description      TEXT,
    image_url        VARCHAR(1000),
    spec_json        TEXT,
    created_at       TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at       TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_products_category FOREIGN KEY (category_id) REFERENCES categories (id)
);

CREATE INDEX idx_products_brand      ON products (brand);
CREATE INDEX idx_products_category   ON products (category_id);
CREATE INDEX idx_products_model_key  ON products (model_key);

-- One platform's listing of a product. Prices are stored in INR: the Indian
-- marketplaces return INR natively, other sources are converted on ingest.
CREATE TABLE offers (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    product_id     BIGINT        NOT NULL,
    platform_id    BIGINT        NOT NULL,
    external_id    VARCHAR(200)  NOT NULL,
    title          VARCHAR(500)  NOT NULL,
    url            VARCHAR(1000),
    image_url      VARCHAR(1000),
    price_inr      DECIMAL(12,2) NOT NULL,
    original_price DECIMAL(12,2),
    discount_pct   DECIMAL(5,2),
    rating         DECIMAL(3,2),
    rating_count   INT           NOT NULL DEFAULT 0,
    in_stock       BOOLEAN       NOT NULL DEFAULT TRUE,
    delivery_days  INT,
    warranty       VARCHAR(200),
    return_policy  VARCHAR(200),
    seller         VARCHAR(200),
    fetched_at     TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_offers_product  FOREIGN KEY (product_id)  REFERENCES products (id)  ON DELETE CASCADE,
    CONSTRAINT fk_offers_platform FOREIGN KEY (platform_id) REFERENCES platforms (id),
    CONSTRAINT uq_offers_platform_external UNIQUE (platform_id, external_id)
);

CREATE INDEX idx_offers_product ON offers (product_id);
CREATE INDEX idx_offers_price   ON offers (price_inr);

CREATE TABLE reviews (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    product_id      BIGINT       NOT NULL,
    offer_id        BIGINT,
    author          VARCHAR(200),
    rating          DECIMAL(3,2),
    body            TEXT,
    review_date     TIMESTAMP,
    sentiment_score DECIMAL(5,4),
    sentiment_label VARCHAR(20),
    CONSTRAINT fk_reviews_product FOREIGN KEY (product_id) REFERENCES products (id) ON DELETE CASCADE,
    CONSTRAINT fk_reviews_offer   FOREIGN KEY (offer_id)   REFERENCES offers (id)   ON DELETE CASCADE
);

CREATE INDEX idx_reviews_product ON reviews (product_id);

-- source distinguishes a genuinely observed price from the demo backfill that
-- gives the forecaster a series to work with on day one. The UI shows the
-- difference; simulated points are never passed off as observed.
CREATE TABLE price_history (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    offer_id    BIGINT        NOT NULL,
    price       DECIMAL(12,2) NOT NULL,
    recorded_at TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    source      VARCHAR(20)   NOT NULL DEFAULT 'OBSERVED',
    CONSTRAINT fk_price_history_offer FOREIGN KEY (offer_id) REFERENCES offers (id) ON DELETE CASCADE
);

CREATE INDEX idx_price_history_offer_time ON price_history (offer_id, recorded_at);

CREATE TABLE favorites (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id    BIGINT    NOT NULL,
    product_id BIGINT    NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_favorites_user    FOREIGN KEY (user_id)    REFERENCES users (id)    ON DELETE CASCADE,
    CONSTRAINT fk_favorites_product FOREIGN KEY (product_id) REFERENCES products (id) ON DELETE CASCADE,
    CONSTRAINT uq_favorites_user_product UNIQUE (user_id, product_id)
);

CREATE TABLE comparison_history (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id          BIGINT    NOT NULL,
    product_ids_json TEXT      NOT NULL,
    winner_product_id BIGINT,
    created_at       TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_comparison_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
);

CREATE TABLE search_history (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id      BIGINT,
    query        VARCHAR(300) NOT NULL,
    filters_json TEXT,
    result_count INT          NOT NULL DEFAULT 0,
    created_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_search_history_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
);

CREATE INDEX idx_search_history_user ON search_history (user_id, created_at);

-- Backs QuotaGuard. Free RapidAPI tiers are small, so every outbound call is
-- accounted for and the guard hard-stops before the monthly budget is blown.
CREATE TABLE api_call_log (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    platform_code   VARCHAR(40) NOT NULL,
    endpoint        VARCHAR(200) NOT NULL,
    called_at       TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    status          VARCHAR(20) NOT NULL,
    quota_remaining INT
);

CREATE INDEX idx_api_call_log_platform_time ON api_call_log (platform_code, called_at);
