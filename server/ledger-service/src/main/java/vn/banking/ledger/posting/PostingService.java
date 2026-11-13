package vn.banking.ledger.posting;

import java.time.Instant;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import vn.banking.events.FraudDecidedEvent;
import vn.banking.events.FraudDecision;
import vn.banking.events.LedgerPostedEvent;
import vn.banking.events.Topics;
import vn.banking.events.TransferFailedEvent;
import vn.banking.ledger.account.Account;
import vn.banking.ledger.account.AccountRepository;
import vn.banking.ledger.entry.LedgerEntry;
import vn.banking.ledger.entry.LedgerEntryRepository;
import vn.banking.ledger.kafka.KafkaEventPublisher;

/**
 * Posts approved transfers as double entries. This service is the single owner of
 * balances: money only moves here, driven by fraud.decided events.
 * Also handles compensation/reversal when downstream partner calls fail after posting.
 */
@Service
public class PostingService {

    private static final Logger log = LoggerFactory.getLogger(PostingService.class);

    private final AccountRepository accounts;
    private final LedgerEntryRepository entries;
    private final KafkaEventPublisher publisher;

    public PostingService(AccountRepository accounts, LedgerEntryRepository entries, KafkaEventPublisher publisher) {
        this.accounts = accounts;
        this.entries = entries;
        this.publisher = publisher;
    }

    @Transactional
    public void onFraudDecided(FraudDecidedEvent event) {
        if (event.decision() != FraudDecision.APPROVED) {
            // Rejected transfers never reach the ledger; transfer-service owns that failure.
            return;
        }

        if (entries.existsByTransferId(event.transferId())) {
            // Redelivery after a published event was lost: re-publish so the saga can recover.
            log.info("Transfer {} already posted, re-publishing ledger.posted", event.transferId());
            publisher.publish(Topics.LEDGER_POSTED, event.sourceAccountId().toString(), toLedgerPosted(event));
            return;
        }

        if (event.sourceAccountId().equals(event.destAccountId())) {
            publishFailed(event, "SELF_TRANSFER_NOT_ALLOWED");
            return;
        }

        // Lock both accounts in deterministic (sorted) order to avoid deadlocks
        // when two transfers touch the same accounts in opposite directions.
        UUID firstId = event.sourceAccountId();
        UUID secondId = event.destAccountId();
        if (secondId.compareTo(firstId) < 0) {
            var tmp = firstId;
            firstId = secondId;
            secondId = tmp;
        }
        Account first = accounts.findByIdForUpdate(firstId).orElse(null);
        Account second = accounts.findByIdForUpdate(secondId).orElse(null);
        Account source = firstId.equals(event.sourceAccountId()) ? first : second;
        Account dest = firstId.equals(event.sourceAccountId()) ? second : first;

        if (source == null || dest == null) {
            publishFailed(event, "ACCOUNT_NOT_FOUND");
            return;
        }
        if (!event.currency().equals(source.getCurrency()) || !event.currency().equals(dest.getCurrency())) {
            publishFailed(event, "CURRENCY_MISMATCH");
            return;
        }
        if (source.getBalance() < event.amount()) {
            publishFailed(event, "INSUFFICIENT_FUNDS");
            return;
        }

        source.debit(event.amount());
        dest.credit(event.amount());
        entries.save(new LedgerEntry(event.transferId(), source.getId(), LedgerEntry.Direction.DEBIT, event.amount()));
        entries.save(new LedgerEntry(event.transferId(), dest.getId(), LedgerEntry.Direction.CREDIT, event.amount()));

        publisher.publish(Topics.LEDGER_POSTED, source.getId().toString(), toLedgerPosted(event));
        log.info("Posted transfer {}: debit {} from {}, credit {} to {}",
                event.transferId(), event.amount(), source.getId(), event.amount(), dest.getId());
    }

    /**
     * Compensating transaction: if money was posted for a transfer that later failed
     * (e.g. partner bank timeout / failure), reverses the balances and records reversal entries.
     */
    @Transactional
    public void onTransferFailed(TransferFailedEvent event) {
        var existing = entries.findByTransferId(event.transferId());
        if (existing.isEmpty()) {
            // Money was never posted; nothing to compensate
            return;
        }

        var debitEntry = existing.stream()
                .filter(e -> e.getDirection() == LedgerEntry.Direction.DEBIT)
                .findFirst().orElse(null);
        var creditEntry = existing.stream()
                .filter(e -> e.getDirection() == LedgerEntry.Direction.CREDIT)
                .findFirst().orElse(null);

        if (debitEntry == null || creditEntry == null) {
            return;
        }

        // Check if reversal is already recorded for the source account
        boolean alreadyReversed = existing.stream()
                .anyMatch(e -> e.getAccountId().equals(debitEntry.getAccountId())
                        && e.getDirection() == LedgerEntry.Direction.CREDIT);
        if (alreadyReversed) {
            log.info("Transfer {} already compensated/reversed", event.transferId());
            return;
        }

        UUID sourceId = debitEntry.getAccountId();
        UUID destId = creditEntry.getAccountId();
        long amount = debitEntry.getAmount();

        UUID firstId = sourceId;
        UUID secondId = destId;
        if (secondId.compareTo(firstId) < 0) {
            var tmp = firstId;
            firstId = secondId;
            secondId = tmp;
        }
        Account first = accounts.findByIdForUpdate(firstId).orElse(null);
        Account second = accounts.findByIdForUpdate(secondId).orElse(null);
        Account source = firstId.equals(sourceId) ? first : second;
        Account dest = firstId.equals(sourceId) ? second : first;

        if (source == null || dest == null) {
            log.error("Cannot reverse transfer {}: account not found", event.transferId());
            return;
        }

        // Reversal: refund source, debit back from dest
        source.credit(amount);
        dest.reverseCredit(amount);
        entries.save(new LedgerEntry(event.transferId(), source.getId(), LedgerEntry.Direction.CREDIT, amount));
        entries.save(new LedgerEntry(event.transferId(), dest.getId(), LedgerEntry.Direction.DEBIT, amount));
        if (dest.getBalance() < 0) {
            // shortcut: negative balance is only logged, add a collections/hold flow when real money moves.
            log.warn("Reversal of transfer {} left account {} at balance {}",
                    event.transferId(), dest.getId(), dest.getBalance());
        }

        log.info("Compensated transfer {}: refunded {} to source {}, debited {} from dest {}",
                event.transferId(), amount, source.getId(), amount, dest.getId());
    }

    private void publishFailed(FraudDecidedEvent event, String reason) {
        log.warn("Transfer {} cannot be posted: {}", event.transferId(), reason);
        publisher.publish(Topics.LEDGER_REJECTED, event.sourceAccountId().toString(),
                new TransferFailedEvent(event.transferId(), reason, Instant.now()));
    }

    private static LedgerPostedEvent toLedgerPosted(FraudDecidedEvent event) {
        return new LedgerPostedEvent(
                event.transferId(),
                event.sourceAccountId(),
                event.destAccountId(),
                event.amount(),
                event.currency(),
                Instant.now());
    }
}
