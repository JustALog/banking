CREATE TABLE account (
    id          UUID PRIMARY KEY,
    customer_id UUID         NOT NULL,
    account_no  VARCHAR(20)  NOT NULL UNIQUE,
    currency    VARCHAR(3)   NOT NULL,
    balance     BIGINT       NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version     BIGINT       NOT NULL DEFAULT 0
);

CREATE INDEX idx_account_customer ON account (customer_id);

CREATE TABLE ledger_entry (
    id          UUID PRIMARY KEY,
    transfer_id UUID        NOT NULL,
    account_id  UUID        NOT NULL,
    direction   VARCHAR(6)  NOT NULL,
    amount      BIGINT      NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_entry_transfer_account_direction UNIQUE (transfer_id, account_id, direction),
    CONSTRAINT ck_entry_direction CHECK (direction IN ('DEBIT', 'CREDIT')),
    CONSTRAINT ck_entry_amount CHECK (amount > 0)
);

CREATE INDEX idx_entry_account ON ledger_entry (account_id, created_at DESC);
