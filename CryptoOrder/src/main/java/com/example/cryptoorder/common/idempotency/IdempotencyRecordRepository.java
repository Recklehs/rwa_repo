package com.example.cryptoorder.common.idempotency;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;

public interface IdempotencyRecordRepository extends JpaRepository<IdempotencyRecord, UUID> {
    Optional<IdempotencyRecord> findByRequestKey(String requestKey);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from IdempotencyRecord i where i.requestKey = :requestKey")
    Optional<IdempotencyRecord> findByRequestKeyWithLock(@Param("requestKey") String requestKey);
}
