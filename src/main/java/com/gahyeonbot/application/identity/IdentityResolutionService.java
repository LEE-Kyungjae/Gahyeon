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
import java.util.concurrent.ThreadLocalRandom;

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

    public ActorId resolveDesktop(String installationId, String displayName) {
        if (installationId == null || installationId.isBlank()) {
            throw new IllegalArgumentException("Desktop installation ID가 비어 있습니다.");
        }
        return resolve(
                IdentityProvider.DESKTOP,
                installationId.trim(),
                null,
                displayName);
    }

    private ActorId resolve(
            IdentityProvider provider,
            String externalId,
            Long compatiblePrincipalId,
            String displayName) {
        String safeName = displayName == null || displayName.isBlank() ? "unknown" : displayName.trim();
        var existing = externalIdentityRepository.findByProviderAndExternalId(provider, externalId);
        if (existing.isPresent()) return new ActorId(existing.get().getPrincipal().getId());

        try {
            ActorId created = transactions.execute(status -> {
                var raced = externalIdentityRepository.findByProviderAndExternalId(provider, externalId);
                if (raced.isPresent()) return new ActorId(raced.get().getPrincipal().getId());
                Principal principal = compatiblePrincipalId == null
                        ? principalRepository.save(Principal.builder()
                                .id(nextInternalPrincipalId())
                                .displayName(safeName)
                                .build())
                        : principalRepository.findById(compatiblePrincipalId)
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

    private long nextInternalPrincipalId() {
        // Discord snowflakes occupy the upper positive range. Keep generated local
        // identities in a small reserved range until UUID ActorId migration lands.
        for (int attempt = 0; attempt < 20; attempt++) {
            long candidate = ThreadLocalRandom.current().nextLong(1, 1_000_000_000L);
            if (!principalRepository.existsById(candidate)) return candidate;
        }
        throw new IllegalStateException("사용 가능한 내부 identity ID를 할당하지 못했습니다.");
    }
}
