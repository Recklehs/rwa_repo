package com.example.cryptoorder.Account.entity;

import jakarta.validation.constraints.Pattern;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UuidGenerator;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
@Table(name = "users")
public class User {
    @Id
    @UuidGenerator(style = UuidGenerator.Style.TIME)
    @Column(name="user_uuid")
    private UUID id;

    @Column(nullable = false)
    private String userName;

    @Column(nullable = false)
    private LocalDate userAge;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    @Pattern(regexp = "^010-\\d{4}-\\d{4}$")
    private String phoneNumber;

    //계좌 리스트
    @OneToMany(mappedBy = "user")
    @Builder.Default
    private List<KRWAccount> krwAccounts = new ArrayList<>();

    @Column(nullable = false)
    @Builder.Default
    private boolean isActive = true;

    /**
     * 편의 메서드: UUID를 HEX 문자열(32자)로 변환하여 반환
     * JSON으로 나갈 때 "userHexId"라는 필드로 자동 포함됨 (Jackson 라이브러리 특성)
     */
    public String getUserHexId() {
        if (this.id == null)
            return null;
        // UUID의 - 기호를 제거하면 HEX 포맷과 동일합니다.
        return this.id.toString().replace("-", "");
    }

    public boolean closeUser() {
        this.isActive = false;
        return this.isActive;
    }

}
