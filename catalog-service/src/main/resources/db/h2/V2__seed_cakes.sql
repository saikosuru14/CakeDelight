-- V2__seed_cakes.sql  (H2 translation - `local` profile only)
-- Values are identical to db/migration/V2__seed_cakes.sql: same UUIDs, names, descriptions,
-- categories, prices and availability flags, so tests and manual walkthroughs behave the same on
-- either database. H2 accepts this plain multi-row INSERT with UUID string literals as-is, so
-- there is no divergence in this file.
-- Demo catalog: four categories, prices from 3.25 to 89.90, one unavailable cake.
INSERT INTO cakes (id, name, description, category, price, available, image_url) VALUES
    ('11111111-1111-4111-8111-000000000001',
     'Chocolate Truffle',
     'Dark chocolate sponge layered with truffle ganache and cocoa nibs.',
     'Birthday', 23.75, TRUE,
     'https://cdn.cakedelight.example/cakes/chocolate-truffle.jpg'),

    ('11111111-1111-4111-8111-000000000002',
     'Red Velvet Dream',
     'Classic red velvet with cream cheese frosting and a white chocolate collar.',
     'Birthday', 27.50, TRUE,
     'https://cdn.cakedelight.example/cakes/red-velvet-dream.jpg'),

    ('11111111-1111-4111-8111-000000000003',
     'Three Tier Celebration',
     'Three tier vanilla and raspberry celebration cake, serves twenty.',
     'Birthday', 89.90, TRUE,
     'https://cdn.cakedelight.example/cakes/three-tier-celebration.jpg'),

    ('11111111-1111-4111-8111-000000000004',
     'Vanilla Bean Cupcake',
     'Madagascar vanilla bean cupcake with swiss meringue buttercream.',
     'Cupcake', 3.25, TRUE,
     'https://cdn.cakedelight.example/cakes/vanilla-bean-cupcake.jpg'),

    ('11111111-1111-4111-8111-000000000005',
     'Salted Caramel Cupcake',
     'Brown butter cupcake filled with salted caramel and topped with sea salt.',
     'Cupcake', 4.10, TRUE,
     'https://cdn.cakedelight.example/cakes/salted-caramel-cupcake.jpg'),

    ('11111111-1111-4111-8111-000000000006',
     'Lemon Drizzle Cupcake',
     'Lemon sponge cupcake soaked in citrus drizzle with candied zest.',
     'Cupcake', 3.60, FALSE,
     'https://cdn.cakedelight.example/cakes/lemon-drizzle-cupcake.jpg'),

    ('11111111-1111-4111-8111-000000000007',
     'Almond Croissant Pastry',
     'Twice baked croissant filled with almond frangipane and toasted flakes.',
     'Pastry', 5.95, TRUE,
     'https://cdn.cakedelight.example/cakes/almond-croissant-pastry.jpg'),

    ('11111111-1111-4111-8111-000000000008',
     'Pistachio Danish',
     'Flaky danish with pistachio cream and a rosewater glaze.',
     'Pastry', 6.40, TRUE,
     'https://cdn.cakedelight.example/cakes/pistachio-danish.jpg'),

    ('11111111-1111-4111-8111-000000000009',
     'New York Cheesecake',
     'Baked vanilla cheesecake on a digestive biscuit base.',
     'Cheesecake', 31.00, TRUE,
     'https://cdn.cakedelight.example/cakes/new-york-cheesecake.jpg'),

    ('11111111-1111-4111-8111-00000000000a',
     'Basque Burnt Cheesecake',
     'Caramelised crustless cheesecake with a molten centre.',
     'Cheesecake', 34.25, TRUE,
     'https://cdn.cakedelight.example/cakes/basque-burnt-cheesecake.jpg');
