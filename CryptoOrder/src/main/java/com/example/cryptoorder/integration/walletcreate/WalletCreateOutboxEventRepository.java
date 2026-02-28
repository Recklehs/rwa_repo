package com.example.cryptoorder.integration.walletcreate;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public interface WalletCreateOutboxEventRepository extends JpaRepository<WalletCreateOutboxEvent, UUID> {

    @Query("""
            select e
            from WalletCreateOutboxEvent e
            where e.status in :statuses
              and e.nextAttemptAt <= :now
            order by e.createdAt asc
            """)
    List<WalletCreateOutboxEvent> findDispatchable(
            @Param("statuses") List<WalletCreateOutboxStatus> statuses,
            @Param("now") LocalDateTime now,
            Pageable pageable
    );
}
