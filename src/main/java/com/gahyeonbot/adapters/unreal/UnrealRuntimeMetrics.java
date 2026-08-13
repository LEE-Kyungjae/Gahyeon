package com.gahyeonbot.adapters.unreal;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public final class UnrealRuntimeMetrics {
    private static final Set<String> MESSAGE_TYPES = Set.of(
            "client.hello", "client.ack", "client.ping",
            "interaction.text.submitted", "perception.transcript.final",
            "perception.transcript.partial", "perception.voice.started",
            "perception.voice.ended", "perception.user.pose",
            "interaction.generation.advanced", "character.action.completed");

    private final MeterRegistry registry;
    private final AtomicInteger connections = new AtomicInteger();
    private final Set<String> connectionIds = ConcurrentHashMap.newKeySet();
    private final AtomicInteger streamingSttConnections = new AtomicInteger();
    private final Set<String> streamingSttConnectionIds = ConcurrentHashMap.newKeySet();

    public UnrealRuntimeMetrics(MeterRegistry registry) {
        this.registry = registry;
        Gauge.builder("gahyeon.unreal.websocket.connections", connections, AtomicInteger::get)
                .register(registry);
        Gauge.builder("gahyeon.unreal.stt.streaming.connections",
                        streamingSttConnections, AtomicInteger::get)
                .register(registry);
    }

    void connected(String connectionId) {
        if (connectionIds.add(connectionId)) connections.incrementAndGet();
    }

    void disconnected(String connectionId) {
        if (connectionIds.remove(connectionId)) connections.decrementAndGet();
    }

    void rendererConnectionRejected(String reason) {
        counter("gahyeon.unreal.websocket.connection.rejected",
                "reason", safeCode(reason)).increment();
    }

    void streamingSttConnected(String connectionId) {
        if (streamingSttConnectionIds.add(connectionId)) streamingSttConnections.incrementAndGet();
    }

    void streamingSttDisconnected(String connectionId) {
        if (streamingSttConnectionIds.remove(connectionId)) streamingSttConnections.decrementAndGet();
    }

    void streamingSttConnectionRejected(String reason) {
        counter("gahyeon.unreal.stt.streaming.connection.rejected",
                "reason", safeCode(reason)).increment();
    }

    void streamingSttOutboundDetached(String reason) {
        String safe = Set.of("queue_full", "executor_rejected", "delivery_failed")
                .contains(reason) ? reason : "other";
        counter("gahyeon.unreal.stt.streaming.outbound.detached", "reason", safe).increment();
    }

    void received(String type) {
        counter("gahyeon.unreal.websocket.messages", "direction", "in", "type", safeType(type)).increment();
    }

    void protocolError(String code) {
        counter("gahyeon.unreal.websocket.protocol.errors", "code", safeCode(code)).increment();
    }

    void replayed(int count) {
        if (count > 0) counter("gahyeon.unreal.websocket.replay.messages").increment(count);
    }

    void command(UnrealCommandDispatcher.DispatchResult result) {
        counter("gahyeon.unreal.cognition.commands", "result", result.name().toLowerCase()).increment();
    }

    void perception(String type) {
        counter("gahyeon.unreal.perception.events", "type", safeType(type)).increment();
    }

    void perceptionIgnored(String reason) {
        counter("gahyeon.unreal.perception.ignored", "reason", safeCode(reason)).increment();
    }

    void processing(String type, long elapsedNanos) {
        Timer.builder("gahyeon.unreal.websocket.message.processing")
                .tag("type", safeType(type))
                .register(registry)
                .record(Math.max(0, elapsedNanos), TimeUnit.NANOSECONDS);
    }

    void cognitionFirstDelta(long elapsedNanos) {
        registry.timer("gahyeon.unreal.cognition.first.delta").record(
                Math.max(0, elapsedNanos), TimeUnit.NANOSECONDS);
    }

    void cognitionFirstSentence(long elapsedNanos) {
        registry.timer("gahyeon.unreal.cognition.first.sentence").record(
                Math.max(0, elapsedNanos), TimeUnit.NANOSECONDS);
    }

    void cognitionCancelled() {
        registry.counter("gahyeon.unreal.cognition.cancelled").increment();
    }

    void generationAdvanced(String reason) {
        counter("gahyeon.unreal.generation.advanced", "reason", safeCode(reason)).increment();
    }

    void ttsFirstSegment(long elapsedNanos) {
        registry.timer("gahyeon.unreal.tts.first.segment").record(
                Math.max(0, elapsedNanos), TimeUnit.NANOSECONDS);
    }

    void ttsSegment(long elapsedNanos) {
        registry.timer("gahyeon.unreal.tts.segment").record(
                Math.max(0, elapsedNanos), TimeUnit.NANOSECONDS);
    }

    void ttsFailure(String code) {
        counter("gahyeon.unreal.tts.failures", "code", safeCode(code)).increment();
    }

    void ttsCancelled() {
        registry.counter("gahyeon.unreal.tts.cancelled").increment();
    }

    void visemeTimeline(String source) {
        String safe = Set.of("provider", "heuristic", "amplitude").contains(source)
                ? source : "other";
        counter("gahyeon.unreal.viseme.timeline", "source", safe).increment();
    }

    void visemeAlignment(String source, String result, long elapsedNanos) {
        String safeSource = Set.of("provider", "heuristic", "unavailable").contains(source)
                ? source : "other";
        String safeResult = Set.of("success", "empty", "audio_invalid", "contract_invalid", "failure")
                .contains(result) ? result : "other";
        Timer.builder("gahyeon.unreal.viseme.alignment")
                .tag("source", safeSource)
                .tag("result", safeResult)
                .register(registry)
                .record(Math.max(0, elapsedNanos), TimeUnit.NANOSECONDS);
    }

    void sttRequest(String result, long elapsedNanos) {
        Timer.builder("gahyeon.unreal.stt.request")
                .tag("result", safeCode(result))
                .register(registry)
                .record(Math.max(0, elapsedNanos), TimeUnit.NANOSECONDS);
    }

    void streamingSttEvent(String type) {
        String safe = Set.of("started", "partial", "final", "error", "cancelled")
                .contains(type) ? type : "other";
        counter("gahyeon.unreal.stt.streaming.events", "type", safe).increment();
    }

    void streamingSttFirstPartial(long elapsedNanos) {
        registry.timer("gahyeon.unreal.stt.streaming.first.partial").record(
                Math.max(0, elapsedNanos), TimeUnit.NANOSECONDS);
    }

    void streamingSttCompleted(String result, long elapsedNanos) {
        Timer.builder("gahyeon.unreal.stt.streaming.duration")
                .tag("result", safeCode(result))
                .register(registry)
                .record(Math.max(0, elapsedNanos), TimeUnit.NANOSECONDS);
    }

    void streamingSttFailure(String code) {
        counter("gahyeon.unreal.stt.streaming.failures", "code", safeCode(code)).increment();
    }

    void outboundAdmitted() {
        registry.counter("gahyeon.unreal.outbound.admitted").increment();
    }

    void outboundRendererDetached(String reason) {
        counter("gahyeon.unreal.outbound.renderer.detached", "reason", safeCode(reason)).increment();
    }

    void bindAudioCache(UnrealAudioCache cache) {
        Gauge.builder("gahyeon.unreal.audio.cache.entries", cache, UnrealAudioCache::entryCount)
                .register(registry);
        Gauge.builder("gahyeon.unreal.audio.cache.bytes", cache, UnrealAudioCache::totalBytes)
                .register(registry);
    }

    void bindOutbound(UnrealEphemeralBroker outbound) {
        Gauge.builder("gahyeon.unreal.outbound.renderers", outbound,
                        UnrealEphemeralBroker::subscriberCount)
                .register(registry);
        Gauge.builder("gahyeon.unreal.outbound.sessions", outbound,
                        UnrealEphemeralBroker::sessionCount)
                .register(registry);
        Gauge.builder("gahyeon.unreal.outbound.queued", outbound,
                        UnrealEphemeralBroker::queuedMessageCount)
                .register(registry);
    }

    void bindClientSessions(UnrealClientSessionRegistry clients) {
        Gauge.builder("gahyeon.unreal.client.bindings", clients,
                        UnrealClientSessionRegistry::connectionCount)
                .register(registry);
        Gauge.builder("gahyeon.unreal.client.open.connections", clients,
                        UnrealClientSessionRegistry::openConnectionCount)
                .register(registry);
        Gauge.builder("gahyeon.unreal.client.sessions", clients,
                        UnrealClientSessionRegistry::sessionCount)
                .register(registry);
    }

    void bindPerceptionSessions(UnrealPerceptionSessionTracker sessions) {
        Gauge.builder("gahyeon.unreal.perception.lifecycle.sessions", sessions,
                        UnrealPerceptionSessionTracker::sessionCount)
                .register(registry);
    }

    void bindPerceptionStore(LatestUnrealPerceptionStore perception) {
        Gauge.builder("gahyeon.unreal.perception.latest.sessions", perception,
                        LatestUnrealPerceptionStore::sessionCount)
                .register(registry);
    }

    void audioCacheEvicted(String reason) {
        counter("gahyeon.unreal.audio.cache.evicted", "reason", safeCode(reason)).increment();
    }

    void audioCacheRejected(String reason) {
        counter("gahyeon.unreal.audio.cache.rejected", "reason", safeCode(reason)).increment();
    }

    private Counter counter(String name, String... tags) {
        return registry.counter(name, tags);
    }

    private String safeType(String type) {
        return MESSAGE_TYPES.contains(type) ? type : "other";
    }

    private String safeCode(String code) {
        return code == null || !code.matches("[a-z_]{1,40}") ? "other" : code;
    }
}
