package vn.banking.events;

/**
 * Central Kafka topic contract. Keys are always accountId (source account of the transfer)
 * so all events of one account land on the same partition and stay ordered.
 */
public final class Topics {

    public static final String TRANSFER_REQUESTED = "transfer.requested";
    public static final String FRAUD_DECIDED = "fraud.decided";
    public static final String LEDGER_POSTED = "ledger.posted";
    public static final String TRANSFER_COMPLETED = "transfer.completed";
    public static final String TRANSFER_FAILED = "transfer.failed";
    /** Ledger could not post (insufficient funds, unknown account, ...); the saga turns it into transfer.failed. */
    public static final String LEDGER_REJECTED = "ledger.rejected";
    public static final String TRANSFER_DLQ = "transfer.dlq";

    private Topics() {
    }
}
