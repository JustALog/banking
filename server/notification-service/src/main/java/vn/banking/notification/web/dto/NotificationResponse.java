package vn.banking.notification.web.dto;

import java.time.Instant;
import java.util.UUID;

import vn.banking.notification.domain.Notification;

public record NotificationResponse(
        UUID id,
        UUID customerId,
        UUID transferId,
        String channel,
        String recipient,
        String title,
        String content,
        String status,
        Instant createdAt) {

    public static NotificationResponse from(Notification n) {
        return new NotificationResponse(
                n.getId(),
                n.getCustomerId(),
                n.getTransferId(),
                n.getChannel().name(),
                n.getRecipient(),
                n.getTitle(),
                n.getContent(),
                n.getStatus().name(),
                n.getCreatedAt());
    }
}
