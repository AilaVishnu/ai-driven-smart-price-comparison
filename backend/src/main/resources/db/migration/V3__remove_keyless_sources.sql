-- ---------------------------------------------------------------------------
-- Remove the keyless fallback sources (DummyJSON, FakeStore).
--
-- They existed so the application could run and demo before a RapidAPI key was
-- available. With Amazon.in and Flipkart both live they earn nothing: their
-- catalogues are synthetic, they cannot match against the real marketplaces
-- because nothing overlaps, and they made up a large share of the catalogue
-- with products no Indian price comparison would ever show.
--
-- Deleted in dependency order rather than relying on cascades, because the
-- offer to platform foreign key is a plain reference.
-- ---------------------------------------------------------------------------

DELETE FROM price_history
WHERE offer_id IN (
    SELECT o.id FROM offers o
    JOIN platforms p ON p.id = o.platform_id
    WHERE p.code IN ('DUMMYJSON', 'FAKESTORE')
);

DELETE FROM reviews
WHERE offer_id IN (
    SELECT o.id FROM offers o
    JOIN platforms p ON p.id = o.platform_id
    WHERE p.code IN ('DUMMYJSON', 'FAKESTORE')
);

DELETE FROM offers
WHERE platform_id IN (SELECT id FROM platforms WHERE code IN ('DUMMYJSON', 'FAKESTORE'));

-- Products whose only listings came from those sources are now orphaned.
DELETE FROM reviews
WHERE product_id NOT IN (SELECT DISTINCT product_id FROM offers);

DELETE FROM favorites
WHERE product_id NOT IN (SELECT DISTINCT product_id FROM offers);

DELETE FROM products
WHERE id NOT IN (SELECT DISTINCT product_id FROM offers);

DELETE FROM platforms WHERE code IN ('DUMMYJSON', 'FAKESTORE');
