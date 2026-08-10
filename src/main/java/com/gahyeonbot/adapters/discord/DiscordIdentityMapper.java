package com.gahyeonbot.adapters.discord;

import com.gahyeonbot.application.identity.IdentityResolutionService;
import com.gahyeonbot.core.identity.ActorId;
import com.gahyeonbot.core.identity.IdentityProvider;
import org.springframework.stereotype.Component;

/** The temporary identity boundary until external identities are persisted separately. */
@Component
public class DiscordIdentityMapper {
    private final IdentityResolutionService identities;

    public DiscordIdentityMapper(IdentityResolutionService identities) {
        this.identities = identities;
    }

    public ActorId toActorId(long discordUserId, String displayName) {
        if (discordUserId <= 0) throw new IllegalArgumentException("Discord user ID가 올바르지 않습니다.");
        return identities.resolveExternal(
                IdentityProvider.DISCORD,
                Long.toString(discordUserId),
                displayName,
                discordUserId);
    }
}
