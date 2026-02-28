package com.example.cryptoorder.integration.walletcreate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "wallet_create_outbox_events")
public class WalletCreateOutboxEvent {

    @Id
    @Column(name = "event_id", nullable = false, updatable = false)
    private UUID eventId;

    @Column(name = "topic", nullable = false, length = 120)
    private String topic;

    @Column(name = "partition_key", nullable = false, length = 120)
    private String partitionKey;

    @Column(name = "payload_json", nullable = false, columnDefinition = "TEXT")
    private String payloadJson;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private WalletCreateOutboxStatus status;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "next_attempt_at", nullable = false)
    private LocalDateTime nextAttemptAt;

    @Column(name = "last_error", columnDefinition = "TEXT")
    private String lastError;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Version
    @Column(name = "row_version", nullable = false)
    private long rowVersion;

    @Builder
    public WalletCreateOutboxEvent(
            UUID eventId,
            String topic,
            String partitionKey,
            String payloadJson,
            WalletCreateOutboxStatus status,
            int attemptCount,
            LocalDateTime nextAttemptAt,
            String lastError
    ) {
        this.eventId = eventId;
        this.topic = topic;
        this.partitionKey = partitionKey;
        this.payloadJson = payloadJson;
        this.status = status == null ? WalletCreateOutboxStatus.PENDING : status;
        this.attemptCount = attemptCount;
        this.nextAttemptAt = nextAttemptAt == null ? LocalDateTime.now() : nextAttemptAt;
        this.lastError = lastError;
    }

    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) {
            createdAt = now;
        }
        if (updatedAt == null) {
            updatedAt = now;
        }
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public void markSent() {
        this.status = WalletCreateOutboxStatus.SENT;
        this.lastError = null;
        this.nextAttemptAt = LocalDateTime.now();
    }

    public void markFailed(String error, long retryBackoffMs, int maxAttempts) {
        this.attemptCount += 1;
        this.lastError = error;
        if (this.attemptCount >= maxAttempts) {
            this.status = WalletCreateOutboxStatus.DEAD;
            this.nextAttemptAt = LocalDateTime.now();
            return;
        }
        this.status = WalletCreateOutboxStatus.FAILED;
        this.nextAttemptAt = LocalDateTime.now().plusNanos(retryBackoffMs * 1_000_000);
    }
}
