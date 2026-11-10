package vn.banking.transfer.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import vn.banking.transfer.saga.SagaOrchestratorService;

@WebMvcTest(TransferController.class)
@AutoConfigureMockMvc(addFilters = false)
class TransferPagingTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    SagaOrchestratorService saga;

    @MockitoBean
    JwtDecoder jwtDecoder;

    @Test
    void pageSizeIsClampedTo200() throws Exception {
        when(saga.listAll(any())).thenReturn(List.of());

        mvc.perform(get("/transfers/admin").param("size", "1000")).andExpect(status().isOk());

        var captor = ArgumentCaptor.forClass(Pageable.class);
        verify(saga).listAll(captor.capture());
        assertThat(captor.getValue().getPageSize()).isEqualTo(200);
    }
}
