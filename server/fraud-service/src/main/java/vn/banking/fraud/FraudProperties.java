package vn.banking.fraud;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "banking.fraud")
public record FraudProperties(
        long amountThreshold,
        long velocityWindowSeconds,
        long velocityMaxTransfers,
        long velocityMaxAmount) {
}
