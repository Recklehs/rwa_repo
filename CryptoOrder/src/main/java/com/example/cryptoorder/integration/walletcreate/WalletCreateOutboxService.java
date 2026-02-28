package com.example.cryptoorder.integration.walletcreate;

import com.example.cryptoorder.common.api.UpstreamServiceException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class WalletCreateOutboxService {

    private static final String EVENT_TYPE = "MemberSignupRequested";
    private static final int EVENT_VERSION = 1;
    private static final String SOURCE = "cryptoorder";

    private final WalletCreateIntegrationProperties properties;
    private final WalletCreateOutboxEventRepository outboxRepository;
    private final ObjectMapper objectMapper;

    public boolean isEnabled() {
        return properties.isEnabled();
    }

    @Transactional
    public void enqueue(UUID userId, String provider, String externalUserId) {
        if (userId == null) {
            throw new UpstreamServiceException("wallet-create 이벤트에 userId가 필요합니다.");
        }

        UUID eventId = UUID.randomUUID();
        WalletCreateRequestedEvent event = new WalletCreateRequestedEvent(
                eventId,
                EVENT_TYPE,
                EVENT_VERSION,
                Instant.now(),
                SOURCE,
                "signup-" + userId,
                new WalletCreateRequestedPayload(userId, provider, externalUserId)
        );

        String payloadJson;
        try {
            payloadJson = objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            throw new UpstreamServiceException("wallet-create 이벤트 직렬화에 실패했습니다.", e);
        }

        WalletCreateOutboxEvent outboxEvent = WalletCreateOutboxEvent.builder()
                .eventId(eventId)
                .topic(properties.getTopic())
                .partitionKey(userId.toString())
                .payloadJson(payloadJson)
                .status(WalletCreateOutboxStatus.PENDING)
                .attemptCount(0)
                .nextAttemptAt(LocalDateTime.now())
                .build();

        outboxRepository.save(outboxEvent);
    }
}
