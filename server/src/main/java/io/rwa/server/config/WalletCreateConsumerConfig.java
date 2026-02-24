package io.rwa.server.config;

import org.apache.kafka.common.TopicPartition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.util.backoff.FixedBackOff;

@Configuration
public class WalletCreateConsumerConfig {

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String> walletCreateKafkaListenerContainerFactory(
        ConsumerFactory<String, String> consumerFactory,
        DefaultErrorHandler walletCreateErrorHandler,
        @Value("${rwa.integration.wallet-create.concurrency:3}") int concurrency
    ) {
        ConcurrentKafkaListenerContainerFactory<String, String> factory = new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        factory.setConcurrency(Math.max(1, concurrency));
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
        factory.setCommonErrorHandler(walletCreateErrorHandler);
        return factory;
    }

    @Bean
    public DefaultErrorHandler walletCreateErrorHandler(
        KafkaTemplate<Object, Object> kafkaTemplate,
        @Value("${rwa.integration.wallet-create.dlq-topic:wallet-create.dlq}") String dlqTopic,
        @Value("${rwa.integration.wallet-create.retry-interval-ms:1000}") long retryIntervalMs,
        @Value("${rwa.integration.wallet-create.retry-max-attempts:10}") long retryMaxAttempts
    ) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
            kafkaTemplate,
            (record, ex) -> new TopicPartition(dlqTopic, record.partition())
        );

        long maxRetries = Math.max(0, retryMaxAttempts - 1);
        FixedBackOff backOff = new FixedBackOff(Math.max(1, retryIntervalMs), maxRetries);
        return new DefaultErrorHandler(recoverer, backOff);
    }
}
