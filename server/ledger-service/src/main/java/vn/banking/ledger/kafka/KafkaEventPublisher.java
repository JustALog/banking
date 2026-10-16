package vn.banking.ledger.kafka;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import vn.banking.events.EventJson;

/**
 * Publishes events only after the surrounding transaction commits, so a rollback
 * never leaks an event for state that does not exist.
 */
@Component
public class KafkaEventPublisher {

    private final KafkaTemplate<String, String> kafkaTemplate;

    public KafkaEventPublisher(KafkaTemplate<String, String> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    public void publish(String topic, String key, Object event) {
        String payload = EventJson.write(event);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    doSend(topic, key, payload);
                }
            });
        } else {
            doSend(topic, key, payload);
        }
    }

    // Blocks until Kafka acks: a failed send throws out of the listener, so the consumed
    // record is redelivered instead of committed (PostingService re-publishes on redelivery).
    private void doSend(String topic, String key, String payload) {
        try {
            kafkaTemplate.send(topic, key, payload).get(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted publishing to " + topic, e);
        } catch (ExecutionException | TimeoutException e) {
            throw new IllegalStateException("Failed to publish to " + topic, e);
        }
    }
}
