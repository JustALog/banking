package vn.banking.ledger.entry;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, UUID> {

    boolean existsByTransferId(UUID transferId);

    List<LedgerEntry> findByTransferId(UUID transferId);

    List<LedgerEntry> findByAccountIdOrderByCreatedAtDesc(UUID accountId);
}
