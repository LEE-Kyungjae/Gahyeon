package com.gahyeonbot.adapters.speech;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gahyeonbot.application.speech.StreamingExpressiveSpeechSynthesisPort;
import com.gahyeonbot.core.speech.ExpressiveSpeechRequest;
import com.gahyeonbot.core.speech.PcmAudioFormat;
import com.gahyeonbot.core.speech.VoiceProfileId;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BooleanSupplier;

/** Isolated raw-PCM streaming path; the existing complete-WAV adapter remains unchanged. */
public final class QwenStreamingExpressiveTtsAdapter implements StreamingExpressiveSpeechSynthesisPort {
    private static final int CHUNK_BYTES = 32_768;
    private final QwenExpressiveTtsProperties properties;
    private final URI endpoint;
    private final ObjectMapper mapper;
    private final HttpClient client;

    public QwenStreamingExpressiveTtsAdapter(
            QwenExpressiveTtsProperties properties, ObjectMapper mapper) {
        this(properties, mapper, HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(properties.getTimeoutMillis())).build());
    }

    QwenStreamingExpressiveTtsAdapter(
            QwenExpressiveTtsProperties properties, ObjectMapper mapper, HttpClient client) {
        this.properties = properties;
        this.mapper = mapper;
        this.client = client;
        this.endpoint = streamingEndpoint(properties.getEndpoint());
        requireIdentity("modelId", properties.getModelId());
        requireIdentity("quantization", properties.getQuantization());
        if (properties.getTimeoutMillis() < 500 || properties.getTimeoutMillis() > 120_000) {
            throw new IllegalArgumentException("Qwen timeout is out of range");
        }
        if (properties.getMaxAudioBytes() < 1_024
                || properties.getMaxAudioBytes() > 33_554_432) {
            throw new IllegalArgumentException("Qwen audio limit is out of range");
        }
    }

    @Override
    public boolean isStreamingReady(VoiceProfileId voiceProfile) {
        return properties.isEnabled() && endpoint != null && voiceProfile != null;
    }

    @Override
    public void streamPcm(
            ExpressiveSpeechRequest request, BooleanSupplier current, PcmSink sink) {
        if (!isStreamingReady(request.voiceProfile())) {
            throw new IllegalStateException("Qwen expressive PCM streaming is not ready");
        }
        if (current == null || sink == null) throw new IllegalArgumentException("stream lifecycle is required");
        HttpRequest.Builder builder = HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofMillis(properties.getTimeoutMillis()))
                .header("Content-Type", "application/json")
                .header("Accept", "audio/pcm")
                .POST(HttpRequest.BodyPublishers.ofByteArray(payload(request)));
        if (properties.getApiKey() != null && !properties.getApiKey().isBlank()) {
            builder.header("Authorization", "Bearer " + properties.getApiKey().trim());
        }
        try {
            HttpResponse<InputStream> response = client.send(
                    builder.build(), HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() != 200) {
                try (InputStream ignored = response.body()) { /* close transport */ }
                throw new IllegalStateException("Qwen expressive PCM stream returned HTTP "
                        + response.statusCode());
            }
            verify(response, request.voiceProfile());
            PcmAudioFormat format = PcmAudioFormat.QWEN_MONO_24K_S16LE;
            sink.started(format);
            long total = 0;
            byte[] buffer = new byte[CHUNK_BYTES];
            int pendingByte = -1;
            try (InputStream input = response.body()) {
                while (current.getAsBoolean()) {
                    int count = input.read(buffer);
                    if (count < 0) break;
                    if (count == 0) continue;
                    total += count;
                    if (total > properties.getMaxAudioBytes()) {
                        throw new IllegalStateException("Qwen expressive PCM stream exceeded its contract");
                    }
                    byte[] chunk;
                    if (pendingByte >= 0) {
                        chunk = new byte[count + 1];
                        chunk[0] = (byte) pendingByte;
                        System.arraycopy(buffer, 0, chunk, 1, count);
                        pendingByte = -1;
                    } else {
                        chunk = java.util.Arrays.copyOf(buffer, count);
                    }
                    if (chunk.length % format.bytesPerFrame() != 0) {
                        pendingByte = chunk[chunk.length - 1] & 0xff;
                        chunk = java.util.Arrays.copyOf(chunk, chunk.length - 1);
                    }
                    if (chunk.length > 0) sink.chunk(chunk);
                }
            }
            if (!current.getAsBoolean()) throw new IllegalStateException("Qwen expressive PCM stream was cancelled");
            if (total == 0 || pendingByte >= 0) {
                throw new IllegalStateException("Qwen expressive PCM stream ended on an invalid frame boundary");
            }
            sink.completed(total);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Qwen expressive PCM stream was interrupted", error);
        } catch (IOException error) {
            throw new IllegalStateException("Qwen expressive PCM stream failed", error);
        }
    }

    private byte[] payload(ExpressiveSpeechRequest request) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("text", request.segment().text());
        payload.put("voiceProfile", request.voiceProfile().value());
        payload.put("style", request.expression().style());
        payload.put("intensity", request.expression().intensity());
        payload.put("communicativeIntent", request.expression().communicativeIntent());
        payload.put("modelId", properties.getModelId().trim());
        payload.put("quantization", properties.getQuantization().trim());
        payload.put("responseFormat", "pcm");
        try {
            return mapper.writeValueAsBytes(payload);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Qwen expressive PCM request cannot be encoded", error);
        }
    }

    private void verify(HttpResponse<?> response, VoiceProfileId requestedVoice) {
        requireEqual("X-Gahyeon-Voice-Profile", requestedVoice.value(), header(response, "X-Gahyeon-Voice-Profile"));
        requireEqual("X-Gahyeon-Model-Id", properties.getModelId().trim(), header(response, "X-Gahyeon-Model-Id"));
        requireEqual("X-Gahyeon-Quantization", properties.getQuantization().trim(), header(response, "X-Gahyeon-Quantization"));
        requireEqual("X-Sample-Rate", "24000", header(response, "X-Sample-Rate"));
        requireEqual("X-Sample-Format", "s16le", header(response, "X-Sample-Format"));
        requireEqual("X-Channels", "1", header(response, "X-Channels"));
        String contentType = header(response, "Content-Type").split(";", 2)[0].trim();
        requireEqual("Content-Type", "audio/pcm", contentType);
    }

    private static String header(HttpResponse<?> response, String name) {
        return response.headers().firstValue(name).orElse("");
    }

    private static URI streamingEndpoint(String configured) {
        if (configured == null || configured.isBlank()) return null;
        URI base = URI.create(configured.trim());
        if (!("http".equalsIgnoreCase(base.getScheme()) || "https".equalsIgnoreCase(base.getScheme()))
                || !base.getPath().endsWith("/v1/speech")) return null;
        return URI.create(base.toString() + "/stream");
    }

    private static void requireIdentity(String name, String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
    }

    private static void requireEqual(String label, String expected, String observed) {
        if (!expected.equals(observed)) throw new IllegalStateException(label + " response identity mismatch");
    }
}
