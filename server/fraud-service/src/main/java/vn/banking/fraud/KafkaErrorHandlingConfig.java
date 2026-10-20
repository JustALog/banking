package vn.banking.fraud;

import org.apache.kafka.common.TopicPartition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

import vn.banking.events.Topics;

@Configuration
public class KafkaErrorHandlingConfig {

    /**
     * Same policy as ledger-service: 3 in-process retries with 1s backoff, then the poisoned record
     * goes to transfer.dlq (Spring's default would retry 9 times and silently drop it).
     */
    @Bean
    DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> kafkaTemplate) {
        var recoverer = new DeadLetterPublishingRecoverer(kafkaTemplate,
                (record, ex) -> new TopicPartition(Topics.TRANSFER_DLQ, record.partition()));
        return new DefaultErrorHandler(recoverer, new FixedBackOff(1000L, 3L));
    }
}
