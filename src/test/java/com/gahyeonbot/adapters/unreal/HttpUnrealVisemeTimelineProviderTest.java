package com.gahyeonbot.adapters.unreal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gahyeonbot.core.speech.AudioOutput;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HttpUnrealVisemeTimelineProviderTest {
    private HttpServer server;
    private final ObjectMapper mapper = new ObjectMapper();

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    @Test
    void bindsAnExactTimelineToTheRequestedAudioDigest() throws Exception {
        var authorization = new AtomicReference<String>();
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/align", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            JsonNode request = mapper.readTree(exchange.getRequestBody());
            byte[] response = mapper.writeValueAsBytes(java.util.Map.of(
                    "audioSha256", request.path("audioSha256").asText(),
                    "cues", java.util.List.of(java.util.Map.of(
                            "semantic", "aa", "atMs", 10, "durationMs", 90, "weight", 0.8))));
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        UnrealVisemeAlignmentProperties properties = properties();
        properties.setApiKey("secret");

        var timeline = new HttpUnrealVisemeTimelineProvider(properties)
                .align("안녕하세요", audio());

        assertThat(authorization).hasValue("Bearer secret");
        assertThat(timeline).containsExactly(new UnrealVisemeCue("aa", 10, 90, 0.8));
    }

    @Test
    void rejectsAResponseForDifferentAudio() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/align", exchange -> {
            byte[] response = "{\"audioSha256\":\"wrong\",\"cues\":[]}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        assertThatThrownBy(() -> new HttpUnrealVisemeTimelineProvider(properties())
                .align("안녕하세요", audio()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("does not match");
    }

    @Test
    void rejectsOversizedAlignerResponseBeforeDeserialization() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/align", exchange -> {
            byte[] response = ("{\"audioSha256\":\"" + "a".repeat(2_000)
                    + "\",\"cues\":[]}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        UnrealVisemeAlignmentProperties properties = properties();
        properties.setMaxResponseBytes(1_024);

        assertThatThrownBy(() -> new HttpUnrealVisemeTimelineProvider(properties)
                .align("안녕하세요", audio()))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("size limit");
    }

    @Test
    void rejectsConfigurationThatCanBreakTheRealtimeBudget() {
        UnrealVisemeAlignmentProperties slow = new UnrealVisemeAlignmentProperties();
        slow.setEnabled(true);
        slow.setEndpoint("http://127.0.0.1:18768/align");
        slow.setTimeoutMillis(5_001);
        assertThatThrownBy(() -> new HttpUnrealVisemeTimelineProvider(slow))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("timeout");

        UnrealVisemeAlignmentProperties unbounded = new UnrealVisemeAlignmentProperties();
        unbounded.setEnabled(true);
        unbounded.setEndpoint("http://127.0.0.1:18768/align");
        unbounded.setMaxResponseBytes(1_048_577);
        assertThatThrownBy(() -> new HttpUnrealVisemeTimelineProvider(unbounded))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("response limit");
    }

    private UnrealVisemeAlignmentProperties properties() {
        var properties = new UnrealVisemeAlignmentProperties();
        properties.setEnabled(true);
        properties.setEndpoint("http://127.0.0.1:" + server.getAddress().getPort() + "/align");
        return properties;
    }

    private static AudioOutput audio() {
        byte[] bytes = "RIFF-test-audio".getBytes(StandardCharsets.UTF_8);
        // The adapter binds arbitrary bytes; PCM validity is enforced by speech preparation first.
        return new AudioOutput(bytes, "audio/wav", "wav");
    }
}
