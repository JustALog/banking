package vn.banking.transfer.saga;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import vn.banking.events.EventJson;
import vn.banking.events.FraudDecidedEvent;
import vn.banking.events.LedgerPostedEvent;
import vn.banking.events.Topics;
import vn.banking.events.TransferFailedEvent;

@Component
public class TransferEventListeners {

    private final SagaOrchestratorService saga;

    public TransferEventListeners(SagaOrchestratorService saga) {
        this.saga = saga;
    }

    @KafkaListener(topics = Topics.FRAUD_DECIDED, groupId = "${spring.kafka.consumer.group-id}")
    public void onFraudDecided(String payload) {
        saga.onFraudDecided(EventJson.read(payload, FraudDecidedEvent.class));
    }

    @KafkaListener(topics = Topics.LEDGER_POSTED, groupId = "${spring.kafka.consumer.group-id}")
    public void onLedgerPosted(String payload) {
        saga.onLedgerPosted(EventJson.read(payload, LedgerPostedEvent.class));
    }

    @KafkaListener(topics = Topics.LEDGER_REJECTED, groupId = "${spring.kafka.consumer.group-id}")
    public void onLedgerRejected(String payload) {
        saga.onLedgerRejected(EventJson.read(payload, TransferFailedEvent.class));
    }
}
