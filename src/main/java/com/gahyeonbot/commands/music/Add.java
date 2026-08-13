package com.gahyeonbot.commands.music;

import com.gahyeonbot.commands.util.AbstractCommand;
import com.gahyeonbot.commands.util.ICommand;
import com.gahyeonbot.commands.util.Description;
import com.gahyeonbot.commands.util.ResponseUtil;
import com.gahyeonbot.adapters.discord.music.MusicService;
import com.gahyeonbot.adapters.discord.music.StreamingService;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.DiscordLocale;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import java.util.List;
import java.util.Map;

/**
 * 음악을 대기열에 추가하는 명령어 클래스.
 * 사용자가 입력한 노래 제목을 검색하여 음악 대기열에 추가합니다.
 * 
 * @author GahyeonBot Team
 * @version 1.0
 */
public class Add extends AbstractCommand {
    private final MusicService musicService;
    private final StreamingService streamingService;

    /**
     * Add 명령어 생성자.
     * 
     * @param musicService 음악 서비스
     * @param streamingService 스트리밍 서비스
     */
    public Add(MusicService musicService, StreamingService streamingService) {
        this.musicService = musicService;
        this.streamingService = streamingService;
    }

    @Override
    public String getName() {
        return Description.ADD_NAME;
    }

    @Override
    public Map<DiscordLocale, String> getNameLocalizations() {
        return localizeKorean(Description.ADD_NAME_KO);
    }

    @Override
    public String getDescription() {
        return Description.ADD_DESC;
    }

    @Override
    public String getDetailedDescription() {
        return Description.ADD_DETAIL;
    }

    @Override
    public List<OptionData> getOptions() {
        return List.of(
                new OptionData(OptionType.STRING, "query", "노래 제목을 입력하세요.", true)
                        .setNameLocalization(DiscordLocale.KOREAN, "노래정보")
        );
    }

    @Override
    public void execute(SlashCommandInteractionEvent event) {
        logger.info("명령어 실행 시작: {}", getName());
        OptionMapping queryOption = event.getOption("query");
        if (queryOption == null) {
            queryOption = event.getOption("노래정보");
        }
        String query = queryOption != null ? queryOption.getAsString().trim() : "";
        if (query.isEmpty()) {
            ResponseUtil.replyError(event, "유효한 노래 정보를 입력하세요.");
            return;
        }

        var guild = event.getGuild();
        if (guild == null) {
            ResponseUtil.replyError(event, "길드를 찾을 수 없습니다.");
            return;
        }

        event.deferReply().queue(
                hook -> handleAdd(event, guild, query),
                error -> {
                    logger.error("명령어 '{}' 응답 예약 실패", getName(), error);
                    ResponseUtil.replyError(event, "명령어 처리를 시작하지 못했습니다.");
                }
        );
    }

    private void handleAdd(SlashCommandInteractionEvent event, Guild guild, String query) {
        var musicManager = musicService.getOrCreateGuildMusicManager(guild);

        if (!musicService.ensureConnectedToVoiceChannel(event, guild, musicManager)) {
            return;
        }

        // 스트리밍 URL과 앨범 커버 검색
        var searchResult = streamingService.search(query);
        if (searchResult.getStreamUrl() == null) {
            ResponseUtil.replyError(event, "스트리밍 URL을 찾을 수 없습니다.");
            return;
        }

        // 음악 로드 및 처리 위임
        musicService.loadAndPlay(event, searchResult.getStreamUrl(), musicManager, query, searchResult.getAlbumCoverUrl());
    }
}
