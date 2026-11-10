package vn.banking.auth.customer;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Creates one test account per role. Enable with banking.seed-demo-accounts=true (never in prod). */
@Component
@ConditionalOnProperty("banking.seed-demo-accounts")
class DemoAccountSeeder implements ApplicationRunner {

    private static final String PASSWORD = "Test@1234";

    private final CustomerService service;

    DemoAccountSeeder(CustomerService service) {
        this.service = service;
    }

    @Override
    public void run(ApplicationArguments args) {
        for (Customer.Role role : Customer.Role.values()) {
            String name = role.name().toLowerCase();
            try {
                service.register(name + "@test.local", PASSWORD, "Test " + name, role);
            } catch (CustomerService.DuplicateEmailException ignored) {
                // already seeded
            }
        }
    }
}
