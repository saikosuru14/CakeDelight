-- V1__create_cakes.sql
CREATE TABLE cakes (
    id           UUID PRIMARY KEY,
    name         VARCHAR(150)   NOT NULL,
    description  VARCHAR(1000),
    category     VARCHAR(50)    NOT NULL,
    price        NUMERIC(12,2)  NOT NULL CHECK (price >= 0),
    available    BOOLEAN        NOT NULL DEFAULT TRUE,
    image_url    VARCHAR(500)
);

CREATE INDEX idx_cakes_category_lower ON cakes (lower(category));
CREATE INDEX idx_cakes_name_lower     ON cakes (lower(name));
CREATE INDEX idx_cakes_price          ON cakes (price);
