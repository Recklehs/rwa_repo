package com.example.cryptoorder.common.idempotency;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
@Table(
        name = "idempotency_records",
        uniqueConstraints = @UniqueConstraint(name = "uk_idempotency_request_key", columnNames = "request_key")
)
public class IdempotencyRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "request_key", nullable = false, length = 255)
    private String requestKey;

    @Column(name = "payload_hash", nullable = false, length = 64)
    private String payloadHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private IdempotencyStatus status;

    @Column(name = "lock_expires_at", nullable = false)
    private LocalDateTime lockExpiresAt;

    @Column(name = "response_status")
    private Integer responseStatus;

    @Lob
    @Column(name = "response_body")
    private String responseBody;

    @Column(name = "response_encrypted", nullable = false)
    @Builder.Default
    private boolean responseEncrypted = false;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(nullable = false)
    private LocalDateTime updatedAt;

    public void markCompleted(int statusCode, String bodyJson, boolean encrypted) {
        this.status = IdempotencyStatus.COMPLETED;
        this.responseStatus = statusCode;
        this.responseBody = bodyJson;
        this.responseEncrypted = encrypted;
    }

    public void renewInProgress(LocalDateTime newLockExpiresAt) {
        this.status = IdempotencyStatus.IN_PROGRESS;
        this.lockExpiresAt = newLockExpiresAt;
        this.responseStatus = null;
        this.responseBody = null;
        this.responseEncrypted = false;
    }
}
