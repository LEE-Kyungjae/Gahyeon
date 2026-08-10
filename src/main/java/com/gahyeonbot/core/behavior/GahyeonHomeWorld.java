package com.gahyeonbot.core.behavior;

import com.gahyeonbot.core.world.WorldActivity;
import com.gahyeonbot.core.world.WorldPosition;

import java.util.Map;
import java.util.Set;

public final class GahyeonHomeWorld {
    public static final String WORLD_ID = "gahyeon-home";

    private GahyeonHomeWorld() {}

    public static WorldDefinition definition() {
        return new WorldDefinition(WORLD_ID, Map.of(
                "bed", point("bed", "bedroom", 0, 0, 0, WorldActivity.SLEEP),
                "desk", point("desk", "workspace", 7, 0, -2, WorldActivity.SIT, WorldActivity.WORK),
                "bookshelf", point("bookshelf", "living_room", 3, 0, -6, WorldActivity.READ),
                "chair", point("chair", "living_room", -2, 0, -5, WorldActivity.SIT, WorldActivity.RELAX),
                "window", point("window", "living_room", 0, 0, -9, WorldActivity.LOOK_OUTSIDE),
                "room-center", point("room-center", "bedroom", 0, 0, -2, WorldActivity.IDLE, WorldActivity.WALK)));
    }

    private static InteractionPoint point(
            String id,
            String room,
            double x,
            double y,
            double z,
            WorldActivity... activities) {
        return new InteractionPoint(id, room, new WorldPosition(x, y, z), Set.of(activities));
    }
}
