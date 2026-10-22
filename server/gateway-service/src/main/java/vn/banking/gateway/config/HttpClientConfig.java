package vn.banking.gateway.config;

import java.time.Duration;

import org.springframework.cloud.gateway.config.HttpClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class HttpClientConfig {

    /**
     * Reactor Netty caches DNS answers for their TTL (600s from Docker's resolver), so after a
     * backend container came back on a new IP the gateway kept dialing the old one for ~10 min.
     * K8s Service IPs are stable, but docker compose reassigns container IPs on restart.
     */
    @Bean
    HttpClientCustomizer shortDnsCache() {
        return httpClient -> httpClient.resolver(spec -> spec.cacheMaxTimeToLive(Duration.ofSeconds(10)));
    }
}
