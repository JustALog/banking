package vn.banking.fraud.domain;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface FraudEvaluationRepository extends JpaRepository<FraudEvaluation, UUID> {

    Optional<FraudEvaluation> findByTransferId(UUID transferId);

    @Query("SELECT COUNT(f) FROM FraudEvaluation f WHERE f.sourceAccountId = :accountId AND f.createdAt >= :since")
    long countRecentTransfers(@Param("accountId") UUID accountId, @Param("since") Instant since);

    @Query("SELECT COALESCE(SUM(f.amount), 0) FROM FraudEvaluation f WHERE f.sourceAccountId = :accountId AND f.createdAt >= :since")
    long sumRecentAmount(@Param("accountId") UUID accountId, @Param("since") Instant since);
}
