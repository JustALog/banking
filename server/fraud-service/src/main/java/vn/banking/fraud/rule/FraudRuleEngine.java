package vn.banking.fraud.rule;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import vn.banking.events.FraudDecision;
import vn.banking.events.TransferRequestedEvent;
import vn.banking.fraud.FraudProperties;
import vn.banking.fraud.domain.FraudEvaluation;
import vn.banking.fraud.domain.FraudEvaluationRepository;

@Service
public class FraudRuleEngine {

    private static final Logger log = LoggerFactory.getLogger(FraudRuleEngine.class);

    private final FraudEvaluationRepository repository;
    private final FraudProperties properties;

    public FraudRuleEngine(FraudEvaluationRepository repository, FraudProperties properties) {
        this.repository = repository;
        this.properties = properties;
    }

    @Transactional
    public EvaluationResult evaluate(TransferRequestedEvent request) {
        // Redelivered/replayed event: repeat the original decision. Re-evaluating would add a second
        // row that the velocity rules count, and could flip the decision.
        var previous = repository.findByTransferId(request.transferId());
        if (previous.isPresent()) {
            log.info("Transfer {} already evaluated, repeating decision {}", request.transferId(), previous.get().getDecision());
            return new EvaluationResult(previous.get().getDecision(), previous.get().getReason());
        }

        // 1. Single transfer amount threshold
        if (request.amount() > properties.amountThreshold()) {
            return recordAndReturn(request, FraudDecision.REJECTED, "THRESHOLD", "AMOUNT_OVER_THRESHOLD");
        }

        Instant windowStart = Instant.now().minus(properties.velocityWindowSeconds(), ChronoUnit.SECONDS);

        // 2. Velocity: transfer frequency in sliding window
        long recentCount = repository.countRecentTransfers(request.sourceAccountId(), windowStart);
        if (recentCount >= properties.velocityMaxTransfers()) {
            return recordAndReturn(request, FraudDecision.REJECTED, "VELOCITY_COUNT",
                    "TOO_MANY_TRANSFERS_IN_WINDOW: " + recentCount);
        }

        // 3. Velocity: aggregate amount in sliding window
        long recentSum = repository.sumRecentAmount(request.sourceAccountId(), windowStart);
        if (recentSum + request.amount() > properties.velocityMaxAmount()) {
            return recordAndReturn(request, FraudDecision.REJECTED, "VELOCITY_AMOUNT",
                    "AGGREGATE_AMOUNT_EXCEEDED: current=" + (recentSum + request.amount())
                            + " max=" + properties.velocityMaxAmount());
        }

        // 4. Approved
        return recordAndReturn(request, FraudDecision.APPROVED, "NONE", "WITHIN_LIMITS");
    }

    private EvaluationResult recordAndReturn(TransferRequestedEvent request, FraudDecision decision,
            String ruleTriggered, String reason) {
        FraudEvaluation evaluation = new FraudEvaluation(
                request.transferId(),
                request.sourceAccountId(),
                request.destAccountId(),
                request.amount(),
                request.currency(),
                decision,
                ruleTriggered,
                reason);
        repository.save(evaluation);
        log.info("Evaluated transfer {}: decision={}, rule={}, reason='{}'",
                request.transferId(), decision, ruleTriggered, reason);
        return new EvaluationResult(decision, reason);
    }

    public record EvaluationResult(FraudDecision decision, String reason) {
    }
}
