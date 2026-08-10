package com.gahyeonbot.adapters.discord;

import com.gahyeonbot.application.identity.IdentityResolutionService;
import com.gahyeonbot.core.identity.ActorId;
import org.springframework.stereotype.Component;

/** The temporary identity boundary until external identities are persisted separately. */
@Component
public class DiscordIdentityMapper {
    private final IdentityResolutionService identities;

    public DiscordIdentityMapper(IdentityResolutionService identities) {
        this.identities = identities;
    }

    public ActorId toActorId(long discordUserId, String displayName) {
        return identities.resolveDiscord(discordUserId, displayName);
    }
}
