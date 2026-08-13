package com.gahyeonbot.adapters.unreal;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;

class UnrealRuntimeMetricsTest {
    @Test
    void lifecycleGaugesReturnToZeroAfterLastRendererDisconnects() {
        var registry = new SimpleMeterRegistry();
        var metrics = new UnrealRuntimeMetrics(registry);
        Executor stalledExecutor = ignored -> { };
        var outbound = new UnrealEphemeralBroker(Clock.systemUTC(), stalledExecutor, 8, metrics);
        var clients = new UnrealClientSessionRegistry();
        var lifecycle = new UnrealPerceptionSessionTracker();
        var latest = new LatestUnrealPerceptionStore(Clock.systemUTC(), Duration.ofSeconds(10));
        metrics.bindOutbound(outbound);
        metrics.bindClientSessions(clients);
        metrics.bindPerceptionSessions(lifecycle);
        metrics.bindPerceptionStore(latest);

        outbound.subscribe("renderer-1", "session-1", ignored -> { });
        clients.bind("renderer-1", new UnrealClientSessionRegistry.Binding(
                "session-1", "world-1", "install-1", "Tester"));
        lifecycle.admit("session-1", "perception.voice.started", 1);
        latest.activateSession("session-1");
        latest.accept(new UnrealPerceptionEvent(
                "perception.user.pose", "session-1", "world-1", 1, Clock.systemUTC().instant(),
                Map.of("x", 1)));
        outbound.publish("session-1", "character.state", "metric-test", Map.of());

        assertGauge(registry, "gahyeon.unreal.outbound.renderers", 1);
        assertGauge(registry, "gahyeon.unreal.outbound.sessions", 1);
        assertGauge(registry, "gahyeon.unreal.outbound.queued", 1);
        assertGauge(registry, "gahyeon.unreal.client.bindings", 1);
        assertGauge(registry, "gahyeon.unreal.client.sessions", 1);
        assertGauge(registry, "gahyeon.unreal.perception.lifecycle.sessions", 1);
        assertGauge(registry, "gahyeon.unreal.perception.latest.sessions", 1);

        outbound.unsubscribeLastSession("renderer-1");
        clients.unbind("renderer-1");
        lifecycle.releaseSession("session-1");
        latest.releaseSession("session-1");

        assertGauge(registry, "gahyeon.unreal.outbound.renderers", 0);
        assertGauge(registry, "gahyeon.unreal.outbound.sessions", 0);
        assertGauge(registry, "gahyeon.unreal.outbound.queued", 0);
        assertGauge(registry, "gahyeon.unreal.client.bindings", 0);
        assertGauge(registry, "gahyeon.unreal.client.sessions", 0);
        assertGauge(registry, "gahyeon.unreal.perception.lifecycle.sessions", 0);
        assertGauge(registry, "gahyeon.unreal.perception.latest.sessions", 0);
    }

    private static void assertGauge(SimpleMeterRegistry registry, String name, double expected) {
        assertThat(registry.get(name).gauge().value()).isEqualTo(expected);
    }
}
