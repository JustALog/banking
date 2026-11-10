package vn.banking.transfer.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import vn.banking.transfer.domain.Transfer;
import vn.banking.transfer.saga.SagaOrchestratorService;
import vn.banking.transfer.saga.SagaOrchestratorService.StartResult;
import vn.banking.transfer.web.dto.CreateTransferRequest;
import vn.banking.transfer.web.dto.TransferResponse;

class TransferControllerTest {

    @Test
    @SuppressWarnings("unchecked")
    void duplicateKeyRaceReturnsExisting() {
        var saga = mock(SagaOrchestratorService.class);
        var controller = new TransferController(saga);
        UUID customerId = UUID.randomUUID();
        var t = new Transfer("k", customerId, UUID.randomUUID(), UUID.randomUUID(), 100, "VND");
        org.springframework.test.util.ReflectionTestUtils.setField(t, "id", UUID.randomUUID());
        when(saga.start(anyString(), any(), any(), any(), any(), anyLong(), anyString(), anyString()))
                .thenThrow(new DataIntegrityViolationException("dup"))
                .thenReturn(new StartResult(t, false));
        var jwt = new Jwt("tok", Instant.now(), Instant.now().plusSeconds(60),
                Map.of("alg", "none"), Map.of("sub", customerId.toString()));
        var auth = new JwtAuthenticationToken(jwt, List.of());

        var response = controller.create(
                new CreateTransferRequest(t.getSourceAccountId(), t.getDestAccountId(), null, 100, "VND"), "k", auth);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(((TransferResponse) response.getBody()).id()).isEqualTo(t.getId());
        verify(saga, times(2)).start(anyString(), any(), any(), any(), any(), anyLong(), anyString(), anyString());
    }

    @Test
    void exactlyOneDestinationRequired() {
        UUID a = UUID.randomUUID();
        assertThat(new CreateTransferRequest(a, a, null, 1, "VND").isDestinationGiven()).isTrue();
        assertThat(new CreateTransferRequest(a, null, "VN0123456789", 1, "VND").isDestinationGiven()).isTrue();
        assertThat(new CreateTransferRequest(a, a, "VN0123456789", 1, "VND").isDestinationGiven()).isFalse();
        assertThat(new CreateTransferRequest(a, null, null, 1, "VND").isDestinationGiven()).isFalse();
    }
}
