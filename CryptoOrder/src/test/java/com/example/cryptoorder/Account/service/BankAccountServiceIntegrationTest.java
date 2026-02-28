package com.example.cryptoorder.Account.service;

import com.example.cryptoorder.Account.constant.TransactionStatus;
import com.example.cryptoorder.Account.constant.TransactionType;
import com.example.cryptoorder.Account.entity.KRWAccount;
import com.example.cryptoorder.Account.entity.KRWAccountHistory;
import com.example.cryptoorder.Account.entity.User;
import com.example.cryptoorder.Account.repository.KRWAccountBalanceRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class BankAccountServiceIntegrationTest {

    @Autowired
    private BankAccountService bankAccountService;

    @Autowired
    private UserAccountService userAccountService;

    @Autowired
    private KRWAccountBalanceRepository krwAccountRepository;

    @Test
    void deposit_increasesBalanceAndSavesHistory() {
        KRWAccount account = createKrwAccount("bank-deposit-user", "KRW-DEPOSIT-0001", 1_000L);

        KRWAccountHistory history = bankAccountService.deposit(account.getId(), 500L, "외부입금자");

        KRWAccount updated = krwAccountRepository.findById(account.getId()).orElseThrow();
        assertThat(updated.getBalance()).isEqualTo(1_500L);
        assertThat(history.getTransactionType()).isEqualTo(TransactionType.DEPOSIT);
        assertThat(history.getAmount()).isEqualTo(500L);
        assertThat(history.getBalanceAfterTransaction()).isEqualTo(1_500L);
        assertThat(history.getTransaction().getStatus()).isEqualTo(TransactionStatus.COMPLETED);
    }

    @Test
    void withdraw_failsWhenBalanceIsInsufficient() {
        KRWAccount account = createKrwAccount("bank-withdraw-user", "KRW-WITHDRAW-0001", 300L);

        assertThatThrownBy(() -> bankAccountService.withdraw(account.getId(), 500L, "외부계좌"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("잔액이 부족");
    }

    @Test
    void deposit_failsWhenAccountIsInactive() {
        KRWAccount account = createKrwAccount("inactive-user", "KRW-INACTIVE-0001", 0L);
        account.closeAccount();

        assertThatThrownBy(() -> bankAccountService.deposit(account.getId(), 100L, "외부입금자"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("비활성화된 계좌");
    }

    private KRWAccount createKrwAccount(String loginId, String accountNumber, Long initialBalance) {
        User user = userAccountService.createFullAccount(
                "계좌사용자",
                "010-9000-0000",
                LocalDate.of(1990, 1, 1),
                loginId,
                "password123"
        );

        KRWAccount account = KRWAccount.builder()
                .user(user)
                .accountNumber(accountNumber)
                .ownerName("계좌사용자")
                .balance(initialBalance)
                .build();
        return krwAccountRepository.save(account);
    }
}
