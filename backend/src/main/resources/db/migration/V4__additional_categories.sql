-- Categories for the Flipkart sections added to catalogue seeding. Without a
-- matching row the ingest would file these products under "other", which hides
-- them from category filters and disables the matching engine category gate.
INSERT INTO categories (name, slug) VALUES
  ('Speakers',    'speakers'),
  ('Smart Bands', 'smartbands');
