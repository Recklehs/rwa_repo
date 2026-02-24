package io.rwa.server.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.rwa.server.wallet.WalletService;
import io.rwa.server.wallet.WalletService.WalletProvisionResult;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

@Component
public class WalletCreateEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(WalletCreateEventConsumer.class);

    private static final String REQUEST_EVENT_TYPE = "MemberSignupRequested";
    private static final String SUCCESS_EVENT_TYPE = "CustodyWalletProvisioned";
    private static final String FAILURE_EVENT_TYPE = "CustodyWalletProvisionFailed";
    private static final int EVENT_VERSION = 1;

    private final IntegrationInboxService inboxService;
    private final WalletService walletService;
    private final ObjectMapper objectMapper;
    private final KafkaTemplate<String, String> kafkaTemplate;

    @Value("${rwa.integration.wallet-create.result-topic:wallet-create-result}")
    private String resultTopic;

    public WalletCreateEventConsumer(
        IntegrationInboxService inboxService,
        WalletService walletService,
        ObjectMapper objectMapper,
        KafkaTemplate<String, String> kafkaTemplate
    ) {
        this.inboxService = inboxService;
        this.walletService = walletService;
        this.objectMapper = objectMapper;
        this.kafkaTemplate = kafkaTemplate;
    }

    @KafkaListener(
        topics = "${rwa.integration.wallet-create.topic:wallet-create}",
        groupId = "${rwa.integration.wallet-create.consumer-group:custody-consumer-group}",
        containerFactory = "walletCreateKafkaListenerContainerFactory"
    )
    public void consume(String message, Acknowledgment acknowledgment) {
        WalletCreateRequestedEvent event = parse(message);

        if (!REQUEST_EVENT_TYPE.equals(event.eventType())) {
            throw new IllegalArgumentException("Unsupported wallet-create eventType: " + event.eventType());
        }

        WalletCreateRequestedPayload payload = event.payload();
        if (payload == null || payload.userId() == null) {
            throw new IllegalArgumentException("wallet-create payload.userId is required");
        }

        boolean shouldProcess = inboxService.claimForProcessing(event.eventId(), event.eventType(), message);
        if (!shouldProcess) {
            acknowledgment.acknowledge();
            return;
        }

        try {
            WalletProvisionResult result = walletService.provisionWallet(
                payload.userId(),
                payload.provider(),
                payload.externalUserId()
            );

            publishResult(buildSuccessEvent(event, result));
            inboxService.markProcessed(event.eventId());
            acknowledgment.acknowledge();
        } catch (Exception e) {
            String reason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            inboxService.markFailed(event.eventId(), reason);
            log.warn("wallet-create consume failed. eventId={} userId={} reason={}", event.eventId(), payload.userId(), reason);
            throw e;
        }
    }

    private WalletCreateRequestedEvent parse(String message) {
        try {
            WalletCreateRequestedEvent event = objectMapper.readValue(message, WalletCreateRequestedEvent.class);
            if (event.eventId() == null) {
                throw new IllegalArgumentException("wallet-create eventId is required");
            }
            return event;
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid wallet-create event payload", e);
        }
    }

    private WalletCreateResultEvent buildSuccessEvent(WalletCreateRequestedEvent request, WalletProvisionResult result) {
        return new WalletCreateResultEvent(
            UUID.randomUUID(),
            SUCCESS_EVENT_TYPE,
            EVENT_VERSION,
            Instant.now(),
            "custody",
            request.correlationId(),
            request.eventId(),
            new WalletCreateResultEvent.WalletCreateResultPayload(
                result.userId(),
                result.address(),
                result.complianceStatus().name(),
                null
            )
        );
    }

    @SuppressWarnings("unused")
    private WalletCreateResultEvent buildFailureEvent(WalletCreateRequestedEvent request, String error) {
        WalletCreateRequestedPayload payload = request.payload();
        return new WalletCreateResultEvent(
            UUID.randomUUID(),
            FAILURE_EVENT_TYPE,
            EVENT_VERSION,
            Instant.now(),
            "custody",
            request.correlationId(),
            request.eventId(),
            new WalletCreateResultEvent.WalletCreateResultPayload(
                payload == null ? null : payload.userId(),
                null,
                null,
                error
            )
        );
    }

    private void publishResult(WalletCreateResultEvent event) {
        try {
            WalletCreateResultEvent safeEvent = event;
            String partitionKey = safeEvent.payload() != null && safeEvent.payload().userId() != null
                ? safeEvent.payload().userId().toString()
                : String.valueOf(safeEvent.requestEventId());
            kafkaTemplate.send(resultTopic, partitionKey, objectMapper.writeValueAsString(safeEvent)).get();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to publish wallet-create result event", e);
        }
    }
}
