package com.example.cryptoorder.Account.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
@Table(name="naver_point_histories")
public class NaverPointHistory {

    @Id
    @GeneratedValue(strategy= GenerationType.UUID)
    @Column(name = "point_history_id")
    private UUID pointHistoryId;

    @ManyToOne(fetch = FetchType.LAZY)
    // 식별관계인 계정의 PK를 외래키로 참조
    @JoinColumn(name = "account_id", nullable = false)
    private Account account;

    //포인트 적립 일시
    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime accrualDate;

    @NotNull
    @Column(nullable = false)
    private Long amount;

    @NotNull
    //포인트 적립 사유
    @Column(nullable = false)
    private String reason;



}
