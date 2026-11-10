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
        assertThat(saved.getTitle()).contains("Successful");
        assertThat(saved.getContent()).contains("500000 VND");
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
        assertThat(saved.getTitle()).contains("Failed");
        assertThat(saved.getContent()).contains("INSUFFICIENT_FUNDS");
    }

    @Test
    void redeliveredEventDoesNotNotifyTwice() {
        UUID transferId = UUID.randomUUID();
        Notification existing = new Notification(UUID.randomUUID(), transferId, Notification.Channel.EMAIL,
                "user", "Transfer Successful", "sent before", Notification.Status.SENT);
        when(repository.findFirstByTransferIdAndTitle(transferId, "Transfer Successful")).thenReturn(Optional.of(existing));

        Notification result = service.notifyTransferCompleted(UUID.randomUUID(), transferId, 500000, "VND");

        assertThat(result).isSameAs(existing);
        verify(repository, never()).save(any());
    }

    @Test
    void listForPassesPageable() {
        UUID customerId = UUID.randomUUID();
        var page = org.springframework.data.domain.PageRequest.of(0, 50);

        service.listFor(customerId, page);

        verify(repository).findByCustomerIdOrderByCreatedAtDesc(customerId, page);
    }
}
