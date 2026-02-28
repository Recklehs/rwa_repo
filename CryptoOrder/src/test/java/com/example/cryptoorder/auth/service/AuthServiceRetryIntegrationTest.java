package com.example.cryptoorder.auth.service;

import com.example.cryptoorder.Account.repository.AccountRepository;
import com.example.cryptoorder.auth.dto.AuthSignupRequest;
import com.example.cryptoorder.common.api.UpstreamServiceException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
@TestPropertySource(properties = {
        "auth.mode=JWKS",
        "auth.allow-ephemeral-keys=true"
})
class AuthServiceRetryIntegrationTest {

    @Autowired
    private AuthService authService;

    @Autowired
    private AccountRepository accountRepository;

    @MockBean
    private CustodyProvisionClient custodyProvisionClient;

    @Test
    void signup_retryAfterProvisionFailure_reusesExistingAccount() {
        AuthSignupRequest request = new AuthSignupRequest(
                "재시도회원",
                "010-4545-4545",
                LocalDate.of(1991, 4, 5),
                "retry-member",
                "password123",
                "MEMBER",
                null
        );

        doThrow(new UpstreamServiceException("provision failed"))
                .doNothing()
                .when(custodyProvisionClient)
                .provisionWallet(any(), eq("MEMBER"), isNull());

        assertThatThrownBy(() -> authService.signup(request))
                .isInstanceOf(UpstreamServiceException.class);
        long accountCountAfterFirstAttempt = accountRepository.count();

        TokenPair pair = authService.signup(request);

        assertThat(pair.accessToken()).isNotBlank();
        assertThat(pair.refreshToken()).isNotBlank();
        assertThat(accountRepository.count()).isEqualTo(accountCountAfterFirstAttempt);
        verify(custodyProvisionClient, times(2)).provisionWallet(any(), eq("MEMBER"), isNull());
    }
}
