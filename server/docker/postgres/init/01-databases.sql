-- Three logical databases, one per service (database-per-service pattern).
-- Users are restricted to their own database; WAL level is already logical via compose.

CREATE DATABASE customer_db;
CREATE DATABASE ledger_db;
CREATE DATABASE transfer_db;
CREATE DATABASE fraud_db;
CREATE DATABASE notify_db;

\connect customer_db
CREATE USER customer WITH PASSWORD 'customer';
GRANT ALL PRIVILEGES ON DATABASE customer_db TO customer;
GRANT ALL ON SCHEMA public TO customer;

\connect ledger_db
CREATE USER ledger WITH PASSWORD 'ledger';
GRANT ALL PRIVILEGES ON DATABASE ledger_db TO ledger;
GRANT ALL ON SCHEMA public TO ledger;

\connect transfer_db
CREATE USER transfer WITH PASSWORD 'transfer';
GRANT ALL PRIVILEGES ON DATABASE transfer_db TO transfer;
GRANT ALL ON SCHEMA public TO transfer;

\connect fraud_db
CREATE USER fraud WITH PASSWORD 'fraud';
GRANT ALL PRIVILEGES ON DATABASE fraud_db TO fraud;
GRANT ALL ON SCHEMA public TO fraud;

\connect notify_db
CREATE USER notify WITH PASSWORD 'notify';
GRANT ALL PRIVILEGES ON DATABASE notify_db TO notify;
GRANT ALL ON SCHEMA public TO notify;

-- Debezium reads the transfer_db WAL via a replication slot
ALTER USER transfer WITH REPLICATION;
