ALTER TABLE transfer DROP CONSTRAINT IF EXISTS transfer_idempotency_key_key;
ALTER TABLE transfer ADD CONSTRAINT uq_transfer_customer_key UNIQUE (customer_id, idempotency_key);
