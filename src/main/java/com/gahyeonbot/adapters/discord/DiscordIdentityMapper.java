package com.gahyeonbot.adapters.discord;

import com.gahyeonbot.core.identity.ActorId;
import org.springframework.stereotype.Component;

/** The temporary identity boundary until external identities are persisted separately. */
@Component
public class DiscordIdentityMapper {
    public ActorId toActorId(long discordUserId) {
        return new ActorId(discordUserId);
    }
}
