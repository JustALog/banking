package vn.banking.ledger.posting;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import vn.banking.events.EventJson;
import vn.banking.events.FraudDecidedEvent;
import vn.banking.events.Topics;

@Component
public class FraudDecidedListener {

    private final PostingService postingService;

    public FraudDecidedListener(PostingService postingService) {
        this.postingService = postingService;
    }

    @KafkaListener(topics = Topics.FRAUD_DECIDED, groupId = "${spring.kafka.consumer.group-id}")
    public void onFraudDecided(String payload) {
        postingService.onFraudDecided(EventJson.read(payload, FraudDecidedEvent.class));
    }
}
