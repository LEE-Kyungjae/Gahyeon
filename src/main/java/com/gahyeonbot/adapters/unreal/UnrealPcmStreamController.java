package com.gahyeonbot.adapters.unreal;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

@RestController
@RequestMapping("/gahyeon/unreal/speech/stream")
@ConditionalOnBean(UnrealPcmStreamCache.class)
@ConditionalOnProperty(
        name = {"gahyeon.headless.enabled", "gahyeon.unreal.websocket.enabled"},
        havingValue = "true")
public final class UnrealPcmStreamController {
    private final UnrealPcmStreamCache streams;

    public UnrealPcmStreamController(UnrealPcmStreamCache streams) {
        this.streams = streams;
    }

    @GetMapping(value = "/{streamId}", produces = "audio/pcm")
    public ResponseEntity<StreamingResponseBody> stream(@PathVariable String streamId) {
        if (!streams.contains(streamId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "PCM stream을 찾을 수 없습니다.");
        }
        StreamingResponseBody body = output -> streams.writeTo(streamId, output);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .contentType(MediaType.parseMediaType("audio/pcm"))
                .header("X-Sample-Rate", "24000")
                .header("X-Sample-Format", "s16le")
                .header("X-Channels", "1")
                .header("X-Content-Type-Options", "nosniff")
                .body(body);
    }
}
