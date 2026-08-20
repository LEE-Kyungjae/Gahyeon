package com.gahyeonbot.application.speech;

import com.gahyeonbot.core.speech.ExpressiveSpeechRequest;
import com.gahyeonbot.core.speech.PcmAudioFormat;
import com.gahyeonbot.core.speech.VoiceProfileId;

import java.util.function.BooleanSupplier;

public interface StreamingExpressiveSpeechSynthesisPort {
    boolean isStreamingReady(VoiceProfileId voiceProfile);

    void streamPcm(ExpressiveSpeechRequest request, BooleanSupplier current, PcmSink sink);

    interface PcmSink {
        void started(PcmAudioFormat format);
        void chunk(byte[] pcm);
        void completed(long pcmBytes);
    }
}
