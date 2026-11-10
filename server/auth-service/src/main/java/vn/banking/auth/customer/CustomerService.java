package vn.banking.auth.customer;

import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CustomerService {

    private final CustomerRepository repository;
    private final PasswordEncoder passwordEncoder;

    public CustomerService(CustomerRepository repository, PasswordEncoder passwordEncoder) {
        this.repository = repository;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional
    public Customer register(String email, String password, String fullName) {
        String normalizedEmail = email.trim().toLowerCase();
        if (repository.existsByEmailIgnoreCase(normalizedEmail)) {
            throw new DuplicateEmailException(normalizedEmail);
        }
        Customer customer = new Customer();
        customer.setEmail(normalizedEmail);
        customer.setPasswordHash(passwordEncoder.encode(password));
        customer.setFullName(fullName.trim());
        return repository.save(customer);
    }

    @Transactional(readOnly = true)
    public Customer authenticate(String email, String password) {
        Customer customer = repository.findByEmailIgnoreCase(email)
                .orElseThrow(BadCredentialsException::new);
        if (!passwordEncoder.matches(password, customer.getPasswordHash())) {
            throw new BadCredentialsException();
        }
        if (customer.getStatus() != Customer.Status.ACTIVE) {
            throw new CustomerLockedException(customer.getId());
        }
        return customer;
    }

    @Transactional(readOnly = true)
    public Customer getById(UUID id) {
        return repository.findById(id).orElseThrow(() -> new CustomerNotFoundException(id));
    }

    @Transactional(readOnly = true)
    public List<Customer> listAll(String q, Pageable pageable) {
        if (q == null || q.isBlank()) {
            return repository.findAllByOrderByCreatedAtDesc(pageable);
        }
        String term = q.trim();
        return repository.findByEmailContainingIgnoreCaseOrFullNameContainingIgnoreCaseOrderByCreatedAtDesc(
                term, term, pageable);
    }

    @Transactional
    public Customer setStatus(UUID id, Customer.Status status, UUID actingAdminId) {
        if (id.equals(actingAdminId)) {
            throw new SelfLockException();
        }
        Customer customer = getById(id);
        customer.setStatus(status);
        return customer;
    }

    public static class SelfLockException extends RuntimeException {
        public SelfLockException() {
            super("Admins cannot change their own status");
        }
    }

    public static class DuplicateEmailException extends RuntimeException {
        public DuplicateEmailException(String email) {
            super("Email already registered: " + email);
        }
    }

    public static class BadCredentialsException extends RuntimeException {
        public BadCredentialsException() {
            super("Invalid email or password");
        }
    }

    public static class CustomerNotFoundException extends RuntimeException {
        public CustomerNotFoundException(UUID id) {
            super("Customer not found: " + id);
        }
    }

    public static class CustomerLockedException extends RuntimeException {
        public CustomerLockedException(UUID id) {
            super("Customer is locked: " + id);
        }
    }
}
