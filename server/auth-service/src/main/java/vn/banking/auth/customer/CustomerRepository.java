package vn.banking.auth.customer;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface CustomerRepository extends JpaRepository<Customer, UUID> {

    boolean existsByEmailIgnoreCase(String email);

    Optional<Customer> findByEmailIgnoreCase(String email);
}
