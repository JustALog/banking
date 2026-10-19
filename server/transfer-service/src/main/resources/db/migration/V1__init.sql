CREATE TABLE transfer (
    id                UUID PRIMARY KEY,
    idempotency_key   VARCHAR(255) NOT NULL UNIQUE,
    customer_id       UUID         NOT NULL,
    source_account_id UUID         NOT NULL,
    dest_account_id   UUID         NOT NULL,
    amount            BIGINT       NOT NULL CHECK (amount > 0),
    currency          VARCHAR(3)   NOT NULL,
    status            VARCHAR(10)  NOT NULL DEFAULT 'PENDING',
    failure_reason    VARCHAR(50),
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version           BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT ck_transfer_status CHECK (status IN ('PENDING', 'COMPLETED', 'FAILED'))
);

CREATE INDEX idx_transfer_customer ON transfer (customer_id, created_at DESC);

-- Transactional outbox. Column names follow the Debezium Outbox Event Router
-- defaults so the connector needs no custom field mapping:
--   id -> event key, aggregatetype -> topic suffix, aggregateid -> record key,
--   payload -> record value.
CREATE TABLE outbox_event (
    id            UUID PRIMARY KEY,
    aggregatetype VARCHAR(100) NOT NULL,
    aggregateid   VARCHAR(200) NOT NULL,
    type          VARCHAR(100),
    payload       JSONB        NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_outbox_aggregatetype ON outbox_event (aggregatetype);
