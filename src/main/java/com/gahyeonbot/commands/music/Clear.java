package com.gahyeonbot.commands.music;

import com.gahyeonbot.commands.util.*;
import com.gahyeonbot.adapters.discord.audio.GuildMusicManager;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.DiscordLocale;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import java.util.List;
import java.util.Map;

/**
 * 음악 대기열을 초기화하는 명령어 클래스.
 * 현재 재생 중인 음악을 포함한 모든 대기열을 비웁니다.
 * 
 * @author GahyeonBot Team
 * @version 1.0
 */
public class Clear extends AbstractCommand {

    private final Map<Long, GuildMusicManager> musicManagers;

    /**
     * Clear 명령어 생성자.
     * 
     * @param musicManagers 서버별 음악 매니저 맵
     */
    public Clear(Map<Long, GuildMusicManager> musicManagers) {
        this.musicManagers = musicManagers;
    }

    @Override
    public String getName() {
        return Description.CLEAR_NAME;
    }

    @Override
    public Map<DiscordLocale, String> getNameLocalizations() {
        return localizeKorean(Description.CLEAR_NAME_KO);
    }

    @Override
    public String getDescription() {
        return Description.CLEAR_DESC;
    }

    @Override
    public String getDetailedDescription() {
        return Description.CLEAR_DETAIL;
    }

    @Override
    public List<OptionData> getOptions() {
        return List.of();
    }

    @Override
    public void execute(SlashCommandInteractionEvent event) {
        logger.info("명령어 실행 시작: {}", getName());
        var guild = event.getGuild();

        if (guild == null) {
            ResponseUtil.replyError(event, "길드를 찾을 수 없습니다.");
            return;
        }

        var musicManager = musicManagers.get(guild.getIdLong());

        if (musicManager == null || !musicManager.isPlaying()) {
            ResponseUtil.replyError(event, "현재 재생 중인 음악이 없습니다.");
            return;
        }

        // 음악 정지 및 대기열 초기화
        musicManager.stopPlayback(guild.getAudioManager());

        // 응답 전송
        var embed = EmbedUtil.createMusicStopEmbed(event);
        ResponseUtil.replyEmbed(event, embed);
    }
}
