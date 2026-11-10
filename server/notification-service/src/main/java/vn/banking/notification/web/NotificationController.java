package vn.banking.notification.web;

import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;

import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import vn.banking.notification.service.NotificationService;
import vn.banking.notification.web.dto.NotificationResponse;

@RestController
@RequestMapping("/notifications")
public class NotificationController {

    private final NotificationService notificationService;

    public NotificationController(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @GetMapping
    public List<NotificationResponse> myNotifications(Authentication authentication, Pageable pageable) {
        UUID customerId = currentCustomer(authentication);
        return notificationService.listFor(customerId, org.springframework.data.domain.PageRequest.of(pageable.getPageNumber(), pageable.getPageSize())).stream()
                .map(NotificationResponse::from)
                .toList();
    }

    static UUID currentCustomer(Authentication authentication) {
        JwtAuthenticationToken jwtAuth = (JwtAuthenticationToken) authentication;
        return UUID.fromString(jwtAuth.getToken().getSubject());
    }
}
