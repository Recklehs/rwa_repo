package com.example.cryptoorder.Account.service;

import com.example.cryptoorder.Account.entity.Account;
import com.example.cryptoorder.Account.entity.KRWAccount;
import com.example.cryptoorder.Account.entity.NaverPoint;
import com.example.cryptoorder.Account.entity.User;
import com.example.cryptoorder.Account.repository.AccountRepository;
import com.example.cryptoorder.Account.repository.KRWAccountBalanceRepository;
import com.example.cryptoorder.Account.repository.NaverPointRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class UserAccountServiceIntegrationTest {

    @Autowired
    private UserAccountService userAccountService;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private NaverPointRepository naverPointRepository;

    @Autowired
    private KRWAccountBalanceRepository krwAccountRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Test
    void createFullAccount_createsActiveUserAndPointWallet() {
        User user = userAccountService.createFullAccount(
                "홍길동",
                "010-1111-2222",
                LocalDate.of(1990, 1, 1),
                "honggildong",
                "password123"
        );

        assertThat(user.getId()).isNotNull();
        assertThat(user.isActive()).isTrue();

        Account account = accountRepository.findByUser(user).orElseThrow();
        assertThat(passwordEncoder.matches("password123", account.getUserLoginPw())).isTrue();

        NaverPoint point = naverPointRepository.findByAccount(account).orElseThrow();
        assertThat(point.getBalance()).isEqualTo(0L);
        assertThat(point.isActive()).isTrue();
    }

    @Test
    void createFullAccount_rejectsDuplicateLoginId() {
        userAccountService.createFullAccount(
                "사용자1",
                "010-3333-4444",
                LocalDate.of(1991, 2, 3),
                "duplicated-id",
                "password123"
        );

        assertThatThrownBy(() -> userAccountService.createFullAccount(
                "사용자2",
                "010-5555-6666",
                LocalDate.of(1992, 3, 4),
                "duplicated-id",
                "password456"
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("이미 사용 중인 로그인 아이디");
    }

    @Test
    void deleteUser_failsWhenKrwAccountHasPositiveBalance() {
        User user = userAccountService.createFullAccount(
                "테스터",
                "010-7777-8888",
                LocalDate.of(1993, 4, 5),
                "delete-user-test",
                "password123"
        );

        KRWAccount krwAccount = KRWAccount.builder()
                .user(user)
                .accountNumber("KRW-DELETE-0001")
                .ownerName("테스터")
                .balance(1_000L)
                .build();
        krwAccountRepository.save(krwAccount);

        assertThatThrownBy(() -> userAccountService.deleteUser(user.getId()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("non-zero balance");
    }
}
