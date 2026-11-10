package vn.banking.notification.listener;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import vn.banking.events.EventJson;
import vn.banking.events.Topics;
import vn.banking.events.TransferCompletedEvent;
import vn.banking.events.TransferFailedEvent;
import vn.banking.notification.service.NotificationService;

@Component
public class TransferEventsListener {

    private static final Logger log = LoggerFactory.getLogger(TransferEventsListener.class);

    private final NotificationService notificationService;

    public TransferEventsListener(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @KafkaListener(topics = Topics.TRANSFER_COMPLETED, groupId = "${spring.kafka.consumer.group-id}")
    public void onTransferCompleted(String payload) {
        TransferCompletedEvent event = EventJson.read(payload, TransferCompletedEvent.class);
        log.info("Received transfer.completed event for transfer {}", event.transferId());
        notificationService.notifyTransferCompleted(
                event.customerId(), event.transferId(), event.amount(), event.currency());
        // A transfer between two of the sender's own accounts needs only the one message.
        if (event.destCustomerId() != null && !event.destCustomerId().equals(event.customerId())) {
            notificationService.notifyTransferReceived(
                    event.destCustomerId(), event.transferId(), event.amount(), event.currency());
        }
    }

    @KafkaListener(topics = Topics.TRANSFER_FAILED, groupId = "${spring.kafka.consumer.group-id}")
    public void onTransferFailed(String payload) {
        TransferFailedEvent event = EventJson.read(payload, TransferFailedEvent.class);
        log.info("Received transfer.failed event for transfer {}", event.transferId());
        notificationService.notifyTransferFailed(
                event.customerId(), event.transferId(), event.reason());
    }
}
