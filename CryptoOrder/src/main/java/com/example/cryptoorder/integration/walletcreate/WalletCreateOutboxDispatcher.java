package com.example.cryptoorder.integration.walletcreate;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "integration.wallet-create", name = "enabled", havingValue = "true")
public class WalletCreateOutboxDispatcher {

    private final WalletCreateIntegrationProperties properties;
    private final WalletCreateOutboxEventRepository outboxRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;

    @Scheduled(fixedDelayString = "${integration.wallet-create.dispatch-interval-ms:1000}")
    public void dispatch() {
        List<WalletCreateOutboxEvent> events = outboxRepository.findDispatchable(
                List.of(WalletCreateOutboxStatus.PENDING, WalletCreateOutboxStatus.FAILED),
                LocalDateTime.now(),
                PageRequest.of(0, properties.getBatchSize())
        );

        for (WalletCreateOutboxEvent event : events) {
            try {
                kafkaTemplate.send(event.getTopic(), event.getPartitionKey(), event.getPayloadJson()).get();
                event.markSent();
                outboxRepository.save(event);
            } catch (Exception e) {
                String errorMessage = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                log.warn("wallet-create publish failed. eventId={} reason={}", event.getEventId(), errorMessage);
                event.markFailed(errorMessage, properties.getRetryBackoffMs(), properties.getMaxAttempts());
                outboxRepository.save(event);
            }
        }
    }
}
