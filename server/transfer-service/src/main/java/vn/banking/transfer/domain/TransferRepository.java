package vn.banking.transfer.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface TransferRepository extends JpaRepository<Transfer, UUID> {

    Optional<Transfer> findByCustomerIdAndIdempotencyKey(UUID customerId, String idempotencyKey);

    List<Transfer> findByCustomerIdOrderByCreatedAtDesc(UUID customerId);

    List<Transfer> findTop200ByOrderByCreatedAtDesc();
}
