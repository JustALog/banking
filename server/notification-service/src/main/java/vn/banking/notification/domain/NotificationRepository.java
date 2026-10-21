package vn.banking.notification.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface NotificationRepository extends JpaRepository<Notification, UUID> {

    List<Notification> findByCustomerIdOrderByCreatedAtDesc(UUID customerId);

    Optional<Notification> findFirstByTransferIdAndTitle(UUID transferId, String title);
}
