package com.gahyeonbot.adapters.unreal;

import com.gahyeonbot.core.speech.AudioOutput;

import java.util.ArrayList;
import java.util.List;

/**
 * Deterministic fallback for PCM WAV providers that expose no phoneme timing.
 * This is deliberately labelled heuristic: audio duration is authoritative,
 * while Korean vowel classes are distributed over speakable code points.
 */
public final class PcmWavKoreanVisemeTimeline implements UnrealVisemeTimelinePort {
    private static final int MAX_CUES = 256;

    @Override
    public List<UnrealVisemeCue> align(String text, AudioOutput audio) {
        long durationMs = pcmWavDurationMs(audio);
        if (durationMs <= 0 || text == null || text.isBlank()) return List.of();
        List<Integer> speakable = text.codePoints()
                .filter(PcmWavKoreanVisemeTimeline::speakable)
                .boxed()
                .toList();
        if (speakable.isEmpty()) return List.of();

        int count = (int) Math.min(Math.min(MAX_CUES, speakable.size()), durationMs);
        long leadMs = Math.min(35, durationMs / 20);
        long usableMs = Math.max(count, durationMs - leadMs * 2);
        var cues = new ArrayList<UnrealVisemeCue>(count);
        for (int index = 0; index < count; index++) {
            int sourceIndex = (int) ((long) index * speakable.size() / count);
            long atMs = leadMs + (long) index * usableMs / count;
            long nextMs = leadMs + (long) (index + 1) * usableMs / count;
            long cueMs = Math.max(1, Math.min(160, Math.max(45, nextMs - atMs)));
            cueMs = Math.min(cueMs, Math.max(1, durationMs - atMs));
            cues.add(new UnrealVisemeCue(
                    semantic(speakable.get(sourceIndex)), atMs, cueMs, 0.9));
        }
        return List.copyOf(cues);
    }

    @Override
    public String source() {
        return "heuristic";
    }

    static long pcmWavDurationMs(AudioOutput audio) {
        if (audio == null || !("audio/wav".equals(audio.mediaType())
                || "audio/x-wav".equals(audio.mediaType()))) return -1;
        byte[] bytes = audio.data();
        if (bytes.length < 44 || !fourCc(bytes, 0, "RIFF") || !fourCc(bytes, 8, "WAVE")) return -1;
        int offset = 12;
        int format = -1;
        int channels = 0;
        long sampleRate = 0;
        int blockAlign = 0;
        int bitsPerSample = 0;
        long dataBytes = -1;
        while (offset <= bytes.length - 8) {
            long chunkBytes = u32(bytes, offset + 4);
            long dataStart = (long) offset + 8;
            if (chunkBytes > Integer.MAX_VALUE || dataStart + chunkBytes > bytes.length) return -1;
            if (fourCc(bytes, offset, "fmt ") && chunkBytes >= 16) {
                format = u16(bytes, offset + 8);
                channels = u16(bytes, offset + 10);
                sampleRate = u32(bytes, offset + 12);
                blockAlign = u16(bytes, offset + 20);
                bitsPerSample = u16(bytes, offset + 22);
            } else if (fourCc(bytes, offset, "data")) {
                dataBytes = chunkBytes;
            }
            long next = dataStart + chunkBytes + (chunkBytes & 1L);
            if (next > Integer.MAX_VALUE || next <= offset) return -1;
            offset = (int) next;
        }
        if (format != 1 || (channels != 1 && channels != 2) || bitsPerSample != 16
                || sampleRate < 8_000 || sampleRate > 192_000
                || blockAlign != channels * 2 || dataBytes <= 0
                || dataBytes % blockAlign != 0) return -1;
        long frames = dataBytes / blockAlign;
        return Math.max(1, frames * 1_000 / sampleRate);
    }

    private static boolean speakable(int codePoint) {
        return Character.isLetterOrDigit(codePoint) || codePoint >= 0xAC00 && codePoint <= 0xD7A3;
    }

    private static String semantic(int codePoint) {
        if (codePoint < 0xAC00 || codePoint > 0xD7A3) return "aa";
        int vowel = (codePoint - 0xAC00) / 28 % 21;
        return switch (vowel) {
            case 0, 2 -> "aa";                 // ㅏ ㅑ
            case 1, 3, 4, 5, 6, 7 -> "E";    // ㅐ ㅒ ㅓ ㅔ ㅕ ㅖ
            case 8, 9, 10, 11, 12 -> "O";    // ㅗ ㅘ ㅙ ㅚ ㅛ
            case 13, 14, 15, 16, 17, 18 -> "U"; // ㅜ ㅝ ㅞ ㅟ ㅠ ㅡ
            default -> "I";                    // ㅢ ㅣ
        };
    }

    private static int u16(byte[] bytes, int offset) {
        return Byte.toUnsignedInt(bytes[offset]) | Byte.toUnsignedInt(bytes[offset + 1]) << 8;
    }

    private static long u32(byte[] bytes, int offset) {
        return Integer.toUnsignedLong(Byte.toUnsignedInt(bytes[offset])
                | Byte.toUnsignedInt(bytes[offset + 1]) << 8
                | Byte.toUnsignedInt(bytes[offset + 2]) << 16
                | Byte.toUnsignedInt(bytes[offset + 3]) << 24);
    }

    private static boolean fourCc(byte[] bytes, int offset, String expected) {
        if (offset < 0 || offset + 4 > bytes.length) return false;
        for (int index = 0; index < 4; index++) {
            if (bytes[offset + index] != (byte) expected.charAt(index)) return false;
        }
        return true;
    }
}
