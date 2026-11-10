package vn.banking.transfer.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;

class ApiExceptionHandlerTest {

    @Test
    void upstreamFailureMapsTo503WithCode() {
        var d = new ApiExceptionHandler().upstreamUnavailable(new ResourceAccessException("down"));

        assertThat(d.getStatus()).isEqualTo(503);
        assertThat(d.getProperties()).containsEntry("code", "UPSTREAM_UNAVAILABLE");
    }
}
