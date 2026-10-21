package vn.banking.notification.service;

import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import vn.banking.notification.domain.Notification;
import vn.banking.notification.domain.NotificationRepository;

@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final NotificationRepository repository;

    public NotificationService(NotificationRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public Notification notifyTransferCompleted(UUID customerId, UUID transferId, long amount, String currency) {
        String title = "Transfer Successful";
        String content = "Your transfer of %d %s (ID: %s) has been successfully processed."
                .formatted(amount, currency, transferId);
        var alreadySent = repository.findFirstByTransferIdAndTitle(transferId, title);
        if (alreadySent.isPresent()) {
            return alreadySent.get();
        }
        String recipient = customerId != null ? customerId.toString() : "user";

        // Mock dispatch to Email / SMS
        log.info("[MOCK DISPATCH] Sending Notification: recipient={}, title='{}', content='{}'",
                recipient, title, content);

        Notification notification = new Notification(
                customerId != null ? customerId : UUID.randomUUID(),
                transferId,
                Notification.Channel.EMAIL,
                recipient,
                title,
                content,
                Notification.Status.SENT);

        return repository.save(notification);
    }

    @Transactional
    public Notification notifyTransferFailed(UUID customerId, UUID transferId, String reason) {
        String title = "Transfer Failed";
        String content = "Your transfer (ID: %s) could not be completed. Reason: %s."
                .formatted(transferId, reason);
        // Kafka delivers at least once: a redelivered event must not message the customer twice
        var alreadySent = repository.findFirstByTransferIdAndTitle(transferId, title);
        if (alreadySent.isPresent()) {
            return alreadySent.get();
        }
        String recipient = customerId != null ? customerId.toString() : "user";

        // Mock dispatch to Email / SMS
        log.info("[MOCK DISPATCH] Sending Notification: recipient={}, title='{}', content='{}'",
                recipient, title, content);

        Notification notification = new Notification(
                customerId != null ? customerId : UUID.randomUUID(),
                transferId,
                Notification.Channel.EMAIL,
                recipient,
                title,
                content,
                Notification.Status.SENT);

        return repository.save(notification);
    }

    @Transactional(readOnly = true)
    public List<Notification> listFor(UUID customerId) {
        return repository.findByCustomerIdOrderByCreatedAtDesc(customerId);
    }
}
