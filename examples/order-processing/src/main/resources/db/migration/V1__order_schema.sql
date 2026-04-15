-- Order domain schema
CREATE TABLE orders (
    id          UUID           NOT NULL,
    customer_id VARCHAR(255)   NOT NULL,
    total       DECIMAL(12, 2) NOT NULL,
    status      VARCHAR(20)    NOT NULL,
    created_at  TIMESTAMP      NOT NULL,
    CONSTRAINT pk_orders PRIMARY KEY (id)
);

CREATE INDEX idx_orders_customer ON orders (customer_id);
CREATE INDEX idx_orders_status   ON orders (status);
