package com.gahyeonbot.services.assistant;

import com.gahyeonbot.application.speech.StreamingTranscriptionPort;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.assertj.core.api.Assertions.assertThat;

class StreamingFloatPcm16ConverterTest {
    @Test
    void downmixesStereoAndResamples48kTo24kAcrossChunks() {
        var format = new StreamingTranscriptionPort.AudioFormat("float32le", 48_000, 2, 480);
        var converter = new StreamingFloatPcm16Converter(format, 24_000);
        byte[] first = stereo(480, 0.5f, 0.5f);
        byte[] second = stereo(480, 0.5f, 0.5f);

        byte[] firstOutput = converter.convert(first);
        byte[] secondOutput = converter.convert(second);

        assertThat(firstOutput).hasSize(240 * Short.BYTES);
        assertThat(secondOutput).hasSize(240 * Short.BYTES);
        assertThat(ByteBuffer.wrap(firstOutput).order(ByteOrder.LITTLE_ENDIAN).getShort())
                .isBetween((short) 16_382, (short) 16_384);
    }

    @Test
    void preservesDurationWhenUpsamplingChunkBoundaries() {
        var format = new StreamingTranscriptionPort.AudioFormat("float32le", 16_000, 1, 160);
        var converter = new StreamingFloatPcm16Converter(format, 24_000);
        int outputSamples = 0;
        for (int chunk = 0; chunk < 10; chunk++) {
            outputSamples += converter.convert(mono(160, 0.25f)).length / Short.BYTES;
        }
        assertThat(outputSamples).isBetween(2_398, 2_400);
    }

    @Test
    void downmixesOppositeChannelsToSilenceAndClampsNonFiniteInput() {
        var format = new StreamingTranscriptionPort.AudioFormat("float32le", 24_000, 2, 240);
        var converter = new StreamingFloatPcm16Converter(format, 24_000);
        byte[] input = stereo(240, 1f, -1f);
        ByteBuffer nonFinite = ByteBuffer.wrap(input).order(ByteOrder.LITTLE_ENDIAN);
        nonFinite.putFloat(0, Float.NaN);
        nonFinite.putFloat(Float.BYTES, Float.NaN);

        ByteBuffer output = ByteBuffer.wrap(converter.convert(input)).order(ByteOrder.LITTLE_ENDIAN);
        while (output.hasRemaining()) assertThat(output.getShort()).isZero();
    }

    private static byte[] mono(int frames, float value) {
        ByteBuffer buffer = ByteBuffer.allocate(frames * Float.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        for (int frame = 0; frame < frames; frame++) buffer.putFloat(value);
        return buffer.array();
    }

    private static byte[] stereo(int frames, float left, float right) {
        ByteBuffer buffer = ByteBuffer.allocate(frames * 2 * Float.BYTES)
                .order(ByteOrder.LITTLE_ENDIAN);
        for (int frame = 0; frame < frames; frame++) {
            buffer.putFloat(left);
            buffer.putFloat(right);
        }
        return buffer.array();
    }
}
