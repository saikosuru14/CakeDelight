-- V3__more_sample_cakes.sql
--
-- ADDITIVE SAMPLE DATA ONLY. No schema change: no new tables, columns, constraints or indexes.
-- V1 owns the schema and V2 owns the original ten-cake seed; neither is modified here, because
-- Flyway records a checksum per applied migration and editing an applied file breaks startup.
--
-- This file is maintained in three byte-identical copies, one per configured Flyway location:
--   src/main/resources/db/migration/     - CANONICAL, PostgreSQL 16, default profile
--   src/test/resources/db/migration-h2/  - TEST-ONLY mirror, `test` profile; never packaged
--   src/main/resources/db/h2/            - LOCAL-ONLY mirror, `local` profile
-- The copies can be byte-identical because the statement below is a plain multi-row INSERT with
-- string, numeric and boolean literals only: no PostgreSQL-specific syntax, no expression
-- indexes, no casts, no functions. H2 in PostgreSQL mode parses it unchanged, so unlike V1 (whose
-- functional indexes H2 cannot parse) there is no divergence to document.
--
-- 14 more cakes, ids continuing the fixed-UUID series from V2 (...00000000000b onward) so demo
-- data stays deterministic. Adds five categories - Brownie, Tart, Vegan, Gluten Free, Wedding -
-- to the existing Birthday, Cupcake, Pastry, Cheesecake. Prices span 2.50 to 149.99 so
-- price-range filtering is visibly meaningful, and three rows are unavailable so the 409
-- CAKE_UNAVAILABLE path has several candidates. Several names deliberately repeat substrings
-- found in V2 ("Chocolate", "Lemon", "Almond", "Pistachio", "Vanilla", "Caramel") so the
-- case-insensitive substring name filter returns multiple hits.
--
-- image_url points at picsum.photos with a stable kebab-case slug seed, which returns a real
-- deterministic image per cake. V2 points at cdn.cakedelight.example, which does not resolve, so
-- those cards always render the fallback tile. If picsum.photos is blocked on the network the UI
-- degrades to the same labelled fallback tile, so nothing breaks either way.
INSERT INTO cakes (id, name, description, category, price, available, image_url) VALUES
    ('11111111-1111-4111-8111-00000000000b',
     'Triple Chocolate Brownie',
     'Fudgy brownie loaded with dark, milk and white chocolate chunks.',
     'Brownie', 4.75, TRUE,
     'https://picsum.photos/seed/triple-chocolate-brownie/400/300'),

    ('11111111-1111-4111-8111-00000000000c',
     'Salted Caramel Brownie',
     'Dense chocolate brownie rippled with salted caramel sauce.',
     'Brownie', 5.25, TRUE,
     'https://picsum.photos/seed/salted-caramel-brownie/400/300'),

    ('11111111-1111-4111-8111-00000000000d',
     'Walnut Blondie Bar',
     'Buttery vanilla blondie bar studded with toasted walnuts.',
     'Brownie', 2.50, TRUE,
     'https://picsum.photos/seed/walnut-blondie-bar/400/300'),

    ('11111111-1111-4111-8111-00000000000e',
     'Lemon Meringue Tart',
     'Crisp pastry case filled with lemon curd under torched meringue.',
     'Tart', 7.80, TRUE,
     'https://picsum.photos/seed/lemon-meringue-tart/400/300'),

    ('11111111-1111-4111-8111-00000000000f',
     'Classic Bakewell Tart',
     'Shortcrust tart with raspberry jam, almond sponge and flaked almonds.',
     'Tart', 6.90, FALSE,
     'https://picsum.photos/seed/classic-bakewell-tart/400/300'),

    ('11111111-1111-4111-8111-000000000010',
     'Dark Chocolate Ganache Tart',
     'Silky dark chocolate ganache set in a cocoa pastry shell.',
     'Tart', 8.45, TRUE,
     'https://picsum.photos/seed/dark-chocolate-ganache-tart/400/300'),

    ('11111111-1111-4111-8111-000000000011',
     'Vegan Chocolate Fudge Cake',
     'Plant based chocolate sponge with a rich dairy free fudge icing.',
     'Vegan', 26.00, TRUE,
     'https://picsum.photos/seed/vegan-chocolate-fudge-cake/400/300'),

    ('11111111-1111-4111-8111-000000000012',
     'Vegan Lemon Polenta Cake',
     'Moist polenta and almond cake finished with a lemon syrup glaze.',
     'Vegan', 24.50, TRUE,
     'https://picsum.photos/seed/vegan-lemon-polenta-cake/400/300'),

    ('11111111-1111-4111-8111-000000000013',
     'Gluten Free Carrot Cake',
     'Spiced carrot and pecan cake with orange cream cheese frosting.',
     'Gluten Free', 28.40, TRUE,
     'https://picsum.photos/seed/gluten-free-carrot-cake/400/300'),

    ('11111111-1111-4111-8111-000000000014',
     'Gluten Free Almond Cheesecake',
     'Baked almond cheesecake on a gluten free amaretti crumb base.',
     'Gluten Free', 33.90, FALSE,
     'https://picsum.photos/seed/gluten-free-almond-cheesecake/400/300'),

    ('11111111-1111-4111-8111-000000000015',
     'Ivory Rose Wedding Cake',
     'Four tier ivory buttercream wedding cake trimmed with sugar roses.',
     'Wedding', 149.99, TRUE,
     'https://picsum.photos/seed/ivory-rose-wedding-cake/400/300'),

    ('11111111-1111-4111-8111-000000000016',
     'Naked Berry Wedding Cake',
     'Three tier naked sponge layered with cream and fresh summer berries.',
     'Wedding', 118.00, TRUE,
     'https://picsum.photos/seed/naked-berry-wedding-cake/400/300'),

    ('11111111-1111-4111-8111-000000000017',
     'Pistachio Rose Cheesecake',
     'Chilled pistachio cheesecake scented with rosewater and honey.',
     'Cheesecake', 36.75, TRUE,
     'https://picsum.photos/seed/pistachio-rose-cheesecake/400/300'),

    ('11111111-1111-4111-8111-000000000018',
     'Vanilla Confetti Birthday Cake',
     'Vanilla sponge packed with rainbow sprinkles under a pastel buttercream.',
     'Birthday', 42.00, FALSE,
     'https://picsum.photos/seed/vanilla-confetti-birthday-cake/400/300');
