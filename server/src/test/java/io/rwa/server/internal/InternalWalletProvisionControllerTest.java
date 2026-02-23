package io.rwa.server.internal;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.rwa.server.common.CanonicalJsonService;
import io.rwa.server.config.RwaProperties;
import io.rwa.server.config.WebMvcConfig;
import io.rwa.server.idempotency.ApiIdempotencyRecord;
import io.rwa.server.idempotency.IdempotencyInterceptor;
import io.rwa.server.idempotency.IdempotencyRecordStatus;
import io.rwa.server.idempotency.IdempotencyService;
import io.rwa.server.security.HmacJwtVerifier;
import io.rwa.server.security.JwksJwtVerifier;
import io.rwa.server.wallet.ComplianceStatus;
import io.rwa.server.wallet.WalletService;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = InternalWalletProvisionController.class)
@Import({ WebMvcConfig.class, IdempotencyInterceptor.class, CanonicalJsonService.class })
class InternalWalletProvisionControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private WalletService walletService;

    @MockBean
    private IdempotencyService idempotencyService;

    @MockBean
    private RwaProperties properties;

    @MockBean
    private JwksJwtVerifier jwksJwtVerifier;

    @MockBean
    private HmacJwtVerifier hmacJwtVerifier;

    @BeforeEach
    void setUp() {
        when(properties.getServiceToken()).thenReturn("svc-token");
        when(properties.getServiceTokenHeader()).thenReturn("X-Service-Token");
        when(properties.getInternalPaths()).thenReturn(List.of("/internal/**"));

        RwaProperties.Auth auth = new RwaProperties.Auth();
        auth.setRequiredPaths(List.of("/trade/**", "/me/**", "/wallet/**"));
        when(properties.getAuth()).thenReturn(auth);
    }

    @Test
    @DisplayName("/internal/wallets/provision은 X-Service-Token이 없으면 401을 반환한다")
    void shouldRejectWhenServiceTokenIsMissing() throws Exception {
        mockMvc.perform(
            post("/internal/wallets/provision")
                .header("Idempotency-Key", "prov-k1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\":\"0f987f33-0ebf-4d3a-8761-f922f9d2104e\"}")
        )
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    @DisplayName("/internal/wallets/provision은 Idempotency-Key가 없으면 400을 반환한다")
    void shouldRejectWhenIdempotencyKeyIsMissing() throws Exception {
        mockMvc.perform(
            post("/internal/wallets/provision")
                .header("X-Service-Token", "svc-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\":\"0f987f33-0ebf-4d3a-8761-f922f9d2104e\"}")
        )
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("Idempotency-Key required"));
    }

    @Test
    @DisplayName("/internal/wallets/provision은 같은 Idempotency-Key에 다른 payload면 409를 반환한다")
    void shouldReturnConflictWhenSameKeyHasDifferentPayload() throws Exception {
        when(idempotencyService.tryInsertInProgress(anyString(), eq("prov-k2"), anyString())).thenAnswer(invocation -> {
            when(idempotencyService.find(anyString(), eq("prov-k2"))).thenReturn(Optional.of(
                new ApiIdempotencyRecord(
                    "POST /internal/wallets/provision",
                    "prov-k2",
                    "different-payload-hash",
                    IdempotencyRecordStatus.COMPLETED,
                    200,
                    null,
                    Instant.now(),
                    Instant.now()
                )
            ));
            return false;
        });

        mockMvc.perform(
            post("/internal/wallets/provision")
                .header("X-Service-Token", "svc-token")
                .header("Idempotency-Key", "prov-k2")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\":\"0f987f33-0ebf-4d3a-8761-f922f9d2104e\"}")
        )
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.message").value("Idempotency-Key reuse with different payload"));
    }

    @Test
    @DisplayName("동일 userId 재호출은 같은 address를 반환한다")
    void shouldReturnSameAddressForSameUserIdAcrossCalls() throws Exception {
        UUID userId = UUID.fromString("0f987f33-0ebf-4d3a-8761-f922f9d2104e");
        WalletService.WalletProvisionResult result = new WalletService.WalletProvisionResult(
            userId,
            "0x1234567890abcdef1234567890abcdef12345678",
            ComplianceStatus.PENDING
        );

        when(idempotencyService.tryInsertInProgress(anyString(), anyString(), anyString())).thenReturn(true);
        when(walletService.provisionWallet(eq(userId), any(), any())).thenReturn(result);

        String body = "{\"userId\":\"" + userId + "\",\"provider\":\"MEMBER\",\"externalUserId\":\"ext-11\"}";

        mockMvc.perform(
            post("/internal/wallets/provision")
                .header("X-Service-Token", "svc-token")
                .header("Idempotency-Key", "prov-k3")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
        )
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.address").value(result.address()));

        mockMvc.perform(
            post("/internal/wallets/provision")
                .header("X-Service-Token", "svc-token")
                .header("Idempotency-Key", "prov-k4")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
        )
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.address").value(result.address()));
    }
}
