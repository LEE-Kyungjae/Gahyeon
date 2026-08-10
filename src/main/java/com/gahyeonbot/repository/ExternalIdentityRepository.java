package com.gahyeonbot.repository;

import com.gahyeonbot.core.identity.IdentityProvider;
import com.gahyeonbot.entity.ExternalIdentity;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ExternalIdentityRepository extends JpaRepository<ExternalIdentity, String> {
    @EntityGraph(attributePaths = "principal")
    Optional<ExternalIdentity> findByProviderAndExternalId(
            IdentityProvider provider,
            String externalId);
}
