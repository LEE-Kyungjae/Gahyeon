package com.gahyeonbot.application.identity;

import com.gahyeonbot.core.identity.ActorId;
import com.gahyeonbot.core.identity.IdentityProvider;

public interface IdentityResolutionUseCase {
    ActorId resolveExternal(
            IdentityProvider provider,
            String externalId,
            String displayName,
            Long preferredActorId);
}
