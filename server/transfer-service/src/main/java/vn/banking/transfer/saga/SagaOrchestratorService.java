package vn.banking.transfer.saga;

import java.time.Instant;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import vn.banking.events.EventJson;
import vn.banking.events.FraudDecidedEvent;
import vn.banking.events.FraudDecision;
import vn.banking.events.LedgerPostedEvent;
import vn.banking.events.Topics;
import vn.banking.events.TransferCompletedEvent;
import vn.banking.events.TransferFailedEvent;
import vn.banking.events.TransferRequestedEvent;
import vn.banking.transfer.account.AccountValidationClient;
import vn.banking.transfer.account.CustomerStatusClient;
import vn.banking.transfer.domain.OutboxEvent;
import vn.banking.transfer.domain.OutboxEventRepository;
import vn.banking.transfer.domain.Transfer;
import vn.banking.transfer.domain.TransferRepository;
import vn.banking.transfer.partner.PartnerBankClient;

/**
 * Saga orchestrator. State machine: PENDING -> COMPLETED | FAILED.
 *
 * The saga never publishes to Kafka directly: every event it emits (transfer.requested,
 * transfer.failed, transfer.completed) is written as an outbox row in the SAME
 * transaction as the state change, and the Debezium outbox router publishes it.
 * This guarantees "state changed" and "event emitted" always happen together.
 */
@Service
public class SagaOrchestratorService {

    private static final Logger log = LoggerFactory.getLogger(SagaOrchestratorService.class);

    private final TransferRepository transfers;
    private final OutboxEventRepository outbox;
    private final AccountValidationClient accountValidation;
    private final PartnerBankClient partnerBank;
    private final CustomerStatusClient customerStatus;

    public SagaOrchestratorService(TransferRepository transfers,
            OutboxEventRepository outbox,
            AccountValidationClient accountValidation,
            PartnerBankClient partnerBank,
            CustomerStatusClient customerStatus) {
        this.transfers = transfers;
        this.outbox = outbox;
        this.accountValidation = accountValidation;
        this.partnerBank = partnerBank;
        this.customerStatus = customerStatus;
    }

    /**
     * Starts a saga. The command is idempotent: replaying the same Idempotency-Key
     * returns the original transfer without creating a new saga.
     */
    @Transactional
    public StartResult start(String idempotencyKey, UUID customerId, UUID sourceAccountId,
            UUID destAccountId, String destAccountNo, long amount, String currency, String bearerToken) {
        var existing = transfers.findByCustomerIdAndIdempotencyKey(customerId, idempotencyKey);
        if (existing.isPresent()) {
            Transfer t = existing.get();
            if (!t.getSourceAccountId().equals(sourceAccountId) || !(destAccountNo != null ? destAccountNo.equals(t.getDestAccountNo()) : t.getDestAccountId().equals(destAccountId))
                    || t.getAmount() != amount || !t.getCurrency().equals(currency)) {
                throw new IdempotencyConflictException("Idempotency-Key was already used with a different request");
            }
            return new StartResult(t, false);
        }

        if (!customerStatus.isActive(bearerToken)) {
            throw new AccountValidationException("CUSTOMER_LOCKED", "Customer is locked");
        }
        var accounts = validateAccounts(customerId, sourceAccountId, destAccountId, destAccountNo, currency, bearerToken);
        var dest = accounts[1];
        destAccountId = dest.id();

        Transfer transfer = new Transfer(idempotencyKey, customerId, sourceAccountId, destAccountId, amount, currency);
        transfer.setAccountDetails(accounts[0].accountNo(), dest.accountNo(), dest.customerId());
        transfer = transfers.save(transfer);

        outbox.save(OutboxEvent.forTopic(
                Topics.TRANSFER_REQUESTED,
                sourceAccountId.toString(),
                EventJson.write(new TransferRequestedEvent(
                        transfer.getId(), idempotencyKey, sourceAccountId, destAccountId,
                        amount, currency, Instant.now()))));

        log.info("Saga {} started (PENDING) for transfer {}", transfer.getId(), idempotencyKey);
        return new StartResult(transfer, true);
    }

    public record StartResult(Transfer transfer, boolean created) {
    }

    @Transactional
    public void onFraudDecided(FraudDecidedEvent event) {
        Transfer transfer = transfers.findById(event.transferId()).orElse(null);
        if (transfer == null || transfer.getStatus() != Transfer.Status.PENDING) {
            return;
        }
        if (event.decision() == FraudDecision.APPROVED) {
            return; // waiting for ledger.posted
        }
        fail(transfer, "FRAUD_REJECTED");
    }

    @Transactional
    public void onLedgerRejected(TransferFailedEvent event) {
        Transfer transfer = transfers.findById(event.transferId()).orElse(null);
        if (transfer == null || transfer.getStatus() != Transfer.Status.PENDING) {
            return;
        }
        fail(transfer, event.reason());
    }

    @Transactional
    public void onLedgerPosted(LedgerPostedEvent event) {
        Transfer transfer = transfers.findById(event.transferId()).orElse(null);
        if (transfer == null || transfer.getStatus() != Transfer.Status.PENDING) {
            return;
        }

        var partnerResult = partnerBank.executeTransfer(
                transfer.getIdempotencyKey(),
                // The partner mock accepts opaque account references, so the account ids
                // serve in place of account numbers (which only the ledger knows).
                transfer.getSourceAccountId().toString(),
                transfer.getDestAccountId().toString(),
                transfer.getAmount(),
                transfer.getCurrency());

        if (partnerResult.success()) {
            transfer.markCompleted();
            outbox.save(OutboxEvent.forTopic(
                    Topics.TRANSFER_COMPLETED,
                    event.sourceAccountId().toString(),
                    EventJson.write(new TransferCompletedEvent(
                            transfer.getId(), transfer.getCustomerId(), transfer.getAmount(), transfer.getCurrency(), Instant.now(),
                            transfer.getDestCustomerId()))));
            log.info("Saga {} COMPLETED", transfer.getId());
        } else {
            transfer.markFailed(partnerResult.failureReason());
            outbox.save(OutboxEvent.forTopic(
                    Topics.TRANSFER_FAILED,
                    event.sourceAccountId().toString(),
                    EventJson.write(new TransferFailedEvent(
                            transfer.getId(), transfer.getCustomerId(), partnerResult.failureReason(), Instant.now()))));
            log.warn("Saga {} FAILED: {}", transfer.getId(), partnerResult.failureReason());
        }
    }

    private void fail(Transfer transfer, String reason) {
        transfer.markFailed(reason);
        outbox.save(OutboxEvent.forTopic(
                Topics.TRANSFER_FAILED,
                transfer.getSourceAccountId().toString(),
                EventJson.write(new TransferFailedEvent(transfer.getId(), transfer.getCustomerId(), reason, Instant.now()))));
        log.warn("Saga {} FAILED: {}", transfer.getId(), reason);
    }

    /** Returns {source, dest}. */
    private AccountValidationClient.LedgerAccount[] validateAccounts(UUID customerId, UUID sourceAccountId,
            UUID destAccountId, String destAccountNo, String currency, String bearerToken) {
        var source = accountValidation.fetch(sourceAccountId, bearerToken);
        if (source == null) {
            throw new AccountValidationException("SOURCE_ACCOUNT_INVALID", "Source account not found: " + sourceAccountId);
        }
        if (!customerId.equals(source.customerId())) {
            throw new AccountValidationException("SOURCE_ACCOUNT_INVALID", "Source account does not belong to caller");
        }
        if (!currency.equals(source.currency())) {
            throw new AccountValidationException("CURRENCY_MISMATCH", "Currency mismatch with source account");
        }
        if (sourceAccountId.equals(destAccountId)) {
            throw new AccountValidationException("SELF_TRANSFER_NOT_ALLOWED", "Source and destination accounts must differ");
        }
        var dest = destAccountNo != null
                ? accountValidation.fetchByNo(destAccountNo, bearerToken)
                : accountValidation.fetch(destAccountId, bearerToken);
        if (dest == null) {
            throw new AccountValidationException("ACCOUNT_NOT_FOUND", "Destination account not found");
        }
        if (sourceAccountId.equals(dest.id())) {
            throw new AccountValidationException("SELF_TRANSFER_NOT_ALLOWED", "Source and destination accounts must differ");
        }
        if (!currency.equals(dest.currency())) {
            throw new AccountValidationException("CURRENCY_MISMATCH", "Currency mismatch with destination account");
        }
        return new AccountValidationClient.LedgerAccount[] { source, dest };
    }

    @Transactional(readOnly = true)
    public Transfer getOwned(UUID transferId, UUID customerId) {
        Transfer transfer = transfers.findById(transferId)
                .orElseThrow(() -> new TransferNotFoundException(transferId));
        if (!transfer.getCustomerId().equals(customerId)) {
            throw new TransferNotFoundException(transferId); // don't reveal existence
        }
        return transfer;
    }

    @Transactional(readOnly = true)
    public java.util.List<Transfer> listFor(UUID customerId) {
        return transfers.findByCustomerIdOrderByCreatedAtDesc(customerId);
    }

    @Transactional(readOnly = true)
    public java.util.List<Transfer> listAll() {
        return transfers.findTop200ByOrderByCreatedAtDesc();
    }

    public static class TransferNotFoundException extends RuntimeException {
        public TransferNotFoundException(UUID id) {
            super("Transfer not found: " + id);
        }
    }

    public static class IdempotencyConflictException extends RuntimeException {
        public IdempotencyConflictException(String message) {
            super(message);
        }
    }

    public static class AccountValidationException extends RuntimeException {
        private final String code;

        public AccountValidationException(String code, String message) {
            super(message);
            this.code = code;
        }

        public String getCode() {
            return code;
        }
    }
}
