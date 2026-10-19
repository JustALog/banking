package vn.banking.transfer.partner;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "banking.partner")
public record PartnerProperties(
        String baseUrl,
        String executePath) {
}
