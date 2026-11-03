package vn.banking.auth.web.dto;

import java.time.Instant;
import java.util.UUID;

import vn.banking.auth.customer.Customer;

public record CustomerResponse(
        UUID id,
        String email,
        String fullName,
        String status,
        String role,
        Instant createdAt) {

    public static CustomerResponse from(Customer customer) {
        return new CustomerResponse(
                customer.getId(),
                customer.getEmail(),
                customer.getFullName(),
                customer.getStatus().name(),
                customer.getRole().name(),
                customer.getCreatedAt());
    }
}
