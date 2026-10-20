package vn.banking.fraud;

import java.time.Instant;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import vn.banking.events.EventJson;
import vn.banking.events.FraudDecidedEvent;
import vn.banking.events.Topics;
import vn.banking.events.TransferRequestedEvent;
import vn.banking.fraud.rule.FraudRuleEngine;

@Component
public class TransferRequestedListener {

    private static final Logger log = LoggerFactory.getLogger(TransferRequestedListener.class);

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final FraudRuleEngine ruleEngine;

    public TransferRequestedListener(KafkaTemplate<String, String> kafkaTemplate, FraudRuleEngine ruleEngine) {
        this.kafkaTemplate = kafkaTemplate;
        this.ruleEngine = ruleEngine;
    }

    @KafkaListener(topics = Topics.TRANSFER_REQUESTED, groupId = "${spring.kafka.consumer.group-id}")
    public void onTransferRequested(String payload) {
        TransferRequestedEvent request = EventJson.read(payload, TransferRequestedEvent.class);

        var result = ruleEngine.evaluate(request);

        FraudDecidedEvent decided = new FraudDecidedEvent(
                request.transferId(),
                request.sourceAccountId(),
                request.destAccountId(),
                request.amount(),
                request.currency(),
                result.decision(),
                result.reason(),
                Instant.now());

        try {
            // wait for the ack: on failure the listener throws and transfer.requested is redelivered
            kafkaTemplate.send(Topics.FRAUD_DECIDED, request.sourceAccountId().toString(), EventJson.write(decided))
                    .get(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted publishing fraud decision", e);
        } catch (ExecutionException | TimeoutException e) {
            throw new IllegalStateException("Failed to publish fraud decision for " + request.transferId(), e);
        }
        log.info("Fraud decision published for transfer {}: {}", request.transferId(), result.decision());
    }
}
