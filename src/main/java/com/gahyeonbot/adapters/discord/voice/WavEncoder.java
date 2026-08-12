package com.gahyeonbot.adapters.discord.voice;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

final class WavEncoder {
    static final int SAMPLE_RATE = 48_000;
    static final short CHANNELS = 2;
    static final short BITS_PER_SAMPLE = 16;

    private WavEncoder() {}

    static byte[] bigEndianToLittleEndian(byte[] pcm) {
        if ((pcm.length & 1) != 0) {
            throw new IllegalArgumentException("16-bit PCM byte length must be even");
        }
        byte[] converted = pcm.clone();
        for (int i = 0; i < converted.length; i += 2) {
            byte high = converted[i];
            converted[i] = converted[i + 1];
            converted[i + 1] = high;
        }
        return converted;
    }

    static byte[] pcmToWav(byte[] pcm) {
        int byteRate = SAMPLE_RATE * CHANNELS * BITS_PER_SAMPLE / 8;
        short blockAlign = (short) (CHANNELS * BITS_PER_SAMPLE / 8);
        ByteBuffer out = ByteBuffer.allocate(44 + pcm.length).order(ByteOrder.LITTLE_ENDIAN);
        putAscii(out, "RIFF");
        out.putInt(36 + pcm.length);
        putAscii(out, "WAVE");
        putAscii(out, "fmt ");
        out.putInt(16);
        out.putShort((short) 1);
        out.putShort(CHANNELS);
        out.putInt(SAMPLE_RATE);
        out.putInt(byteRate);
        out.putShort(blockAlign);
        out.putShort(BITS_PER_SAMPLE);
        putAscii(out, "data");
        out.putInt(pcm.length);
        out.put(pcm);
        return out.array();
    }

    private static void putAscii(ByteBuffer buffer, String value) {
        for (int i = 0; i < value.length(); i++) buffer.put((byte) value.charAt(i));
    }
}
