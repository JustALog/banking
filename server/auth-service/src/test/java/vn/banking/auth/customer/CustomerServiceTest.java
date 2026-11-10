package vn.banking.auth.customer;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.crypto.password.PasswordEncoder;

class CustomerServiceTest {

    CustomerRepository repository = mock(CustomerRepository.class);
    CustomerService service = new CustomerService(repository, mock(PasswordEncoder.class));

    @Test
    void searchFiltersByEmailOrName() {
        var page = PageRequest.of(0, 50);

        service.listAll(" An ", page);

        verify(repository).findByEmailContainingIgnoreCaseOrFullNameContainingIgnoreCaseOrderByCreatedAtDesc("An", "An", page);
    }

    @Test
    void blankQueryListsUnfilteredPage() {
        var page = PageRequest.of(0, 50);

        service.listAll("  ", page);

        verify(repository).findAllByOrderByCreatedAtDesc(page);
    }
}
