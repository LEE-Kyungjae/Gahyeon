package com.gahyeonbot.adapters.unreal;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Binds authenticated Unreal event connections to Core session identity for sibling transports. */
public final class UnrealClientSessionRegistry {
    private final ConcurrentHashMap<String, Binding> byConnection = new ConcurrentHashMap<>();
    private final java.util.Set<String> openConnections = ConcurrentHashMap.newKeySet();
    private final int maximumConnections;
    private final int maximumConnectionsPerSession;

    public UnrealClientSessionRegistry() {
        this(64, 4);
    }

    public UnrealClientSessionRegistry(int maximumConnections, int maximumConnectionsPerSession) {
        if (maximumConnections < 1 || maximumConnectionsPerSession < 1
                || maximumConnectionsPerSession > maximumConnections) {
            throw new IllegalArgumentException("renderer connection limits are invalid");
        }
        this.maximumConnections = maximumConnections;
        this.maximumConnectionsPerSession = maximumConnectionsPerSession;
    }

    public synchronized BindingAdmission bind(String connectionId, Binding binding) {
        requireText(connectionId, "connectionId");
        Objects.requireNonNull(binding, "binding");
        if (byConnection.containsKey(connectionId)) {
            return BindingAdmission.CONNECTION_ALREADY_BOUND;
        }
        boolean openedHere = !openConnections.contains(connectionId);
        if (openedHere) {
            if (!open(connectionId)) return BindingAdmission.GLOBAL_CAPACITY;
        }
        boolean incompatible = byConnection.values().stream()
                .anyMatch(candidate -> candidate.sessionId().equals(binding.sessionId())
                        && !candidate.sameIdentity(binding));
        if (incompatible) {
            if (openedHere) openConnections.remove(connectionId);
            return BindingAdmission.INCOMPATIBLE_SESSION_IDENTITY;
        }
        long sessionConnections = byConnection.values().stream()
                .filter(candidate -> candidate.sessionId().equals(binding.sessionId()))
                .count();
        if (sessionConnections >= maximumConnectionsPerSession) {
            if (openedHere) openConnections.remove(connectionId);
            return BindingAdmission.SESSION_CAPACITY;
        }
        byConnection.put(connectionId, binding);
        return BindingAdmission.ACCEPTED;
    }

    public synchronized void unbind(String connectionId) {
        if (connectionId != null) {
            byConnection.remove(connectionId);
            openConnections.remove(connectionId);
        }
    }

    /** Reserves bounded transport capacity before a client.hello allocates session state. */
    public synchronized boolean open(String connectionId) {
        requireText(connectionId, "connectionId");
        if (openConnections.contains(connectionId)) return true;
        if (openConnections.size() >= maximumConnections) return false;
        openConnections.add(connectionId);
        return true;
    }

    public synchronized Optional<Binding> find(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return Optional.empty();
        Binding found = null;
        for (Binding candidate : byConnection.values()) {
            if (!candidate.sessionId().equals(sessionId)) continue;
            if (found != null && !found.sameIdentity(candidate)) return Optional.empty();
            found = candidate;
        }
        return Optional.ofNullable(found);
    }

    public synchronized boolean hasRendererForWorld(String worldId) {
        if (worldId == null || worldId.isBlank()) return false;
        return byConnection.values().stream()
                .anyMatch(binding -> binding.worldId().equals(worldId));
    }

    /** One binding per logical session rendering this exact character in the world. */
    public synchronized java.util.List<Binding> sessionsFor(String worldId, String characterId) {
        if (worldId == null || worldId.isBlank() || characterId == null || characterId.isBlank()) {
            return java.util.List.of();
        }
        return byConnection.values().stream()
                .filter(binding -> binding.worldId().equals(worldId)
                        && binding.characterId().equals(characterId))
                .collect(java.util.stream.Collectors.toMap(
                        Binding::sessionId, binding -> binding, (left, right) -> left))
                .values().stream().toList();
    }

    int connectionCount() {
        return byConnection.size();
    }

    int openConnectionCount() {
        return openConnections.size();
    }

    synchronized int sessionCount() {
        return (int) byConnection.values().stream()
                .map(Binding::sessionId)
                .distinct()
                .count();
    }

    public enum BindingAdmission {
        ACCEPTED,
        CONNECTION_ALREADY_BOUND,
        INCOMPATIBLE_SESSION_IDENTITY,
        GLOBAL_CAPACITY,
        SESSION_CAPACITY
    }

    public record Binding(
            String sessionId,
            String worldId,
            String installationId,
            String displayName,
            String characterId) {
        public Binding(String sessionId, String worldId, String installationId, String displayName) {
            this(sessionId, worldId, installationId, displayName, "gahyeon");
        }

        public Binding {
            requireText(sessionId, "sessionId");
            requireText(worldId, "worldId");
            requireText(installationId, "installationId");
            displayName = displayName == null || displayName.isBlank()
                    ? "Gahyeon user" : displayName.trim();
            characterId = new com.gahyeonbot.core.life.CharacterId(characterId).value();
        }

        private boolean sameIdentity(Binding other) {
            return sessionId.equals(other.sessionId)
                    && worldId.equals(other.worldId)
                    && installationId.equals(other.installationId)
                    && characterId.equals(other.characterId);
        }
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
    }
}
