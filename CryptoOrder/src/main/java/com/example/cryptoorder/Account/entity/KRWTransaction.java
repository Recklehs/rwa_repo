package com.example.cryptoorder.Account.entity;

import com.example.cryptoorder.Account.constant.TransactionStatus;
import com.example.cryptoorder.Account.constant.TransactionType;
import io.hypersistence.utils.hibernate.id.Tsid;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
@Table(name="krw_transactions")
public class KRWTransaction {
    @Id
    @Tsid
    private Long transactionId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "related_user_id", nullable = false)
    private User relatedUserId;

    @Column(nullable = false)
    private String sender;

    @Column(nullable = false)
    private String receiver;

    @Column(nullable = false)
    private Long amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TransactionStatus status;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TransactionType transactionType;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime transactionDate;


}
