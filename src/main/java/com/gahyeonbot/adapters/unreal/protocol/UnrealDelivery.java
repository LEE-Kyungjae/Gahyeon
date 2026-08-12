package com.gahyeonbot.adapters.unreal.protocol;

public enum UnrealDelivery {
    DURABLE("durable"),
    COMMAND("command"),
    EPHEMERAL("ephemeral");

    private final String wireValue;

    UnrealDelivery(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }
}
