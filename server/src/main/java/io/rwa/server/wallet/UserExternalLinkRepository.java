package io.rwa.server.wallet;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserExternalLinkRepository extends JpaRepository<UserExternalLinkEntity, Long> {
    List<UserExternalLinkEntity> findAllByUserId(UUID userId);
    Optional<UserExternalLinkEntity> findByProviderAndExternalUserId(String provider, String externalUserId);
    Optional<UserExternalLinkEntity> findByProviderAndUserId(String provider, UUID userId);
}
