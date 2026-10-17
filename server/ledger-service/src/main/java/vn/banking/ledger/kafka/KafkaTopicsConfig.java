package vn.banking.ledger.kafka;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

import vn.banking.events.Topics;

@Configuration
public class KafkaTopicsConfig {

    // No replication factor: the broker default applies (1 on compose, 3 on K8s)
    private static final int PARTITIONS = 3;

    @Bean
    NewTopic ledgerPosted() {
        return TopicBuilder.name(Topics.LEDGER_POSTED).partitions(PARTITIONS).build();
    }

    @Bean
    NewTopic ledgerRejected() {
        return TopicBuilder.name(Topics.LEDGER_REJECTED).partitions(PARTITIONS).build();
    }

    @Bean
    NewTopic transferDlq() {
        return TopicBuilder.name(Topics.TRANSFER_DLQ).partitions(PARTITIONS).build();
    }

    /**
     * 3 in-process retries with 1s backoff, then the poisoned record goes to transfer.dlq.
     */
    @Bean
    DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> kafkaTemplate) {
        var recoverer = new DeadLetterPublishingRecoverer(kafkaTemplate,
                (record, ex) -> new TopicPartition(Topics.TRANSFER_DLQ, record.partition()));
        return new DefaultErrorHandler(recoverer, new FixedBackOff(1000L, 3L));
    }
}
