package com.gahyeonbot.services.assistant;

import com.gahyeonbot.adapters.discord.DiscordIdentityMapper;
import com.gahyeonbot.adapters.discord.DiscordAudioFileMaterializer;
import com.gahyeonbot.adapters.speech.TenVadDetector;
import com.gahyeonbot.application.speech.StreamingUtteranceAccumulator;
import com.gahyeonbot.application.speech.UtteranceSegmentationPolicy;
import com.gahyeonbot.core.audio.GuildMusicManager;
import com.gahyeonbot.core.conversation.ConversationRequest;
import com.gahyeonbot.core.session.ClientSource;
import com.gahyeonbot.core.session.ConversationModality;
import com.gahyeonbot.core.session.ConversationSession;
import com.gahyeonbot.core.session.ConversationSessionId;
import com.gahyeonbot.core.speech.AudioInput;
import com.gahyeonbot.core.speech.AudioOutput;
import com.gahyeonbot.core.speech.SpeechSegment;
import com.gahyeonbot.core.speech.SpeechSynthesisUseCase;
import com.gahyeonbot.core.speech.TranscriptionUseCase;
import com.gahyeonbot.core.speech.VoiceProfileId;
import com.gahyeonbot.services.music.MusicService;
import com.gahyeonbot.services.tts.TtsTrackMetadata;
import com.sedmelluq.discord.lavaplayer.player.AudioLoadResultHandler;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.dv8tion.jda.api.audio.AudioReceiveHandler;
import net.dv8tion.jda.api.audio.UserAudio;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import net.dv8tion.jda.api.entities.channel.middleman.AudioChannel;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Service
@RequiredArgsConstructor
public class VoiceAssistantService {
    private final AssistantProperties properties;
    private final TranscriptionUseCase transcription;
    private final AssistantChatProvider chatProvider;
    private final DiscordIdentityMapper identityMapper;
    private final SpeechSynthesisUseCase speechSynthesis;
    private final DiscordAudioFileMaterializer audioFiles;
    private final MusicService musicService;
    private final com.gahyeonbot.core.audio.AudioManager audioManager;

    private final Map<Long, Session> sessions = new ConcurrentHashMap<>();
    private final ScheduledExecutorService silenceDetector =
            Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().name("assistant-silence-", 0).factory());
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();

    public boolean isConfigured() {
        return properties.isEnabled() && transcription.isReady() && chatProvider.isReady();
    }

    public StartResult start(Guild guild, Member requester, MessageChannel textChannel) {
        if (!properties.isEnabled()) return new StartResult(false, "비서 기능이 비활성화되어 있습니다.");
        if (!transcription.isReady()) return new StartResult(false, "STT 설정이 필요합니다.");
        if (!chatProvider.isReady()) return new StartResult(false, "OpenRouter 키와 모델 설정이 필요합니다.");
        if (requester == null || requester.getVoiceState() == null
                || requester.getVoiceState().getChannel() == null) {
            return new StartResult(false, "먼저 음성 채널에 입장해 주세요.");
        }
        AudioChannel channel = requester.getVoiceState().getChannel();
        GuildMusicManager musicManager = musicService.getOrCreateGuildMusicManager(guild);
        Session session = new Session(
                guild, channel, textChannel, musicManager, requester.getIdLong());
        if (sessions.putIfAbsent(guild.getIdLong(), session) != null) {
            session.close();
            return new StartResult(false, "이 서버에서는 이미 비서 세션이 실행 중입니다.");
        }

        try {
            var manager = guild.getAudioManager();
            manager.setSelfDeafened(false);
            manager.setSendingHandler(musicManager.getSendHandler());
            manager.setReceivingHandler(session.receiver);
            manager.openAudioConnection(channel);
        } catch (RuntimeException e) {
            sessions.remove(guild.getIdLong(), session);
            session.close();
            throw e;
        }
        return new StartResult(true,
                "음성 비서 세션을 시작했습니다. 종료 전까지 참여자의 음성이 외부 STT/AI로 전송되고 전사문이 이 채널에 표시됩니다.");
    }

    public boolean stop(Guild guild) {
        Session session = sessions.remove(guild.getIdLong());
        if (session == null) return false;
        session.close();
        guild.getAudioManager().setReceivingHandler(null);
        guild.getAudioManager().closeAudioConnection();
        return true;
    }

    public boolean isRunning(long guildId) {
        return sessions.containsKey(guildId);
    }

    public boolean stopWhenOwnerLeaves(Guild guild, long memberId, long voiceChannelId) {
        Session session = sessions.get(guild.getIdLong());
        if (session == null
                || session.ownerUserId != memberId
                || session.voiceChannel.getIdLong() != voiceChannelId) {
            return false;
        }
        return stop(guild);
    }

    @PreDestroy
    void shutdown() {
        sessions.values().forEach(Session::close);
        silenceDetector.shutdownNow();
        workers.shutdownNow();
    }

    public record StartResult(boolean started, String message) {}

    private final class Session {
        private final Guild guild;
        private final AudioChannel voiceChannel;
        private final MessageChannel textChannel;
        private final GuildMusicManager musicManager;
        private final long ownerUserId;
        private final ConversationSessionId conversationSessionId;
        private final Map<Long, Utterance> utterances = new ConcurrentHashMap<>();
        private final Map<Long, RequestGuard> requestGuards = new ConcurrentHashMap<>();
        private final AudioReceiveHandler receiver = new Receiver();
        private final ScheduledFuture<?> silenceTask;
        private final ExecutorService ttsWorker = Executors.newSingleThreadExecutor(
                Thread.ofVirtual().name("assistant-tts-", 0).factory());
        private final AtomicLong responseRevision = new AtomicLong();
        private volatile boolean closed;

        private Session(Guild guild, AudioChannel voiceChannel, MessageChannel textChannel,
                        GuildMusicManager musicManager, long ownerUserId) {
            this.guild = guild;
            this.voiceChannel = voiceChannel;
            this.textChannel = textChannel;
            this.musicManager = musicManager;
            this.ownerUserId = ownerUserId;
            this.conversationSessionId = new ConversationSessionId(
                    "discord:voice:" + guild.getId());
            this.silenceTask = silenceDetector.scheduleWithFixedDelay(
                    this::flushSilent, 250, 250, TimeUnit.MILLISECONDS);
        }

        private void close() {
            closed = true;
            responseRevision.incrementAndGet();
            silenceTask.cancel(false);
            ttsWorker.shutdownNow();
            musicManager.interruptTtsPlayback();
            utterances.values().forEach(Utterance::close);
            utterances.clear();
        }

        private final class Receiver implements AudioReceiveHandler {
            @Override public boolean canReceiveUser() { return !closed; }

            @Override
            public void handleUserAudio(UserAudio userAudio) {
                if (closed || userAudio.getUser().isBot()) return;
                // JDA exposes decoded 16-bit PCM in big-endian byte order, while
                // WAV and TEN VAD expect little-endian samples.
                byte[] pcm = WavEncoder.bigEndianToLittleEndian(userAudio.getAudioData(1.0));
                Utterance utterance = utterances.computeIfAbsent(
                        userAudio.getUser().getIdLong(),
                        ignored -> new Utterance(userAudio.getUser().getName()));
                synchronized (utterance) {
                    utterance.accumulator.accept(pcm, System.currentTimeMillis());
                }
            }
        }

        private void flushSilent() {
            if (closed) return;
            long now = System.currentTimeMillis();
            utterances.forEach((userId, utterance) -> {
                synchronized (utterance) {
                    utterance.accumulator.poll(now).ifPresent(completed -> workers.submit(() -> process(
                            userId,
                            utterance.username,
                            completed.pcm(),
                            completed.capturedAudioMillis(),
                            completed.detectedSpeechMillis())));
                }
            });
        }

        private void process(
                long userId,
                String username,
                byte[] pcm,
                long capturedAudioMillis,
                long detectedSpeechMillis) {
            if (closed) return;
            try {
                long sttStartedAt = System.nanoTime();
                String transcript = transcription.transcribe(new AudioInput(
                        WavEncoder.pcmToWav(pcm), "audio/wav"));
                long sttMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - sttStartedAt);
                log.info("비서 STT 완료 guild={} user={} audioMs={} detectedSpeechMs={} sttMs={} chars={}",
                        guild.getIdLong(), userId, capturedAudioMillis, detectedSpeechMillis,
                        sttMillis, transcript.length());
                if (transcript.isBlank()) return;
                RequestGuard guard = requestGuards.computeIfAbsent(userId, ignored -> new RequestGuard());
                transcript = guard.mergeOrHold(transcript, System.currentTimeMillis());
                if (transcript == null) {
                    log.info("비서 짧은 전사 보류 guild={} user={}", guild.getIdLong(), userId);
                    return;
                }
                if (!guard.allow(transcript, System.currentTimeMillis())) {
                    log.info("비서 AI 호출 차단 guild={} user={} reason=duplicate-or-rate-limit",
                            guild.getIdLong(), userId);
                    return;
                }
                long revision = responseRevision.incrementAndGet();
                musicManager.interruptTtsPlayback();
                textChannel.sendMessage("**" + username + "**: " + limit(transcript, 1500)).queue();
                AtomicBoolean waitingForAnswer = new AtomicBoolean(true);
                ScheduledFuture<?> acknowledgement = scheduleAcknowledgement(
                        revision, waitingForAnswer);
                String answer;
                try {
                    synchronized (guard.aiLock) {
                        var conversationSession = new ConversationSession(
                                conversationSessionId,
                                identityMapper.toActorId(userId, username),
                                ClientSource.DISCORD,
                                ConversationModality.VOICE,
                                Map.of("discord.guildId", guild.getId()));
                        answer = chatProvider.chat(new ConversationRequest(
                                "voice:" + guild.getId() + ":" + UUID.randomUUID(),
                                conversationSession,
                                username,
                                transcript));
                    }
                } finally {
                    waitingForAnswer.set(false);
                    if (acknowledgement != null) acknowledgement.cancel(false);
                }
                // A progress acknowledgement may already be playing when the answer arrives.
                musicManager.interruptTtsPlayback();
                textChannel.sendMessage("**가현**: " + limit(answer, 1800)).queue();
                if (properties.isSpeakResponses()
                        && speechSynthesis.isReady(VoiceProfileId.ASSISTANT) && !closed
                        && TtsSpeechText.isSafeToSpeak(answer)) {
                    queueSpeech(answer, revision);
                } else if (!TtsSpeechText.isSafeToSpeak(answer)) {
                    log.warn("내부 오류 형태의 응답은 음성 출력을 생략합니다. guild={} revision={}",
                            guild.getIdLong(), revision);
                }
            } catch (Exception e) {
                log.error("음성 비서 처리 실패 guild={} user={}", guild.getIdLong(), userId, e);
                textChannel.sendMessage("음성 비서 처리에 실패했습니다. 잠시 후 다시 말해 주세요.").queue();
            }
        }

        private ScheduledFuture<?> scheduleAcknowledgement(
                long revision,
                AtomicBoolean waitingForAnswer) {
            long delay = properties.getResponseAcknowledgementMillis();
            String message = properties.getResponseAcknowledgementText();
            if (!properties.isSpeakResponses()
                    || !speechSynthesis.isReady(VoiceProfileId.ASSISTANT)
                    || delay < 0 || message == null || message.isBlank()) {
                return null;
            }
            return silenceDetector.schedule(
                    () -> ttsWorker.submit(() -> speakAcknowledgement(
                            message, revision, waitingForAnswer)),
                    delay, TimeUnit.MILLISECONDS);
        }

        private void speakAcknowledgement(
                String message,
                long revision,
                AtomicBoolean waitingForAnswer) {
            if (closed || revision != responseRevision.get() || !waitingForAnswer.get()) return;
            try {
                var segments = speechSynthesis.prepare(message);
                if (segments.isEmpty()) return;
                AudioOutput output = speechSynthesis.synthesize(
                        segments.getFirst(), VoiceProfileId.ASSISTANT);
                Path audio = audioFiles.materialize(output);
                if (closed || revision != responseRevision.get() || !waitingForAnswer.get()) {
                    java.nio.file.Files.deleteIfExists(audio);
                    return;
                }
                audioManager.getPlayerManager().loadItem(audio.toString(), new AudioLoadResultHandler() {
                    @Override public void trackLoaded(AudioTrack track) {
                        if (closed || revision != responseRevision.get() || !waitingForAnswer.get()) {
                            try { java.nio.file.Files.deleteIfExists(audio); } catch (Exception ignored) {}
                            return;
                        }
                        track.setUserData(new TtsTrackMetadata(audio, true, true));
                        musicManager.playOrQueueTrack(track);
                    }
                    @Override public void playlistLoaded(AudioPlaylist playlist) {
                        if (!playlist.getTracks().isEmpty()) trackLoaded(playlist.getTracks().getFirst());
                    }
                    @Override public void noMatches() {
                        log.warn("비서 대기 안내 TTS 파일을 로드하지 못함: {}", audio);
                    }
                    @Override public void loadFailed(FriendlyException exception) {
                        log.warn("비서 대기 안내 TTS 로딩 실패: {}", exception.getMessage());
                    }
                });
            } catch (Exception e) {
                if (!closed && waitingForAnswer.get()) {
                    log.warn("비서 대기 안내 TTS 처리 실패 guild={}: {}",
                            guild.getIdLong(), e.getMessage());
                }
            }
        }

        private void queueSpeech(String answer, long revision) {
            ttsWorker.submit(() -> {
                try {
                    speakLatest(answer, revision);
                } catch (Exception e) {
                    if (!closed && revision == responseRevision.get()) {
                        log.error("비서 TTS 처리 실패 guild={} revision={}",
                                guild.getIdLong(), revision, e);
                    }
                }
            });
        }

        private void speakLatest(String answer, long revision) throws Exception {
            String spokenText = TtsSpeechText.sanitize(answer);
            if (spokenText.isBlank()) return;
            for (SpeechSegment segment : speechSynthesis.prepare(spokenText)) {
                if (closed || revision != responseRevision.get()) return;
                AudioOutput output = speechSynthesis.synthesize(
                        segment, VoiceProfileId.ASSISTANT);
                Path audio = audioFiles.materialize(output);
                if (closed || revision != responseRevision.get()) {
                    java.nio.file.Files.deleteIfExists(audio);
                    return;
                }
                audioManager.getPlayerManager().loadItem(audio.toString(), new AudioLoadResultHandler() {
                    @Override public void trackLoaded(AudioTrack track) {
                        if (closed || revision != responseRevision.get()) {
                            try { java.nio.file.Files.deleteIfExists(audio); } catch (Exception ignored) {}
                            return;
                        }
                        track.setUserData(new TtsTrackMetadata(audio, true, true));
                        musicManager.playOrQueueTrack(track);
                    }
                    @Override public void playlistLoaded(AudioPlaylist playlist) {
                        if (!playlist.getTracks().isEmpty()) trackLoaded(playlist.getTracks().getFirst());
                    }
                    @Override public void noMatches() { log.warn("비서 TTS 파일을 로드하지 못함: {}", audio); }
                    @Override public void loadFailed(FriendlyException exception) {
                        log.warn("비서 TTS 로딩 실패: {}", exception.getMessage());
                    }
                });
            }
        }
    }

    private final class Utterance {
        private final String username;
        private final StreamingUtteranceAccumulator accumulator;

        private Utterance(String username) {
            this.username = username;
            AssistantProperties.Vad vad = properties.getVad();
            int bytesPerSecond = WavEncoder.SAMPLE_RATE * WavEncoder.CHANNELS
                    * WavEncoder.BITS_PER_SAMPLE / 8;
            var policy = new UtteranceSegmentationPolicy(
                    bytesPerSecond,
                    properties.getMaxUtteranceSeconds(),
                    bytesPerSecond / 2,
                    vad.isEnabled() ? vad.getEndSilenceMillis() : properties.getSilenceMillis(),
                    vad.isEnabled() ? vad.getMinSpeechMillis() : 0,
                    vad.isEnabled() ? vad.getShortSpeechMillis() : 0,
                    vad.isEnabled() ? vad.getShortSpeechEndSilenceMillis() : properties.getSilenceMillis(),
                    vad.isEnabled() ? vad.getPreRollMillis() : 0,
                    16_000);
            this.accumulator = new StreamingUtteranceAccumulator(
                    policy,
                    vad.isEnabled() ? new TenVadDetector(vad.getHopSize(), vad.getThreshold()) : null,
                    System.currentTimeMillis());
        }

        private void close() {
            accumulator.close();
        }
    }

    private final class RequestGuard {
        private final Object aiLock = new Object();
        private final Deque<Long> requestTimes = new ArrayDeque<>();
        private String lastTranscript = "";
        private long lastTranscriptAt;
        private String pendingFragment = "";
        private long pendingFragmentAt;

        private synchronized String mergeOrHold(String transcript, long now) {
            String clean = transcript == null ? "" : transcript.trim();
            if (!pendingFragment.isBlank()) {
                if (now - pendingFragmentAt <= properties.getFragmentMergeMillis()) {
                    clean = pendingFragment + " " + clean;
                }
                pendingFragment = "";
                pendingFragmentAt = 0;
            }
            long meaningfulCharacters = clean.codePoints()
                    .filter(Character::isLetterOrDigit)
                    .count();
            if (meaningfulCharacters < properties.getMinTranscriptCharacters()) {
                pendingFragment = clean;
                pendingFragmentAt = now;
                return null;
            }
            return clean;
        }

        private synchronized boolean allow(String transcript, long now) {
            String normalized = transcript.replaceAll("\\s+", "").trim();
            if (normalized.equals(lastTranscript)
                    && now - lastTranscriptAt < properties.getDuplicateTranscriptMillis()) {
                return false;
            }
            long cutoff = now - TimeUnit.MINUTES.toMillis(1);
            while (!requestTimes.isEmpty() && requestTimes.peekFirst() < cutoff) {
                requestTimes.removeFirst();
            }
            if (requestTimes.size() >= Math.max(1, properties.getMaxAiRequestsPerMinute())) {
                return false;
            }
            requestTimes.addLast(now);
            lastTranscript = normalized;
            lastTranscriptAt = now;
            return true;
        }
    }

    private static String limit(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max - 3) + "...";
    }
}
