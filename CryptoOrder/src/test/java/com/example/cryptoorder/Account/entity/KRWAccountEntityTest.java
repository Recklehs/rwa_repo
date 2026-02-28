package com.example.cryptoorder.Account.entity;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KRWAccountEntityTest {

    @Test
    void closeAccount_allowsOnlyZeroBalance() {
        KRWAccount account = KRWAccount.builder()
                .accountNumber("KRW-ENTITY-0001")
                .ownerName("tester")
                .balance(0L)
                .build();

        account.closeAccount();

        assertThat(account.isActive()).isFalse();
    }

    @Test
    void closeAccount_rejectsNegativeBalance() {
        KRWAccount account = KRWAccount.builder()
                .accountNumber("KRW-ENTITY-0002")
                .ownerName("tester")
                .balance(-1L)
                .build();

        assertThatThrownBy(account::closeAccount)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("잔액이 0원이 아닌");
    }
}
