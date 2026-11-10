package vn.banking.notification.service;

import java.text.NumberFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.Pageable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import vn.banking.notification.domain.Notification;
import vn.banking.notification.domain.NotificationRepository;

@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    // Same wording as the client's REASONS (client/src/format.ts); unknown codes are shown as-is.
    private static final Map<String, String> REASONS = Map.of(
            "FRAUD_REJECTED", "Giao dịch bị chặn bởi kiểm tra an toàn (số tiền vượt ngưỡng hoặc chuyển quá nhiều lần trong thời gian ngắn).",
            "INSUFFICIENT_FUNDS", "Số dư tài khoản nguồn không đủ.",
            "ACCOUNT_NOT_FOUND", "Không tìm thấy tài khoản nhận.",
            "CURRENCY_MISMATCH", "Hai tài khoản khác loại tiền.",
            "SELF_TRANSFER_NOT_ALLOWED", "Không thể chuyển vào chính tài khoản nguồn.",
            "PARTNER_TIMEOUT", "Ngân hàng đối tác không phản hồi kịp. Tiền đã được hoàn lại tài khoản nguồn.",
            "PARTNER_FAILED", "Ngân hàng đối tác từ chối giao dịch. Tiền đã được hoàn lại tài khoản nguồn.");

    private final NotificationRepository repository;

    public NotificationService(NotificationRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public Notification notifyTransferCompleted(UUID customerId, UUID transferId, long amount, String currency) {
        return send(customerId, transferId, "Chuyển tiền thành công",
                "Lệnh chuyển %s %s đã hoàn tất. Mã giao dịch %s.".formatted(money(amount), currency, transferId));
    }

    @Transactional
    public Notification notifyTransferReceived(UUID destCustomerId, UUID transferId, long amount, String currency) {
        return send(destCustomerId, transferId, "Bạn vừa nhận tiền",
                "Tài khoản của bạn vừa nhận %s %s. Mã giao dịch %s.".formatted(money(amount), currency, transferId));
    }

    @Transactional
    public Notification notifyTransferFailed(UUID customerId, UUID transferId, String reason) {
        String text = reason == null ? "" : REASONS.getOrDefault(reason, reason);
        return send(customerId, transferId, "Chuyển tiền không thành công",
                "Lệnh chuyển (mã %s) không thực hiện được: %s".formatted(transferId, text));
    }

    private static String money(long amount) {
        return NumberFormat.getIntegerInstance(Locale.forLanguageTag("vi-VN")).format(amount);
    }

    private Notification send(UUID customerId, UUID transferId, String title, String content) {
        // Kafka delivers at least once: a redelivered event must not message the customer twice
        var alreadySent = repository.findFirstByTransferIdAndTitle(transferId, title);
        if (alreadySent.isPresent()) {
            return alreadySent.get();
        }
        // shortcut: recipient is the customer id; store email when a real EMAIL/SMS sender is added.
        String recipient = customerId != null ? customerId.toString() : "user";

        // Mock dispatch to Email / SMS
        log.info("[MOCK DISPATCH] Sending Notification: recipient={}, title='{}', content='{}'",
                recipient, title, content);

        return repository.save(new Notification(
                customerId != null ? customerId : UUID.randomUUID(),
                transferId,
                Notification.Channel.EMAIL,
                recipient,
                title,
                content,
                Notification.Status.SENT));
    }

    @Transactional(readOnly = true)
    public List<Notification> listFor(UUID customerId, Pageable pageable) {
        return repository.findByCustomerIdOrderByCreatedAtDesc(customerId, pageable);
    }
}
