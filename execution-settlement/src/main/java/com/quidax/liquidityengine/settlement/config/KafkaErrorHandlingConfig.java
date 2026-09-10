package com.quidax.liquidityengine.settlement.config;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

import java.util.HashMap;
import java.util.Map;

/**
 * Retry + dead-letter configuration for the settlement consumer. A transient failure
 * (DB connection blip, deadlock from concurrent settlement on the same account) gets
 * `maxRetryAttempts` retries with backoff; a failure that persists past that — a genuinely
 * malformed event, or a bug — gets published to `settlements.completed.DLT` instead of
 * blocking the partition forever or silently dropping the trade. This is the "recovery"
 * half of the JD's "validation, routing, execution, settlement, reconciliation, cancellations,
 * retries, and recovery" — an alert on the DLT topic is the operational trigger for manual
 * investigation, which reconciliation-job also independently surfaces as a balance mismatch.
 */
@Configuration
public class KafkaErrorHandlingConfig {

    @Value("${settlement.max-retry-attempts:3}")
    private long maxRetryAttempts;

    @Value("${settlement.retry-backoff-ms:500}")
    private long retryBackoffMs;

    @Bean
    public ConsumerFactory<String, Object> consumerFactory(KafkaProperties kafkaProperties) {
        Map<String, Object> props = new HashMap<>(kafkaProperties.buildConsumerProperties(null));
        props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 50);
        return new DefaultKafkaConsumerFactory<>(props);
    }

    @Bean
    public DefaultErrorHandler settlementErrorHandler(KafkaTemplate<String, Object> kafkaTemplate) {
        var recoverer = new DeadLetterPublishingRecoverer(kafkaTemplate,
                (record, ex) -> new org.apache.kafka.common.TopicPartition(
                        record.topic() + ".DLT", record.partition()));

        var errorHandler = new DefaultErrorHandler(recoverer, new FixedBackOff(retryBackoffMs, maxRetryAttempts));

        // Don't waste retries on failures that will never succeed — a validation bug or a
        // deserialization error is a bug, not a transient condition; route it to the DLT
        // immediately so it doesn't block the partition for the full retry window.
        errorHandler.addNotRetryableExceptions(IllegalArgumentException.class);

        return errorHandler;
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, Object> kafkaListenerContainerFactory(
            ConsumerFactory<String, Object> consumerFactory,
            DefaultErrorHandler settlementErrorHandler) {
        var factory = new ConcurrentKafkaListenerContainerFactory<String, Object>();
        factory.setConsumerFactory(consumerFactory);
        factory.setCommonErrorHandler(settlementErrorHandler);
        return factory;
    }
}
