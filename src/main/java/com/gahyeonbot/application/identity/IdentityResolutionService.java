package com.gahyeonbot.application.identity;

import com.gahyeonbot.core.identity.ActorId;
import com.gahyeonbot.core.identity.IdentityProvider;
import com.gahyeonbot.entity.ExternalIdentity;
import com.gahyeonbot.entity.Principal;
import com.gahyeonbot.repository.ExternalIdentityRepository;
import com.gahyeonbot.repository.PrincipalRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

@Service
public class IdentityResolutionService {
    private final PrincipalRepository principalRepository;
    private final ExternalIdentityRepository externalIdentityRepository;
    private final TransactionTemplate transactions;

    public IdentityResolutionService(
            PrincipalRepository principalRepository,
            ExternalIdentityRepository externalIdentityRepository,
            PlatformTransactionManager transactionManager) {
        this.principalRepository = principalRepository;
        this.externalIdentityRepository = externalIdentityRepository;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    public ActorId resolveDiscord(long discordUserId, String displayName) {
        if (discordUserId <= 0) throw new IllegalArgumentException("Discord user ID가 올바르지 않습니다.");
        return resolve(
                IdentityProvider.DISCORD,
                Long.toString(discordUserId),
                discordUserId,
                displayName);
    }

    private ActorId resolve(
            IdentityProvider provider,
            String externalId,
            long compatiblePrincipalId,
            String displayName) {
        String safeName = displayName == null || displayName.isBlank() ? "unknown" : displayName.trim();
        var existing = externalIdentityRepository.findByProviderAndExternalId(provider, externalId);
        if (existing.isPresent()) return new ActorId(existing.get().getPrincipal().getId());

        try {
            ActorId created = transactions.execute(status -> {
                var raced = externalIdentityRepository.findByProviderAndExternalId(provider, externalId);
                if (raced.isPresent()) return new ActorId(raced.get().getPrincipal().getId());
                Principal principal = principalRepository.findById(compatiblePrincipalId)
                        .orElseGet(() -> principalRepository.save(Principal.builder()
                                .id(compatiblePrincipalId)
                                .displayName(safeName)
                                .build()));
                externalIdentityRepository.saveAndFlush(ExternalIdentity.builder()
                        .id(UUID.randomUUID().toString())
                        .principal(principal)
                        .provider(provider)
                        .externalId(externalId)
                        .build());
                return new ActorId(principal.getId());
            });
            if (created == null) throw new IllegalStateException("Identity transaction returned no result");
            return created;
        } catch (DataIntegrityViolationException race) {
            return externalIdentityRepository.findByProviderAndExternalId(provider, externalId)
                    .map(identity -> new ActorId(identity.getPrincipal().getId()))
                    .orElseThrow(() -> race);
        }
    }
}
