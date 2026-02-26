package io.rwa.server.wallet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.rwa.server.common.ApiException;
import io.rwa.server.outbox.OutboxEventPublisher;
import io.rwa.server.tx.GasManagerService;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

@ExtendWith(MockitoExtension.class)
class WalletServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private WalletRepository walletRepository;

    @Mock
    private UserExternalLinkRepository userExternalLinkRepository;

    @Mock
    private WalletCryptoService walletCryptoService;

    @Mock
    private OutboxEventPublisher outboxEventPublisher;

    @Mock
    private GasManagerService gasManagerService;

    @Mock
    private NamedParameterJdbcTemplate jdbcTemplate;

    private WalletService walletService;

    @BeforeEach
    void setUp() {
        walletService = new WalletService(
            userRepository,
            walletRepository,
            userExternalLinkRepository,
            walletCryptoService,
            outboxEventPublisher,
            gasManagerService,
            new ObjectMapper(),
            jdbcTemplate
        );
    }

    @Test
    @DisplayName("signup은 사용자/지갑을 저장하고 UserSignedUp 이벤트를 발행한다")
    void signupShouldPersistUserAndWalletAndPublishEvent() {
        // given: signup 수행에 필요한 암호화/저장 목 동작을 준비한다.
        when(userExternalLinkRepository.findByProviderAndExternalUserId("MEMBER", "ext-user-1"))
            .thenReturn(Optional.empty());
        when(jdbcTemplate.update(any(String.class), any(MapSqlParameterSource.class))).thenReturn(1);
        when(walletCryptoService.encryptPrivateKey(any())).thenReturn(new byte[] { 1, 2, 3 });
        when(walletRepository.save(any(WalletEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(userExternalLinkRepository.save(any(UserExternalLinkEntity.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));

        // when: 신규 가입을 실행한다.
        SignupResult result = walletService.signup("ext-user-1", null);

        // then: 사용자/지갑 저장과 outbox 이벤트 발행이 기대한 값으로 수행된다.
        ArgumentCaptor<String> privateKeyCaptor = ArgumentCaptor.forClass(String.class);
        verify(walletCryptoService).encryptPrivateKey(privateKeyCaptor.capture());
        assertThat(privateKeyCaptor.getValue()).matches("^[0-9a-fA-F]{64}$");
        assertThat(result.userId().version()).isEqualTo(7);
        assertThat(result.userId().variant()).isEqualTo(2);

        ArgumentCaptor<MapSqlParameterSource> userInsertCaptor = ArgumentCaptor.forClass(MapSqlParameterSource.class);
        verify(jdbcTemplate).update(any(String.class), userInsertCaptor.capture());
        assertThat(userInsertCaptor.getValue().getValue("userId")).isEqualTo(result.userId());

        ArgumentCaptor<WalletEntity> walletCaptor = ArgumentCaptor.forClass(WalletEntity.class);
        verify(walletRepository).save(walletCaptor.capture());
        WalletEntity savedWallet = walletCaptor.getValue();
        assertThat(savedWallet.getUserId()).isEqualTo(result.userId());
        assertThat(savedWallet.getAddress()).isEqualTo(result.address());
        assertThat(savedWallet.getAddress()).isEqualTo(savedWallet.getAddress().toLowerCase());
        assertThat(savedWallet.getEncryptedPrivkey()).containsExactly((byte) 1, (byte) 2, (byte) 3);
        assertThat(savedWallet.getEncVersion()).isEqualTo(1);
        assertThat(savedWallet.getCreatedAt()).isNotNull();

        ArgumentCaptor<UserExternalLinkEntity> linkCaptor = ArgumentCaptor.forClass(UserExternalLinkEntity.class);
        verify(userExternalLinkRepository).save(linkCaptor.capture());
        UserExternalLinkEntity savedLink = linkCaptor.getValue();
        assertThat(savedLink.getUserId()).isEqualTo(result.userId());
        assertThat(savedLink.getProvider()).isEqualTo("MEMBER");
        assertThat(savedLink.getExternalUserId()).isEqualTo("ext-user-1");
        assertThat(savedLink.getCreatedAt()).isNotNull();

        ArgumentCaptor<JsonNode> payloadCaptor = ArgumentCaptor.forClass(JsonNode.class);
        verify(outboxEventPublisher).publish(
            eq("User"),
            eq(result.userId().toString()),
            eq("UserSignedUp"),
            eq(result.userId().toString()),
            payloadCaptor.capture()
        );
        JsonNode payload = payloadCaptor.getValue();
        assertThat(payload.path("userId").asText()).isEqualTo(result.userId().toString());
        assertThat(payload.path("address").asText()).isEqualTo(result.address());
        assertThat(payload.path("externalUserId").asText()).isEqualTo("ext-user-1");
        assertThat(payload.path("provider").asText()).isEqualTo("MEMBER");
        assertThat(result.externalUserId()).isEqualTo("ext-user-1");
        assertThat(result.provider()).isEqualTo("MEMBER");
        assertThat(result.created()).isTrue();
        verify(gasManagerService).ensureInitialGasGranted(result.address());
    }

    @Test
    @DisplayName("signup은 동일 externalUserId가 이미 연결되어 있으면 기존 지갑을 반환한다")
    void signupShouldReturnExistingWalletWhenExternalUserAlreadyLinked() {
        // given: provider/externalUserId 매핑이 이미 존재한다.
        UUID userId = UUID.fromString("0199587d-78f9-7000-8000-000000000099");
        UserExternalLinkEntity link = new UserExternalLinkEntity();
        link.setUserId(userId);
        link.setProvider("MEMBER");
        link.setExternalUserId("ext-user-existing");
        when(userExternalLinkRepository.findByProviderAndExternalUserId("MEMBER", "ext-user-existing"))
            .thenReturn(Optional.of(link));

        WalletEntity wallet = new WalletEntity();
        wallet.setUserId(userId);
        wallet.setAddress("0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        when(walletRepository.findById(userId)).thenReturn(Optional.of(wallet));

        // when: 동일 externalUserId로 signup을 호출한다.
        SignupResult result = walletService.signup("ext-user-existing", "member");

        // then: 신규 생성 없이 기존 userId/address를 반환한다.
        assertThat(result.userId()).isEqualTo(userId);
        assertThat(result.address()).isEqualTo(wallet.getAddress());
        assertThat(result.externalUserId()).isEqualTo("ext-user-existing");
        assertThat(result.provider()).isEqualTo("MEMBER");
        assertThat(result.created()).isFalse();
        verify(jdbcTemplate, never()).update(any(String.class), any(MapSqlParameterSource.class));
        verify(walletRepository, never()).save(any(WalletEntity.class));
        verify(outboxEventPublisher, never()).publish(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("assertApproved는 사용자가 APPROVED가 아니면 403 예외를 던진다")
    void assertApprovedShouldThrowWhenUserIsNotApproved() {
        // given: 사용자의 compliance 상태가 APPROVED가 아니도록 준비한다.
        UUID userId = UUID.randomUUID();
        UserEntity user = new UserEntity();
        user.setUserId(userId);
        user.setComplianceStatus(ComplianceStatus.PENDING);
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        // when: 승인 상태 검증을 수행한다.
        // then: FORBIDDEN 예외가 발생하고 메시지에 APPROVED 요구사항이 포함된다.
        assertThatThrownBy(() -> walletService.assertApproved(userId))
            .isInstanceOfSatisfying(ApiException.class, ex -> {
                assertThat(ex.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
                assertThat(ex.getMessage()).contains("APPROVED");
            });
    }

    @Test
    @DisplayName("getWallet은 지갑이 없으면 404 예외를 던진다")
    void getWalletShouldThrowNotFoundWhenWalletIsMissing() {
        // given: 조회 대상 사용자의 지갑이 저장소에 없도록 준비한다.
        UUID userId = UUID.randomUUID();
        when(walletRepository.findById(userId)).thenReturn(Optional.empty());

        // when: 지갑 조회를 수행한다.
        // then: NOT_FOUND 예외가 발생한다.
        assertThatThrownBy(() -> walletService.getWallet(userId))
            .isInstanceOfSatisfying(ApiException.class, ex -> {
                assertThat(ex.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                assertThat(ex.getMessage()).contains("Wallet not found");
            });
    }

    @Test
    @DisplayName("signup은 users insert 영향 행 수가 1이 아니면 스키마 미스매치로 실패한다")
    void signupShouldFailWhenUserInsertAffectsUnexpectedRowCount() {
        when(userExternalLinkRepository.findByProviderAndExternalUserId("MEMBER", "ext-zero"))
            .thenReturn(Optional.empty());
        when(jdbcTemplate.update(any(String.class), any(MapSqlParameterSource.class))).thenReturn(0);

        assertThatThrownBy(() -> walletService.signup("ext-zero", null))
            .isInstanceOfSatisfying(ApiException.class, ex -> {
                assertThat(ex.getStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
                assertThat(ex.getMessage()).contains("users table schema mismatch");
            });

        verify(walletRepository, never()).save(any(WalletEntity.class));
    }

    @Test
    @DisplayName("signup은 users insert SQL grammar 오류에서 스키마 미스매치로 즉시 실패한다")
    void signupShouldFailFastForSqlGrammarErrorOnUserInsert() {
        when(userExternalLinkRepository.findByProviderAndExternalUserId("MEMBER", "ext-schema"))
            .thenReturn(Optional.empty());
        when(jdbcTemplate.update(any(String.class), any(MapSqlParameterSource.class)))
            .thenThrow(new BadSqlGrammarException(
                "createUser",
                "INSERT INTO users (user_id, ...)",
                new SQLException("relation \"users\" does not exist", "42P01")
            ));

        assertThatThrownBy(() -> walletService.signup("ext-schema", null))
            .isInstanceOfSatisfying(ApiException.class, ex -> {
                assertThat(ex.getStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
                assertThat(ex.getMessage()).contains("users table schema mismatch");
            });
    }

    @Test
    @DisplayName("provisionWallet은 external link 충돌 시 지갑 생성/가스지급 없이 409를 반환한다")
    void provisionWalletShouldRejectExternalLinkConflictBeforeWalletCreation() {
        UUID requestedUserId = UUID.fromString("dce34653-a5a3-4ac2-b4e6-f2d1245f28e9");
        UUID existingUserId = UUID.fromString("ad8c0d58-9f6f-46ba-833d-6ae42048c930");

        UserExternalLinkEntity existingLink = new UserExternalLinkEntity();
        existingLink.setUserId(existingUserId);
        existingLink.setProvider("MEMBER");
        existingLink.setExternalUserId("ext-conflict");

        when(userExternalLinkRepository.findByProviderAndExternalUserId("MEMBER", "ext-conflict"))
            .thenReturn(Optional.of(existingLink));
        when(userRepository.existsById(requestedUserId)).thenReturn(true);

        assertThatThrownBy(() -> walletService.provisionWallet(requestedUserId, "MEMBER", "ext-conflict"))
            .isInstanceOfSatisfying(ApiException.class, ex -> {
                assertThat(ex.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                assertThat(ex.getMessage()).contains("externalUserId");
            });

        verify(walletRepository, never()).save(any(WalletEntity.class));
        verify(gasManagerService, never()).ensureInitialGasGranted(any());
    }

    @Test
    @DisplayName("provisionWallet은 external link 삽입 경합에서 같은 userId 매핑이면 성공한다")
    void provisionWalletShouldSucceedWhenExternalLinkInsertRacesForSameUser() {
        UUID userId = UUID.fromString("97d9065f-17ed-4abf-9527-43789e40f93c");
        String provider = "MEMBER";
        String externalUserId = "ext-race";

        UserExternalLinkEntity linked = new UserExternalLinkEntity();
        linked.setProvider(provider);
        linked.setExternalUserId(externalUserId);
        linked.setUserId(userId);

        WalletEntity wallet = new WalletEntity();
        wallet.setUserId(userId);
        wallet.setAddress("0x1111111111111111111111111111111111111111");

        UserEntity user = new UserEntity();
        user.setUserId(userId);
        user.setComplianceStatus(ComplianceStatus.PENDING);

        when(jdbcTemplate.update(any(String.class), any(MapSqlParameterSource.class)))
            .thenReturn(0);
        when(userExternalLinkRepository.findByProviderAndExternalUserId(provider, externalUserId))
            .thenReturn(Optional.empty(), Optional.of(linked));
        when(userExternalLinkRepository.findByProviderAndUserId(provider, userId))
            .thenReturn(Optional.empty());
        when(userRepository.existsById(userId)).thenReturn(true);
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(walletRepository.findById(userId)).thenReturn(Optional.of(wallet));

        WalletService.WalletProvisionResult result = walletService.provisionWallet(userId, provider, externalUserId);

        assertThat(result.userId()).isEqualTo(userId);
        assertThat(result.address()).isEqualTo(wallet.getAddress());
        assertThat(result.complianceStatus()).isEqualTo(ComplianceStatus.PENDING);
        verify(walletRepository, never()).save(any(WalletEntity.class));
        verify(gasManagerService, never()).ensureInitialGasGranted(any());
    }
}
