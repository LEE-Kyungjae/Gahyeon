package com.gahyeonbot.adapters.unreal;

import org.junit.jupiter.api.Test;

import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class UnrealClientSessionRegistryTest {
    @Test
    void isolatesRendererSessionsByWorldAndCharacter() {
        var registry = new UnrealClientSessionRegistry();
        registry.bind("gahyeon-1", new UnrealClientSessionRegistry.Binding(
                "session-g", "home", "install-g", "User", "gahyeon"));
        registry.bind("diana-1", new UnrealClientSessionRegistry.Binding(
                "session-d", "home", "install-d", "User", "diana"));
        registry.bind("gahyeon-2", new UnrealClientSessionRegistry.Binding(
                "session-g", "home", "install-g", "User", "gahyeon"));

        assertThat(registry.sessionsFor("home", "gahyeon"))
                .extracting(UnrealClientSessionRegistry.Binding::sessionId)
                .containsExactly("session-g");
        assertThat(registry.sessionsFor("home", "diana"))
                .extracting(UnrealClientSessionRegistry.Binding::sessionId)
                .containsExactly("session-d");
    }

    @Test
    void keepsSessionAvailableUntilItsLastConsistentConnectionLeaves() {
        var registry = new UnrealClientSessionRegistry();
        var binding = new UnrealClientSessionRegistry.Binding(
                "session-1", "world-1", "install-1", "User");
        assertThat(registry.bind("socket-1", binding))
                .isEqualTo(UnrealClientSessionRegistry.BindingAdmission.ACCEPTED);
        assertThat(registry.bind("socket-2", binding))
                .isEqualTo(UnrealClientSessionRegistry.BindingAdmission.ACCEPTED);

        registry.unbind("socket-1");
        assertThat(registry.find("session-1")).contains(binding);
        assertThat(registry.hasRendererForWorld("world-1")).isTrue();
        registry.unbind("socket-2");
        assertThat(registry.find("session-1")).isEmpty();
        assertThat(registry.hasRendererForWorld("world-1")).isFalse();
    }

    @Test
    void rejectsConflictingIdentityWithoutPoisoningTheExistingSession() {
        var registry = new UnrealClientSessionRegistry();
        var trusted = new UnrealClientSessionRegistry.Binding(
                "session-1", "world-1", "install-1", "User");
        assertThat(registry.bind("socket-1", trusted))
                .isEqualTo(UnrealClientSessionRegistry.BindingAdmission.ACCEPTED);
        assertThat(registry.bind("socket-2", new UnrealClientSessionRegistry.Binding(
                "session-1", "other-world", "other-install", "Attacker")))
                .isEqualTo(
                        UnrealClientSessionRegistry.BindingAdmission.INCOMPATIBLE_SESSION_IDENTITY);

        assertThat(registry.find("session-1")).contains(trusted);
        assertThat(registry.connectionCount()).isEqualTo(1);
    }

    @Test
    void rejectsConnectionIdRebinding() {
        var registry = new UnrealClientSessionRegistry();
        var binding = new UnrealClientSessionRegistry.Binding(
                "session-1", "world-1", "install-1", "User");
        assertThat(registry.bind("socket-1", binding))
                .isEqualTo(UnrealClientSessionRegistry.BindingAdmission.ACCEPTED);
        assertThat(registry.bind("socket-1", binding))
                .isEqualTo(UnrealClientSessionRegistry.BindingAdmission.CONNECTION_ALREADY_BOUND);
    }

    @Test
    void atomicallyBoundsConnectionsPerSessionAndGlobally() throws Exception {
        var registry = new UnrealClientSessionRegistry(4, 2);
        var binding = new UnrealClientSessionRegistry.Binding(
                "session-1", "world-1", "install-1", "User");
        var admitted = new AtomicInteger();
        try (var executor = Executors.newFixedThreadPool(8)) {
            for (int index = 0; index < 16; index++) {
                int connection = index;
                executor.submit(() -> {
                    if (registry.bind("socket-" + connection, binding)
                            == UnrealClientSessionRegistry.BindingAdmission.ACCEPTED) {
                        admitted.incrementAndGet();
                    }
                });
            }
            executor.shutdown();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(admitted).hasValue(2);
        assertThat(registry.connectionCount()).isEqualTo(2);

        assertThat(registry.bind("other-1", new UnrealClientSessionRegistry.Binding(
                "session-2", "world-2", "install-2", "User")))
                .isEqualTo(UnrealClientSessionRegistry.BindingAdmission.ACCEPTED);
        assertThat(registry.bind("other-2", new UnrealClientSessionRegistry.Binding(
                "session-3", "world-3", "install-3", "User")))
                .isEqualTo(UnrealClientSessionRegistry.BindingAdmission.ACCEPTED);
        assertThat(registry.bind("other-3", new UnrealClientSessionRegistry.Binding(
                "session-4", "world-4", "install-4", "User")))
                .isEqualTo(UnrealClientSessionRegistry.BindingAdmission.GLOBAL_CAPACITY);
    }

    @Test
    void releasesCapacityWhenRendererLeaves() {
        var registry = new UnrealClientSessionRegistry(1, 1);
        var first = new UnrealClientSessionRegistry.Binding(
                "session-1", "world-1", "install-1", "User");
        var second = new UnrealClientSessionRegistry.Binding(
                "session-2", "world-2", "install-2", "User");
        assertThat(registry.bind("socket-1", first))
                .isEqualTo(UnrealClientSessionRegistry.BindingAdmission.ACCEPTED);
        assertThat(registry.bind("socket-2", second))
                .isEqualTo(UnrealClientSessionRegistry.BindingAdmission.GLOBAL_CAPACITY);

        registry.unbind("socket-1");

        assertThat(registry.bind("socket-2", second))
                .isEqualTo(UnrealClientSessionRegistry.BindingAdmission.ACCEPTED);
    }

    @Test
    void countsPreHelloSocketsAgainstTheGlobalTransportBound() {
        var registry = new UnrealClientSessionRegistry(2, 1);

        assertThat(registry.open("pending-1")).isTrue();
        assertThat(registry.open("pending-1")).isTrue();
        assertThat(registry.open("pending-2")).isTrue();
        assertThat(registry.open("pending-3")).isFalse();
        assertThat(registry.openConnectionCount()).isEqualTo(2);
        assertThat(registry.connectionCount()).isZero();

        registry.unbind("pending-1");

        assertThat(registry.open("pending-3")).isTrue();
        assertThat(registry.openConnectionCount()).isEqualTo(2);
    }
}
