-- ---------------------------------------------------------------------------
-- Reference data: the platforms we compare across, and the category taxonomy
-- the matching engine blocks on.
-- ---------------------------------------------------------------------------

-- Primary sources: real Indian marketplaces, reached through one free RapidAPI
-- key. Marked requires_key so /api/platforms can report honestly when a key is
-- absent rather than silently showing nothing.
INSERT INTO platforms (code, display_name, logo_url, base_url, enabled, requires_key, is_fallback) VALUES
  ('AMAZON_IN', 'Amazon.in', NULL, 'https://www.amazon.in',  TRUE, TRUE,  FALSE),
  ('FLIPKART',  'Flipkart',  NULL, 'https://www.flipkart.com', TRUE, TRUE, FALSE);

-- Fallback sources: no key required. Governed by providers.fallback.mode=auto,
-- so they activate only when no key is configured, quota is exhausted, or the
-- primaries are failing. They keep the app demonstrable, never silently
-- substituting for real marketplace data - the UI always shows which is live.
INSERT INTO platforms (code, display_name, logo_url, base_url, enabled, requires_key, is_fallback) VALUES
  ('DUMMYJSON', 'DummyJSON Store', NULL, 'https://dummyjson.com',    TRUE, FALSE, TRUE),
  ('FAKESTORE', 'FakeStore',       NULL, 'https://fakestoreapi.com', TRUE, FALSE, TRUE);

INSERT INTO categories (name, slug) VALUES
  ('Smartphones',      'smartphones'),
  ('Laptops',          'laptops'),
  ('Tablets',          'tablets'),
  ('Headphones',       'headphones'),
  ('Smartwatches',     'smartwatches'),
  ('Cameras',          'cameras'),
  ('Televisions',      'televisions'),
  ('Home Appliances',  'home-appliances'),
  ('Gaming',           'gaming'),
  ('Storage',          'storage'),
  ('Monitors',         'monitors'),
  ('Footwear',         'footwear'),
  ('Fashion',          'fashion'),
  ('Beauty',           'beauty'),
  ('Groceries',        'groceries'),
  ('Accessories',      'accessories'),
  ('Software',         'software'),
  ('Other',            'other');
