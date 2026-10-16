package vn.banking.ledger.posting;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import vn.banking.events.EventJson;
import vn.banking.events.Topics;
import vn.banking.events.TransferFailedEvent;

@Component
public class TransferFailedListener {

    private static final Logger log = LoggerFactory.getLogger(TransferFailedListener.class);

    private final PostingService postingService;

    public TransferFailedListener(PostingService postingService) {
        this.postingService = postingService;
    }

    @KafkaListener(topics = Topics.TRANSFER_FAILED, groupId = "${spring.kafka.consumer.group-id}")
    public void onTransferFailed(String payload) {
        TransferFailedEvent event = EventJson.read(payload, TransferFailedEvent.class);
        log.info("Received transfer.failed event for compensation check: {}", event.transferId());
        postingService.onTransferFailed(event);
    }
}
