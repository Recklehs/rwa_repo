package com.example.cryptoorder.auth.repository;

import com.example.cryptoorder.Account.entity.User;
import com.example.cryptoorder.auth.entity.RefreshToken;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from RefreshToken t where t.tokenHash = :tokenHash and t.revoked = false")
    Optional<RefreshToken> findByTokenHashAndRevokedFalseForUpdate(@Param("tokenHash") String tokenHash);

    void deleteAllByUser(User user);
}
