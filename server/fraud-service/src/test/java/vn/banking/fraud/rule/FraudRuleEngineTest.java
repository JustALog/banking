package vn.banking.fraud.rule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import vn.banking.events.FraudDecision;
import vn.banking.events.TransferRequestedEvent;
import vn.banking.fraud.FraudProperties;
import vn.banking.fraud.domain.FraudEvaluation;
import vn.banking.fraud.domain.FraudEvaluationRepository;

@ExtendWith(MockitoExtension.class)
class FraudRuleEngineTest {

    @Mock
    private FraudEvaluationRepository repository;

    private FraudProperties properties;
    private FraudRuleEngine engine;

    private final UUID sourceAccountId = UUID.randomUUID();
    private final UUID destAccountId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        properties = new FraudProperties(5_000_000, 60, 5, 10_000_000);
        engine = new FraudRuleEngine(repository, properties);
    }

    @Test
    void approvesTransferWithinLimits() {
        TransferRequestedEvent request = new TransferRequestedEvent(
                UUID.randomUUID(), "k1", sourceAccountId, destAccountId, 1_000_000, "VND", Instant.now());

        when(repository.countRecentTransfers(eq(sourceAccountId), any(Instant.class))).thenReturn(1L);
        when(repository.sumRecentAmount(eq(sourceAccountId), any(Instant.class))).thenReturn(1_000_000L);

        var result = engine.evaluate(request);

        assertThat(result.decision()).isEqualTo(FraudDecision.APPROVED);
        assertThat(result.reason()).isEqualTo("WITHIN_LIMITS");

        ArgumentCaptor<FraudEvaluation> captor = ArgumentCaptor.forClass(FraudEvaluation.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getRuleTriggered()).isEqualTo("NONE");
    }

    @Test
    void rejectsTransferOverSingleThreshold() {
        TransferRequestedEvent request = new TransferRequestedEvent(
                UUID.randomUUID(), "k2", sourceAccountId, destAccountId, 6_000_000, "VND", Instant.now());

        var result = engine.evaluate(request);

        assertThat(result.decision()).isEqualTo(FraudDecision.REJECTED);
        assertThat(result.reason()).isEqualTo("AMOUNT_OVER_THRESHOLD");

        ArgumentCaptor<FraudEvaluation> captor = ArgumentCaptor.forClass(FraudEvaluation.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getRuleTriggered()).isEqualTo("THRESHOLD");
    }

    @Test
    void rejectsTransferWhenVelocityFrequencyExceeded() {
        TransferRequestedEvent request = new TransferRequestedEvent(
                UUID.randomUUID(), "k3", sourceAccountId, destAccountId, 100_000, "VND", Instant.now());

        when(repository.countRecentTransfers(eq(sourceAccountId), any(Instant.class))).thenReturn(5L);

        var result = engine.evaluate(request);

        assertThat(result.decision()).isEqualTo(FraudDecision.REJECTED);
        assertThat(result.reason()).contains("TOO_MANY_TRANSFERS_IN_WINDOW");

        ArgumentCaptor<FraudEvaluation> captor = ArgumentCaptor.forClass(FraudEvaluation.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getRuleTriggered()).isEqualTo("VELOCITY_COUNT");
    }

    @Test
    void rejectsTransferWhenVelocityAggregateAmountExceeded() {
        TransferRequestedEvent request = new TransferRequestedEvent(
                UUID.randomUUID(), "k4", sourceAccountId, destAccountId, 2_000_000, "VND", Instant.now());

        when(repository.countRecentTransfers(eq(sourceAccountId), any(Instant.class))).thenReturn(2L);
        when(repository.sumRecentAmount(eq(sourceAccountId), any(Instant.class))).thenReturn(9_000_000L);

        var result = engine.evaluate(request);

        assertThat(result.decision()).isEqualTo(FraudDecision.REJECTED);
        assertThat(result.reason()).contains("AGGREGATE_AMOUNT_EXCEEDED");

        ArgumentCaptor<FraudEvaluation> captor = ArgumentCaptor.forClass(FraudEvaluation.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getRuleTriggered()).isEqualTo("VELOCITY_AMOUNT");
    }

    @Test
    void redeliveredTransferRepeatsOriginalDecisionWithoutNewRow() {
        UUID transferId = UUID.randomUUID();
        TransferRequestedEvent request = new TransferRequestedEvent(
                transferId, "k-dup", sourceAccountId, destAccountId, 1_000_000, "VND", Instant.now());
        when(repository.findByTransferId(transferId)).thenReturn(Optional.of(new FraudEvaluation(
                transferId, sourceAccountId, destAccountId, 1_000_000, "VND",
                FraudDecision.REJECTED, "VELOCITY_COUNT", "TOO_MANY_TRANSFERS_IN_WINDOW: 5")));

        var result = engine.evaluate(request);

        assertThat(result.decision()).isEqualTo(FraudDecision.REJECTED);
        assertThat(result.reason()).isEqualTo("TOO_MANY_TRANSFERS_IN_WINDOW: 5");
        verify(repository, never()).save(any());
        verify(repository, never()).countRecentTransfers(any(), any());
    }
}
