package com.gahyeonbot.services.assistant;

import com.gahyeonbot.application.speech.StreamingTranscriptionPort;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/** Stateful downmix and linear resampler for provider PCM16 input. */
final class StreamingFloatPcm16Converter {
    private final int sourceRate;
    private final int channels;
    private final int targetRate;
    private final double sourceFramesPerTargetFrame;
    private float[] retained = new float[0];
    private double nextPosition;

    StreamingFloatPcm16Converter(
            StreamingTranscriptionPort.AudioFormat format,
            int targetRate) {
        if (format == null) throw new IllegalArgumentException("format is required");
        if (targetRate < 8_000 || targetRate > 192_000) {
            throw new IllegalArgumentException("targetRate is out of range");
        }
        this.sourceRate = format.sampleRate();
        this.channels = format.channels();
        this.targetRate = targetRate;
        this.sourceFramesPerTargetFrame = (double) sourceRate / targetRate;
    }

    synchronized byte[] convert(byte[] float32le) {
        if (float32le == null || float32le.length == 0
                || float32le.length % (channels * Float.BYTES) != 0) {
            throw new IllegalArgumentException("invalid interleaved float32 PCM");
        }
        ByteBuffer input = ByteBuffer.wrap(float32le).order(ByteOrder.LITTLE_ENDIAN);
        int frames = float32le.length / (channels * Float.BYTES);
        float[] mono = Arrays.copyOf(retained, retained.length + frames);
        int target = retained.length;
        for (int frame = 0; frame < frames; frame++) {
            double sum = 0;
            for (int channel = 0; channel < channels; channel++) {
                float sample = input.getFloat();
                sum += Float.isFinite(sample) ? sample : 0;
            }
            mono[target++] = (float) (sum / channels);
        }

        int maximumOutput = (int) Math.ceil(mono.length / sourceFramesPerTargetFrame) + 1;
        ByteBuffer output = ByteBuffer.allocate(maximumOutput * Short.BYTES)
                .order(ByteOrder.LITTLE_ENDIAN);
        while (nextPosition + 1 < mono.length) {
            int left = (int) Math.floor(nextPosition);
            double fraction = nextPosition - left;
            double interpolated = mono[left] + (mono[left + 1] - mono[left]) * fraction;
            int pcm = (int) Math.round(Math.max(-1, Math.min(1, interpolated)) * 32_767.0);
            output.putShort((short) pcm);
            nextPosition += sourceFramesPerTargetFrame;
        }
        int discard = Math.min((int) Math.floor(nextPosition), Math.max(0, mono.length - 1));
        retained = Arrays.copyOfRange(mono, discard, mono.length);
        nextPosition -= discard;
        return Arrays.copyOf(output.array(), output.position());
    }

    int sourceRate() {
        return sourceRate;
    }

    int targetRate() {
        return targetRate;
    }
}
