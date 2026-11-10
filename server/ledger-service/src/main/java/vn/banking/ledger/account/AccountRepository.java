package vn.banking.ledger.account;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import jakarta.persistence.LockModeType;

public interface AccountRepository extends JpaRepository<Account, UUID> {

    boolean existsByAccountNo(String accountNo);

    long countByCustomerId(UUID customerId);

    List<Account> findByCustomerIdOrderByCreatedAt(UUID customerId);

    List<Account> findTop200ByOrderByCreatedAtDesc();

    Optional<Account> findByAccountNo(String accountNo);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Account a where a.id = :id")
    Optional<Account> findByIdForUpdate(UUID id);
}
