package com.gahyeonbot.commands.general;

import com.gahyeonbot.commands.util.AbstractCommand;
import com.gahyeonbot.commands.util.Description;
import com.gahyeonbot.commands.util.ResponseUtil;
import com.gahyeonbot.adapters.discord.audio.GuildMusicManager;
import com.gahyeonbot.adapters.discord.music.MusicService;
import com.gahyeonbot.services.tts.TtsService;
import com.gahyeonbot.services.tts.TtsTrackMetadata;
import com.sedmelluq.discord.lavaplayer.player.AudioLoadResultHandler;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.DiscordLocale;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.CompletableFuture;

@Slf4j
@Component
@RequiredArgsConstructor
public class Tts extends AbstractCommand {
    private static final String OPT_TEXT = "text";

    private final TtsService ttsService;
    private final MusicService musicService;
    private final com.gahyeonbot.adapters.discord.audio.AudioManager audioManager;

    @Override
    public String getName() {
        return Description.TTS_NAME;
    }

    @Override
    public Map<DiscordLocale, String> getNameLocalizations() {
        return localizeKorean(Description.TTS_NAME_KO);
    }

    @Override
    public String getDescription() {
        return Description.TTS_DESC;
    }

    @Override
    public String getDetailedDescription() {
        return Description.TTS_DETAIL;
    }

    @Override
    public List<OptionData> getOptions() {
        OptionData text = new OptionData(OptionType.STRING, OPT_TEXT, "읽을 텍스트", true)
                .setNameLocalization(DiscordLocale.KOREAN, "텍스트");
        return List.of(text);
    }

    @Override
    public void execute(SlashCommandInteractionEvent event) {
        Guild guild = event.getGuild();
        if (guild == null) {
            ResponseUtil.replyError(event, "길드를 찾을 수 없습니다.");
            return;
        }

        event.deferReply().queue(
                hook -> handle(event, guild),
                err -> {
                    log.error("명령어 '{}' deferReply 실패", getName(), err);
                    ResponseUtil.replyError(event, "명령어 처리를 시작하지 못했습니다.");
                }
        );
    }

    private void handle(SlashCommandInteractionEvent event, Guild guild) {
        if (!ttsService.isEnabled()) {
            ResponseUtil.replyError(event, "TTS 기능이 비활성화되어 있습니다.");
            return;
        }

        String text = event.getOption(OPT_TEXT) != null ? event.getOption(OPT_TEXT).getAsString() : null;
        if (text == null || text.isBlank()) {
            ResponseUtil.replyError(event, "읽을 텍스트를 입력해줘.");
            return;
        }
        if (text.length() > ttsService.getMaxChars()) {
            ResponseUtil.replyError(event, "텍스트가 너무 길어. (최대 " + ttsService.getMaxChars() + "자)");
            return;
        }

        GuildMusicManager musicManager = musicService.getOrCreateGuildMusicManager(guild);
        if (!musicService.ensureConnectedToVoiceChannel(event, guild, musicManager)) {
            return;
        }

        List<String> segments;
        try {
            segments = ttsService.prepareSegments(text);
        } catch (Exception e) {
            log.error("TTS 합성 실패", e);
            ResponseUtil.replyError(event, "TTS 준비에 실패했어: " + e.getMessage());
            return;
        }

        if (segments.isEmpty()) {
            ResponseUtil.replyError(event, "읽을 문장을 만들지 못했어.");
            return;
        }

        // 1) First segment is synthesized first for faster perceived response.
        Path firstAudio;
        try {
            firstAudio = ttsService.synthesizeSegmentToAudio(segments.get(0));
        } catch (Exception e) {
            log.error("TTS 첫 문장 합성 실패", e);
            ResponseUtil.replyError(event, "TTS 합성에 실패했어: " + e.getMessage());
            return;
        }

        AtomicBoolean firstQueued = new AtomicBoolean(false);
        enqueueWav(event, musicManager, firstAudio, firstQueued);

        // 2) Remaining segments synthesize in background and queue as they are ready.
        if (segments.size() > 1) {
            CompletableFuture.runAsync(() -> {
                for (int i = 1; i < segments.size(); i++) {
                    String segment = segments.get(i);
                    try {
                        Path audio = ttsService.synthesizeSegmentToAudio(segment);
                        enqueueWav(event, musicManager, audio, firstQueued);
                    } catch (Exception e) {
                        log.error("TTS 후속 문장 합성 실패 (index={})", i, e);
                    }
                }
            });
        }
    }

    private void enqueueWav(SlashCommandInteractionEvent event, GuildMusicManager musicManager, Path wav, AtomicBoolean firstQueued) {
        audioManager.getPlayerManager().loadItem(wav.toString(), new AudioLoadResultHandler() {
            @Override
            public void trackLoaded(AudioTrack track) {
                // Mark for cleanup.
                track.setUserData(new TtsTrackMetadata(wav, true));

                musicManager.playOrQueueTrack(track);
                firstQueued.compareAndSet(false, true);
            }

            @Override
            public void playlistLoaded(AudioPlaylist playlist) {
                // Should not happen for wav, but handle defensively.
                if (!playlist.getTracks().isEmpty()) {
                    trackLoaded(playlist.getTracks().get(0));
                } else {
                    fail("재생 가능한 트랙이 없어.");
                }
            }

            @Override
            public void noMatches() {
                fail("오디오를 로드하지 못했어.");
            }

            @Override
            public void loadFailed(FriendlyException e) {
                fail("오디오 로딩 실패: " + e.getMessage());
            }

            private void fail(String msg) {
                log.warn("TTS wav enqueue 실패: {} ({})", msg, wav);
                if (firstQueued.compareAndSet(false, true)) {
                    ResponseUtil.replyError(event, msg);
                }
            }
        });
    }
}
