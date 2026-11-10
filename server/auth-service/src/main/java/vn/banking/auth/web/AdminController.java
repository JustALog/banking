package vn.banking.auth.web;

import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import vn.banking.auth.customer.Customer;
import vn.banking.auth.customer.CustomerService;
import vn.banking.auth.web.dto.CustomerResponse;

/** ROLE_ADMIN only (enforced in SecurityConfig). */
@RestController
@RequestMapping("/auth/admin/customers")
public class AdminController {

    private final CustomerService customerService;

    public AdminController(CustomerService customerService) {
        this.customerService = customerService;
    }

    @GetMapping
    public List<CustomerResponse> list(@RequestParam(required = false) String q, Pageable pageable) {
        return customerService.listAll(q, org.springframework.data.domain.PageRequest.of(pageable.getPageNumber(), pageable.getPageSize())).stream().map(CustomerResponse::from).toList();
    }

    @PostMapping("/{id}/lock")
    public CustomerResponse lock(@PathVariable UUID id, Authentication authentication) {
        return CustomerResponse.from(customerService.setStatus(id, Customer.Status.LOCKED, actor(authentication)));
    }

    @PostMapping("/{id}/unlock")
    public CustomerResponse unlock(@PathVariable UUID id, Authentication authentication) {
        return CustomerResponse.from(customerService.setStatus(id, Customer.Status.ACTIVE, actor(authentication)));
    }

    private static UUID actor(Authentication authentication) {
        return UUID.fromString(((JwtAuthenticationToken) authentication).getToken().getSubject());
    }
}
