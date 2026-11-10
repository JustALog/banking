package vn.banking.transfer.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TransferRepository extends JpaRepository<Transfer, UUID> {

    Optional<Transfer> findByCustomerIdAndIdempotencyKey(UUID customerId, String idempotencyKey);

    /** Own transfers (any status) plus completed incoming ones. */
    @Query("select t from Transfer t where t.customerId = :me "
            + "or (t.destCustomerId = :me and t.status = vn.banking.transfer.domain.Transfer.Status.COMPLETED) "
            + "order by t.createdAt desc")
    List<Transfer> findVisibleTo(@Param("me") UUID me);

    List<Transfer> findTop200ByOrderByCreatedAtDesc();
}
