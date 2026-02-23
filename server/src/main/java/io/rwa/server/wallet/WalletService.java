package io.rwa.server.wallet;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.rwa.server.common.ApiException;
import io.rwa.server.outbox.OutboxEventPublisher;
import io.rwa.server.tx.GasManagerService;
import jakarta.transaction.Transactional;
import java.security.SecureRandom;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.web3j.crypto.ECKeyPair;
import org.web3j.crypto.Keys;
import org.web3j.utils.Numeric;

@Service
public class WalletService {

    private static final Logger log = LoggerFactory.getLogger(WalletService.class);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String DEFAULT_PROVIDER = "MEMBER";
    private static final int MAX_EXTERNAL_USER_ID_LENGTH = 128;
    private static final String REQUIRED_FLYWAY_RANGE = "V1~V10";

    private final UserRepository userRepository;
    private final WalletRepository walletRepository;
    private final UserExternalLinkRepository userExternalLinkRepository;
    private final WalletCryptoService walletCryptoService;
    private final OutboxEventPublisher outboxEventPublisher;
    private final GasManagerService gasManagerService;
    private final ObjectMapper objectMapper;
    private final NamedParameterJdbcTemplate jdbcTemplate;

    public WalletService(
        UserRepository userRepository,
        WalletRepository walletRepository,
        UserExternalLinkRepository userExternalLinkRepository,
        WalletCryptoService walletCryptoService,
        OutboxEventPublisher outboxEventPublisher,
        GasManagerService gasManagerService,
        ObjectMapper objectMapper,
        NamedParameterJdbcTemplate jdbcTemplate
    ) {
        this.userRepository = userRepository;
        this.walletRepository = walletRepository;
        this.userExternalLinkRepository = userExternalLinkRepository;
        this.walletCryptoService = walletCryptoService;
        this.outboxEventPublisher = outboxEventPublisher;
        this.gasManagerService = gasManagerService;
        this.objectMapper = objectMapper;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional
    public SignupResult signup(String externalUserId, String provider) {
        String normalizedExternalUserId = normalizeExternalUserId(externalUserId);
        String normalizedProvider = normalizeProvider(provider);

        Optional<UserExternalLinkEntity> existingLink = userExternalLinkRepository.findByProviderAndExternalUserId(
            normalizedProvider,
            normalizedExternalUserId
        );
        if (existingLink.isPresent()) {
            return toExistingSignupResult(existingLink.get(), normalizedExternalUserId, normalizedProvider);
        }

        try {
            ECKeyPair keyPair = Keys.createEcKeyPair();
            String address = "0x" + Keys.getAddress(keyPair.getPublicKey());
            String privateKeyHex = Numeric.toHexStringNoPrefixZeroPadded(keyPair.getPrivateKey(), 64);

            Instant now = Instant.now();
            UUID userId = createUser(now);
            if (userId == null) {
                throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to get generated user_id from database");
            }

            WalletEntity wallet = new WalletEntity();
            wallet.setUserId(userId);
            wallet.setAddress(address.toLowerCase());
            wallet.setEncryptedPrivkey(walletCryptoService.encryptPrivateKey(privateKeyHex));
            wallet.setEncVersion(1);
            wallet.setCreatedAt(now);
            walletRepository.save(wallet);

            try {
                saveExternalLink(userId, normalizedProvider, normalizedExternalUserId, now);
            } catch (DataIntegrityViolationException duplicateException) {
                // concurrent signup with same external id can race; drop the new wallet/user and return winner mapping.
                cleanupCreatedUser(userId);
                Optional<UserExternalLinkEntity> winner = userExternalLinkRepository.findByProviderAndExternalUserId(
                    normalizedProvider,
                    normalizedExternalUserId
                );
                if (winner.isPresent()) {
                    return toExistingSignupResult(winner.get(), normalizedExternalUserId, normalizedProvider);
                }
                throw duplicateException;
            }

            outboxEventPublisher.publish(
                "User",
                userId.toString(),
                "UserSignedUp",
                userId.toString(),
                objectMapper.createObjectNode()
                    .put("userId", userId.toString())
                    .put("address", address.toLowerCase())
                    .put("externalUserId", normalizedExternalUserId)
                    .put("provider", normalizedProvider)
            );

            try {
                gasManagerService.ensureInitialGasGranted(address.toLowerCase());
            } catch (Exception e) {
                log.warn("Initial gas grant failed after signup. userId={} address={} reason={}", userId, address.toLowerCase(), e.getMessage());
            }

            return new SignupResult(userId, address.toLowerCase(), normalizedExternalUserId, normalizedProvider, true);
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to create custodial wallet: " + e.getMessage());
        }
    }

    @Transactional
    public WalletProvisionResult provisionWallet(UUID userId, String provider, String externalUserId) {
        if (userId == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "userId is required");
        }
        String normalizedProvider = normalizeProvider(provider);
        String normalizedExternalUserId = normalizeOptionalExternalUserId(externalUserId);
        Instant now = Instant.now();

        ensureUserExists(userId, now);

        if (normalizedExternalUserId != null) {
            ensureExternalLinkConsistency(userId, normalizedProvider, normalizedExternalUserId, now);
        }

        WalletCreationResult walletResult = ensureWalletForProvision(userId, now);
        if (walletResult.created()) {
            try {
                gasManagerService.ensureInitialGasGranted(walletResult.wallet().getAddress());
            } catch (Exception e) {
                log.warn(
                    "Initial gas grant failed after provisioning. userId={} address={} reason={}",
                    userId,
                    walletResult.wallet().getAddress(),
                    e.getMessage()
                );
            }
        }

        UserEntity user = getUser(userId);
        return new WalletProvisionResult(user.getUserId(), walletResult.wallet().getAddress(), user.getComplianceStatus());
    }

    private UUID createUser(Instant now) {
        Timestamp nowTs = Timestamp.from(now);
        MapSqlParameterSource common = new MapSqlParameterSource()
            .addValue("createdAt", nowTs, Types.TIMESTAMP)
            .addValue("complianceStatus", ComplianceStatus.PENDING.name())
            .addValue("complianceUpdatedAt", nowTs, Types.TIMESTAMP);

        try {
            return jdbcTemplate.queryForObject(
                """
                    INSERT INTO users (created_at, compliance_status, compliance_updated_at)
                    VALUES (:createdAt, :complianceStatus, :complianceUpdatedAt)
                    RETURNING user_id
                    """,
                common,
                UUID.class
            );
        } catch (BadSqlGrammarException e) {
            // Only fallback when RETURNING syntax is the incompatibility point.
            if (!isReturningCompatibilityIssue(e)) {
                throw usersSchemaMismatch(e);
            }

            UUID userId = generateUuidV7();
            try {
                int updated = jdbcTemplate.update(
                    """
                        INSERT INTO users (user_id, created_at, compliance_status, compliance_updated_at)
                        VALUES (:userId, :createdAt, :complianceStatus, :complianceUpdatedAt)
                        """,
                    new MapSqlParameterSource()
                        .addValue("userId", userId)
                        .addValue("createdAt", nowTs, Types.TIMESTAMP)
                        .addValue("complianceStatus", ComplianceStatus.PENDING.name())
                        .addValue("complianceUpdatedAt", nowTs, Types.TIMESTAMP)
                );
                if (updated != 1) {
                    throw usersSchemaMismatch(e);
                }
            } catch (BadSqlGrammarException fallbackException) {
                throw usersSchemaMismatch(fallbackException);
            }
            log.warn("Falling back to explicit user_id insert because RETURNING failed: {}", e.getMessage());
            return userId;
        }
    }

    private boolean isReturningCompatibilityIssue(BadSqlGrammarException e) {
        String message = rootMessage(e).toLowerCase();
        if (!message.contains("returning")) {
            return false;
        }

        Throwable root = rootCause(e);
        if (root instanceof SQLException sqlException) {
            String sqlState = sqlException.getSQLState();
            if (sqlState == null || sqlState.isBlank()) {
                return true;
            }
            // Only syntax/feature-not-supported SQL states should trigger RETURNING fallback.
            return "42601".equals(sqlState) || "0A000".equals(sqlState);
        }

        return true;
    }

    private ApiException usersSchemaMismatch(Exception e) {
        return new ApiException(
            HttpStatus.INTERNAL_SERVER_ERROR,
            "users table schema mismatch. run Flyway migrations (" + REQUIRED_FLYWAY_RANGE + "). cause=" + rootMessage(e)
        );
    }

    private Throwable rootCause(Throwable e) {
        Throwable current = e;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }

    private String rootMessage(Throwable e) {
        Throwable current = rootCause(e);
        return current.getMessage() == null ? e.getMessage() : current.getMessage();
    }

    private UUID generateUuidV7() {
        long unixMillis = Instant.now().toEpochMilli() & 0xFFFFFFFFFFFFL;
        long randA = RANDOM.nextInt(1 << 12);
        long msb = (unixMillis << 16) | (0x7L << 12) | randA;
        long lsb = (RANDOM.nextLong() & 0x3FFFFFFFFFFFFFFFL) | 0x8000000000000000L;
        return new UUID(msb, lsb);
    }

    private void saveExternalLink(UUID userId, String provider, String externalUserId, Instant now) {
        UserExternalLinkEntity link = new UserExternalLinkEntity();
        link.setProvider(provider);
        link.setExternalUserId(externalUserId);
        link.setUserId(userId);
        link.setCreatedAt(now);
        link.setUpdatedAt(now);
        userExternalLinkRepository.save(link);
    }

    private SignupResult toExistingSignupResult(UserExternalLinkEntity link, String externalUserId, String provider) {
        WalletEntity wallet = getWallet(link.getUserId());
        return new SignupResult(link.getUserId(), wallet.getAddress(), externalUserId, provider, false);
    }

    private void cleanupCreatedUser(UUID userId) {
        walletRepository.deleteById(userId);
        userRepository.deleteById(userId);
    }

    private String normalizeExternalUserId(String externalUserId) {
        if (externalUserId == null || externalUserId.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "externalUserId is required");
        }
        return validateExternalUserId(externalUserId.trim());
    }

    private String normalizeOptionalExternalUserId(String externalUserId) {
        if (externalUserId == null || externalUserId.isBlank()) {
            return null;
        }
        return validateExternalUserId(externalUserId.trim());
    }

    private String validateExternalUserId(String normalized) {
        if (normalized.length() > MAX_EXTERNAL_USER_ID_LENGTH) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "externalUserId is too long");
        }
        return normalized;
    }

    private String normalizeProvider(String provider) {
        if (provider == null || provider.isBlank()) {
            return DEFAULT_PROVIDER;
        }
        return provider.trim().toUpperCase();
    }

    public UserEntity getUser(UUID userId) {
        return userRepository.findById(userId)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "User not found: " + userId));
    }

    public WalletEntity getWallet(UUID userId) {
        return walletRepository.findById(userId)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Wallet not found for user: " + userId));
    }

    public WalletEntity getWalletOrProvisioned(UUID userId) {
        return walletRepository.findById(userId)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "WALLET_NOT_PROVISIONED"));
    }

    public String getAddress(UUID userId) {
        return getWallet(userId).getAddress();
    }

    public String decryptUserPrivateKey(UUID userId) {
        return walletCryptoService.decryptPrivateKey(getWallet(userId).getEncryptedPrivkey());
    }

    public Optional<ExternalUserWalletView> findByExternalUser(String provider, String externalUserId) {
        String normalizedProvider = normalizeProvider(provider);
        String normalizedExternalUserId = normalizeExternalUserId(externalUserId);
        return userExternalLinkRepository.findByProviderAndExternalUserId(normalizedProvider, normalizedExternalUserId)
            .map(link -> {
                UserEntity user = getUser(link.getUserId());
                WalletEntity wallet = getWallet(link.getUserId());
                return new ExternalUserWalletView(
                    user.getUserId(),
                    wallet.getAddress(),
                    normalizedExternalUserId,
                    normalizedProvider,
                    user.getComplianceStatus(),
                    user.getComplianceUpdatedAt()
                );
            });
    }

    public List<ExternalLinkView> listExternalLinks(UUID userId) {
        return userExternalLinkRepository.findAllByUserId(userId).stream()
            .map(link -> new ExternalLinkView(link.getProvider(), link.getExternalUserId()))
            .toList();
    }

    public void assertApproved(UUID userId) {
        UserEntity user = getUser(userId);
        if (user.getComplianceStatus() != ComplianceStatus.APPROVED) {
            throw new ApiException(HttpStatus.FORBIDDEN, "User compliance_status must be APPROVED");
        }
    }

    public record ExternalUserWalletView(
        UUID userId,
        String address,
        String externalUserId,
        String provider,
        ComplianceStatus complianceStatus,
        Instant complianceUpdatedAt
    ) {
    }

    private void ensureUserExists(UUID userId, Instant now) {
        jdbcTemplate.update(
            """
                INSERT INTO users (user_id, created_at, compliance_status, compliance_updated_at)
                VALUES (:userId, :createdAt, :status, :updatedAt)
                ON CONFLICT (user_id) DO NOTHING
                """,
            new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("createdAt", Timestamp.from(now), Types.TIMESTAMP)
                .addValue("status", ComplianceStatus.PENDING.name())
                .addValue("updatedAt", Timestamp.from(now), Types.TIMESTAMP)
        );
    }

    private WalletCreationResult ensureWalletForProvision(UUID userId, Instant now) {
        Optional<WalletEntity> existingWallet = walletRepository.findById(userId);
        if (existingWallet.isPresent()) {
            return new WalletCreationResult(existingWallet.get(), false);
        }
        return createWalletForProvision(userId, now);
    }

    private WalletCreationResult createWalletForProvision(UUID userId, Instant now) {
        try {
            ECKeyPair keyPair = Keys.createEcKeyPair();
            String address = "0x" + Keys.getAddress(keyPair.getPublicKey());
            String privateKeyHex = Numeric.toHexStringNoPrefixZeroPadded(keyPair.getPrivateKey(), 64);

            WalletEntity wallet = new WalletEntity();
            wallet.setUserId(userId);
            wallet.setAddress(address.toLowerCase());
            wallet.setEncryptedPrivkey(walletCryptoService.encryptPrivateKey(privateKeyHex));
            wallet.setEncVersion(1);
            wallet.setCreatedAt(now);
            WalletEntity saved = walletRepository.save(wallet);
            return new WalletCreationResult(saved, true);
        } catch (DataIntegrityViolationException duplicate) {
            WalletEntity existingWallet = walletRepository.findById(userId)
                .orElseThrow(() -> duplicate);
            return new WalletCreationResult(existingWallet, false);
        } catch (Exception e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to provision wallet: " + e.getMessage());
        }
    }

    private void ensureExternalLinkConsistency(UUID userId, String provider, String externalUserId, Instant now) {
        Optional<UserExternalLinkEntity> existing = userExternalLinkRepository.findByProviderAndExternalUserId(provider, externalUserId);
        if (existing.isPresent()) {
            if (!existing.get().getUserId().equals(userId)) {
                throw new ApiException(HttpStatus.CONFLICT, "externalUserId already linked to another user");
            }
            return;
        }

        Optional<UserExternalLinkEntity> linkByUser = userExternalLinkRepository.findByProviderAndUserId(provider, userId);
        if (linkByUser.isPresent() && !externalUserId.equals(linkByUser.get().getExternalUserId())) {
            throw new ApiException(HttpStatus.CONFLICT, "provider already linked with different externalUserId");
        }

        try {
            saveExternalLink(userId, provider, externalUserId, now);
        } catch (DataIntegrityViolationException e) {
            Optional<UserExternalLinkEntity> after = userExternalLinkRepository.findByProviderAndExternalUserId(provider, externalUserId);
            if (after.isPresent() && after.get().getUserId().equals(userId)) {
                return;
            }
            throw new ApiException(HttpStatus.CONFLICT, "externalUserId link conflict");
        }
    }

    public record ExternalLinkView(String provider, String externalUserId) {
    }

    public record WalletProvisionResult(UUID userId, String address, ComplianceStatus complianceStatus) {
    }

    private record WalletCreationResult(WalletEntity wallet, boolean created) {
    }
}
