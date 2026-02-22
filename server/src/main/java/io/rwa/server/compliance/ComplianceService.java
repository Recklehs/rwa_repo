package io.rwa.server.compliance;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.rwa.server.outbox.OutboxEventPublisher;
import io.rwa.server.common.ApiException;
import io.rwa.server.tx.GasManagerService;
import io.rwa.server.wallet.ComplianceStatus;
import io.rwa.server.wallet.UserEntity;
import io.rwa.server.wallet.UserRepository;
import io.rwa.server.wallet.WalletService;
import jakarta.transaction.Transactional;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class ComplianceService {

    private static final Logger log = LoggerFactory.getLogger(ComplianceService.class);

    private final UserRepository userRepository;
    private final OutboxEventPublisher outboxEventPublisher;
    private final WalletService walletService;
    private final GasManagerService gasManagerService;
    private final ObjectMapper objectMapper;

    public ComplianceService(
        UserRepository userRepository,
        OutboxEventPublisher outboxEventPublisher,
        WalletService walletService,
        GasManagerService gasManagerService,
        ObjectMapper objectMapper
    ) {
        this.userRepository = userRepository;
        this.outboxEventPublisher = outboxEventPublisher;
        this.walletService = walletService;
        this.gasManagerService = gasManagerService;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public UserEntity approve(UUID userId) {
        UserEntity user = userRepository.findById(userId)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "User not found: " + userId));
        user.setComplianceStatus(ComplianceStatus.APPROVED);
        user.setComplianceUpdatedAt(Instant.now());
        UserEntity saved = userRepository.save(user);

        outboxEventPublisher.publish(
            "User",
            userId.toString(),
            "ComplianceApproved",
            userId.toString(),
            objectMapper.createObjectNode().put("userId", userId.toString()).put("status", "APPROVED")
        );

        try {
            gasManagerService.ensureInitialGasGranted(walletService.getAddress(userId));
        } catch (Exception e) {
            log.warn("Initial gas grant failed after compliance approval. userId={} reason={}", userId, e.getMessage());
        }
        return saved;
    }

    @Transactional
    public UserEntity revoke(UUID userId) {
        UserEntity user = userRepository.findById(userId)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "User not found: " + userId));
        user.setComplianceStatus(ComplianceStatus.REVOKED);
        user.setComplianceUpdatedAt(Instant.now());
        UserEntity saved = userRepository.save(user);

        outboxEventPublisher.publish(
            "User",
            userId.toString(),
            "ComplianceRevoked",
            userId.toString(),
            objectMapper.createObjectNode().put("userId", userId.toString()).put("status", "REVOKED")
        );
        return saved;
    }

    public List<UserEntity> findByStatus(ComplianceStatus status) {
        return userRepository.findByComplianceStatus(status);
    }
}
