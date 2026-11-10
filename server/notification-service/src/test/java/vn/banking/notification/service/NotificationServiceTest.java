package vn.banking.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import vn.banking.notification.domain.Notification;
import vn.banking.notification.domain.NotificationRepository;

@ExtendWith(MockitoExtension.class)
class NotificationServiceTest {

    @Mock
    private NotificationRepository repository;

    private NotificationService service;

    @BeforeEach
    void setUp() {
        service = new NotificationService(repository);
    }

    @Test
    void notifyTransferCompletedSavesNotification() {
        UUID customerId = UUID.randomUUID();
        UUID transferId = UUID.randomUUID();

        when(repository.save(any(Notification.class))).thenAnswer(inv -> inv.getArgument(0));

        Notification result = service.notifyTransferCompleted(customerId, transferId, 500000, "VND");

        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(repository).save(captor.capture());

        Notification saved = captor.getValue();
        assertThat(saved.getCustomerId()).isEqualTo(customerId);
        assertThat(saved.getTransferId()).isEqualTo(transferId);
        assertThat(saved.getStatus()).isEqualTo(Notification.Status.SENT);
        assertThat(saved.getTitle()).isEqualTo("Chuyển tiền thành công");
        assertThat(saved.getContent()).contains("500.000 VND");
    }

    @Test
    void notifyTransferFailedSavesNotification() {
        UUID customerId = UUID.randomUUID();
        UUID transferId = UUID.randomUUID();

        when(repository.save(any(Notification.class))).thenAnswer(inv -> inv.getArgument(0));

        Notification result = service.notifyTransferFailed(customerId, transferId, "INSUFFICIENT_FUNDS");

        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(repository).save(captor.capture());

        Notification saved = captor.getValue();
        assertThat(saved.getCustomerId()).isEqualTo(customerId);
        assertThat(saved.getTransferId()).isEqualTo(transferId);
        assertThat(saved.getStatus()).isEqualTo(Notification.Status.SENT);
        assertThat(saved.getTitle()).isEqualTo("Chuyển tiền không thành công");
        assertThat(saved.getContent()).contains("Số dư tài khoản nguồn không đủ.");
    }

    @Test
    void redeliveredEventDoesNotNotifyTwice() {
        UUID transferId = UUID.randomUUID();
        Notification existing = new Notification(UUID.randomUUID(), transferId, Notification.Channel.EMAIL,
                "user", "Chuyển tiền thành công", "sent before", Notification.Status.SENT);
        when(repository.findFirstByTransferIdAndTitle(transferId, "Chuyển tiền thành công")).thenReturn(Optional.of(existing));

        Notification result = service.notifyTransferCompleted(UUID.randomUUID(), transferId, 500000, "VND");

        assertThat(result).isSameAs(existing);
        verify(repository, never()).save(any());
    }

    @Test
    void receivedNotifiesRecipientWithOwnTitle() {
        UUID dest = UUID.randomUUID();
        UUID transferId = UUID.randomUUID();
        when(repository.save(any(Notification.class))).thenAnswer(inv -> inv.getArgument(0));

        Notification n = service.notifyTransferReceived(dest, transferId, 2500000, "VND");

        assertThat(n.getCustomerId()).isEqualTo(dest);
        assertThat(n.getTitle()).isEqualTo("Bạn vừa nhận tiền");
        assertThat(n.getContent()).isEqualTo("Tài khoản của bạn vừa nhận 2.500.000 VND. Mã giao dịch " + transferId + ".");
    }

    @Test
    void unknownFailureReasonIsShownAsCode() {
        when(repository.save(any(Notification.class))).thenAnswer(inv -> inv.getArgument(0));

        Notification n = service.notifyTransferFailed(UUID.randomUUID(), UUID.randomUUID(), "WEIRD_CODE");

        assertThat(n.getContent()).endsWith("không thực hiện được: WEIRD_CODE");
    }

    @Test
    void listForPassesPageable() {
        UUID customerId = UUID.randomUUID();
        var page = org.springframework.data.domain.PageRequest.of(0, 50);

        service.listFor(customerId, page);

        verify(repository).findByCustomerIdOrderByCreatedAtDesc(customerId, page);
    }
}
