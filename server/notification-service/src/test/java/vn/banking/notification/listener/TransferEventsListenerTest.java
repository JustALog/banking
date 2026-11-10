package vn.banking.notification.listener;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import vn.banking.events.EventJson;
import vn.banking.events.TransferCompletedEvent;
import vn.banking.notification.service.NotificationService;

class TransferEventsListenerTest {

    private final NotificationService service = mock(NotificationService.class);
    private final TransferEventsListener listener = new TransferEventsListener(service);

    private String completed(UUID sender, UUID dest, UUID transferId) {
        return EventJson.write(new TransferCompletedEvent(transferId, sender, 1000, "VND", Instant.now(), dest));
    }

    @Test
    void completedNotifiesSenderAndRecipient() {
        UUID sender = UUID.randomUUID(), dest = UUID.randomUUID(), t = UUID.randomUUID();

        listener.onTransferCompleted(completed(sender, dest, t));

        verify(service).notifyTransferCompleted(sender, t, 1000, "VND");
        verify(service).notifyTransferReceived(dest, t, 1000, "VND");
    }

    @Test
    void selfTransferNotifiesOnce() {
        UUID me = UUID.randomUUID(), t = UUID.randomUUID();

        listener.onTransferCompleted(completed(me, me, t));

        verify(service).notifyTransferCompleted(me, t, 1000, "VND");
        verify(service, never()).notifyTransferReceived(any(), any(), anyLong(), any());
    }
}
