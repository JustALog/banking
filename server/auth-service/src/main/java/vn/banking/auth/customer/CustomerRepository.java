package vn.banking.auth.customer;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;

import org.springframework.data.jpa.repository.JpaRepository;

public interface CustomerRepository extends JpaRepository<Customer, UUID> {

    boolean existsByEmailIgnoreCase(String email);

    Optional<Customer> findByEmailIgnoreCase(String email);

    List<Customer> findAllByOrderByCreatedAtDesc(Pageable pageable);

    List<Customer> findByEmailContainingIgnoreCaseOrFullNameContainingIgnoreCaseOrderByCreatedAtDesc(
            String email, String fullName, Pageable pageable);
}
