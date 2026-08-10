package com.gahyeonbot.commands.music;

import com.gahyeonbot.commands.util.AbstractCommand;
import com.gahyeonbot.commands.util.Description;
import com.gahyeonbot.commands.util.EmbedUtil;
import com.gahyeonbot.commands.util.ResponseUtil;
import com.gahyeonbot.adapters.discord.audio.GuildMusicManager;
import com.gahyeonbot.services.music.MusicService;
import com.gahyeonbot.services.streaming.StreamingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.DiscordLocale;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class Music extends AbstractCommand {

    private final MusicService musicService;
    private final StreamingService streamingService;
    private final Map<Long, GuildMusicManager> musicManagers;

    private static final String OPT_ACTION = "action";
    private static final String OPT_QUERY = "query";

    private enum Action {
        ADD,
        PAUSE,
        RESUME,
        SKIP,
        QUEUE,
        CLEAR
    }

    @Override
    public String getName() {
        return Description.MUSIC_NAME;
    }

    @Override
    public Map<DiscordLocale, String> getNameLocalizations() {
        return localizeKorean(Description.MUSIC_NAME_KO);
    }

    @Override
    public String getDescription() {
        return Description.MUSIC_DESC;
    }

    @Override
    public String getDetailedDescription() {
        return Description.MUSIC_DETAIL;
    }

    @Override
    public List<OptionData> getOptions() {
        OptionData action = new OptionData(OptionType.STRING, OPT_ACTION, "실행할 동작", false)
                .setNameLocalization(DiscordLocale.KOREAN, "동작")
                .addChoice("추가", "add")
                .addChoice("일시정지", "pause")
                .addChoice("재생", "resume")
                .addChoice("다음곡", "skip")
                .addChoice("대기열", "queue")
                .addChoice("초기화", "clear");

        OptionData query = new OptionData(OptionType.STRING, OPT_QUERY, "노래 제목/검색어 (추가할 때 사용)", false)
                .setNameLocalization(DiscordLocale.KOREAN, "노래");

        return List.of(action, query);
    }

    @Override
    public void execute(SlashCommandInteractionEvent event) {
        log.info("명령어 실행 시작: {}", getName());

        Guild guild = event.getGuild();
        if (guild == null) {
            ResponseUtil.replyError(event, "길드를 찾을 수 없습니다.");
            return;
        }

        // Defer to allow streaming search/network operations.
        event.deferReply().queue(
                hook -> handle(event, guild),
                err -> {
                    log.error("명령어 '{}' deferReply 실패", getName(), err);
                    ResponseUtil.replyError(event, "명령어 처리를 시작하지 못했습니다.");
                }
        );
    }

    private void handle(SlashCommandInteractionEvent event, Guild guild) {
        Action action = parseAction(event.getOption(OPT_ACTION));
        String query = parseQuery(event.getOption(OPT_QUERY));

        // Sensible defaults:
        // - if query is present and action omitted -> ADD
        // - else default -> QUEUE
        if (action == null) {
            action = (query != null && !query.isBlank()) ? Action.ADD : Action.QUEUE;
        }

        switch (action) {
            case ADD -> handleAdd(event, guild, query);
            case PAUSE -> handlePause(event, guild);
            case RESUME -> handleResume(event, guild);
            case SKIP -> handleSkip(event, guild);
            case QUEUE -> handleQueue(event, guild);
            case CLEAR -> handleClear(event, guild);
        }
    }

    private Action parseAction(OptionMapping opt) {
        if (opt == null) return null;
        String raw = opt.getAsString();
        if (raw == null) return null;
        String v = raw.trim().toLowerCase(Locale.ROOT);
        return switch (v) {
            case "add" -> Action.ADD;
            case "pause" -> Action.PAUSE;
            case "resume" -> Action.RESUME;
            case "skip" -> Action.SKIP;
            case "queue" -> Action.QUEUE;
            case "clear" -> Action.CLEAR;
            default -> null;
        };
    }

    private String parseQuery(OptionMapping opt) {
        if (opt == null) return null;
        String s = opt.getAsString();
        return s != null ? s.trim() : null;
    }

    private void handleAdd(SlashCommandInteractionEvent event, Guild guild, String query) {
        if (query == null || query.isBlank()) {
            ResponseUtil.replyEphemeralEmbed(event, EmbedUtil.createInfoEmbed("""
                    노래를 추가하려면 이렇게 써줘.
                    예) `/뮤직 action:추가 query:아이유`
                    예) `/뮤직 query:아이유` (동작 생략하면 자동으로 추가)
                    """.trim()));
            return;
        }

        GuildMusicManager musicManager = musicService.getOrCreateGuildMusicManager(guild);
        if (!musicService.ensureConnectedToVoiceChannel(event, guild, musicManager)) {
            return;
        }

        var searchResult = streamingService.search(query);
        if (searchResult.getStreamUrl() == null) {
            ResponseUtil.replyError(event, "스트리밍 URL을 찾을 수 없습니다.");
            return;
        }

        musicService.loadAndPlay(event, searchResult.getStreamUrl(), musicManager, query, searchResult.getAlbumCoverUrl());
    }

    private void handleQueue(SlashCommandInteractionEvent event, Guild guild) {
        GuildMusicManager musicManager = musicManagers.get(guild.getIdLong());
        if (musicManager == null || musicManager.getQueue().isEmpty()) {
            ResponseUtil.replyEphemeralEmbed(event, EmbedUtil.createInfoEmbed("""
                    지금은 대기열이 비어있어.
                    노래 추가: `/뮤직 query:노래제목`
                    """.trim()));
            return;
        }
        ResponseUtil.replyEmbed(event, EmbedUtil.createQueueEmbed(musicManager.getQueue()));
    }

    private void handlePause(SlashCommandInteractionEvent event, Guild guild) {
        GuildMusicManager musicManager = musicManagers.get(guild.getIdLong());
        if (musicManager == null || musicManager.getCurrentTrack() == null) {
            ResponseUtil.replyError(event, "현재 재생 중인 음악이 없습니다.");
            return;
        }
        if (musicManager.isPaused()) {
            ResponseUtil.replyError(event, "음악이 이미 일시정지 상태입니다.");
            return;
        }
        musicManager.pause();
        ResponseUtil.replyEmbed(event, EmbedUtil.createPauseEmbed(event));
    }

    private void handleResume(SlashCommandInteractionEvent event, Guild guild) {
        GuildMusicManager musicManager = musicManagers.get(guild.getIdLong());
        if (musicManager == null || musicManager.getCurrentTrack() == null) {
            ResponseUtil.replyError(event, "현재 재생 중인 음악이 없습니다.");
            return;
        }
        if (!musicManager.isPaused()) {
            ResponseUtil.replyError(event, "음악이 이미 재생 중입니다.");
            return;
        }
        musicManager.setPaused(false);
        ResponseUtil.replyEmbed(event, EmbedUtil.createResumeEmbed(event));
    }

    private void handleSkip(SlashCommandInteractionEvent event, Guild guild) {
        GuildMusicManager musicManager = musicManagers.get(guild.getIdLong());
        if (musicManager == null || !musicManager.isPlaying()) {
            ResponseUtil.replyError(event, "현재 재생 중인 트랙이 없습니다.");
            return;
        }

        var currentTrack = musicManager.getCurrentTrack();
        boolean trackSkipped = musicManager.skipCurrentTrack();
        if (!trackSkipped) {
            ResponseUtil.replyError(event, "트랙을 건너뛸 수 없습니다.");
            return;
        }
        ResponseUtil.replyEmbed(event, EmbedUtil.createSkipEmbed(event, currentTrack.getInfo().title));
    }

    private void handleClear(SlashCommandInteractionEvent event, Guild guild) {
        GuildMusicManager musicManager = musicManagers.get(guild.getIdLong());
        if (musicManager == null || !musicManager.isPlaying()) {
            ResponseUtil.replyError(event, "현재 재생 중인 음악이 없습니다.");
            return;
        }
        musicManager.stopPlayback(guild.getAudioManager());
        ResponseUtil.replyEmbed(event, EmbedUtil.createMusicStopEmbed(event));
    }
}
