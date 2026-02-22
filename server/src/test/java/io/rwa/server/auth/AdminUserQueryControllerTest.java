package io.rwa.server.auth;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.rwa.server.config.RwaProperties;
import io.rwa.server.idempotency.IdempotencyInterceptor;
import io.rwa.server.wallet.ComplianceStatus;
import io.rwa.server.wallet.WalletService;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = AdminUserQueryController.class)
class AdminUserQueryControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private WalletService walletService;

    @MockBean
    private IdempotencyInterceptor idempotencyInterceptor;

    @MockBean
    private RwaProperties properties;

    @BeforeEach
    void setUp() throws Exception {
        doReturn(true).when(idempotencyInterceptor).preHandle(any(), any(), any());
        when(properties.getAdminApiToken()).thenReturn("admin-secret-token");
    }

    @Test
    @DisplayName("관리자 토큰이 없으면 /admin/users/by-external 요청은 401을 반환한다")
    void shouldRejectWhenAdminTokenMissing() throws Exception {
        mockMvc.perform(get("/admin/users/by-external").param("externalUserId", "ext-1"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.status").value(401))
            .andExpect(jsonPath("$.message").value("Invalid admin token"));

        verify(walletService, never()).findByExternalUser(any(), any());
    }

    @Test
    @DisplayName("유효한 관리자 토큰이면 externalUserId로 user/address를 조회한다")
    void shouldReturnLinkedWalletWithValidToken() throws Exception {
        UUID userId = UUID.fromString("7f2edf86-4c41-4104-ab4b-469f2d16f83e");
        WalletService.ExternalUserWalletView view = new WalletService.ExternalUserWalletView(
            userId,
            "0x1234567890abcdef1234567890abcdef12345678",
            "ext-1",
            "MEMBER",
            ComplianceStatus.APPROVED,
            Instant.parse("2026-02-22T12:00:00Z")
        );
        when(walletService.findByExternalUser("MEMBER", "ext-1")).thenReturn(Optional.of(view));

        mockMvc.perform(
            get("/admin/users/by-external")
                .param("externalUserId", "ext-1")
                .header("X-Admin-Token", "admin-secret-token")
        )
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.userId").value(userId.toString()))
            .andExpect(jsonPath("$.address").value(view.address()))
            .andExpect(jsonPath("$.provider").value("MEMBER"))
            .andExpect(jsonPath("$.externalUserId").value("ext-1"))
            .andExpect(jsonPath("$.complianceStatus").value("APPROVED"));

        verify(walletService).findByExternalUser(eq("MEMBER"), eq("ext-1"));
    }
}
