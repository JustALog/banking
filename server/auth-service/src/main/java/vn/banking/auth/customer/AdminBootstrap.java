package vn.banking.auth.customer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/** Creates the admin account from BANKING_ADMIN_EMAIL / BANKING_ADMIN_PASSWORD when both are set. */
@Component
class AdminBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);

    private final CustomerRepository repository;
    private final PasswordEncoder passwordEncoder;
    private final String email;
    private final String password;

    AdminBootstrap(CustomerRepository repository, PasswordEncoder passwordEncoder,
            @Value("${banking.admin.email:}") String email,
            @Value("${banking.admin.password:}") String password) {
        this.repository = repository;
        this.passwordEncoder = passwordEncoder;
        this.email = email.trim().toLowerCase();
        this.password = password;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (email.isEmpty() || password.isEmpty()) {
            return;
        }
        var existing = repository.findByEmailIgnoreCase(email);
        if (existing.isPresent()) {
            // Never promote an existing account: whoever registered this email first would become admin.
            if (existing.get().getRole() != Customer.Role.ADMIN) {
                log.warn("Admin email {} is already a regular customer; not promoting it", email);
            }
            return;
        }
        Customer admin = new Customer();
        admin.setEmail(email);
        admin.setPasswordHash(passwordEncoder.encode(password));
        admin.setFullName("Administrator");
        admin.setRole(Customer.Role.ADMIN);
        repository.save(admin);
        log.info("Created admin account {}", email);
    }
}
