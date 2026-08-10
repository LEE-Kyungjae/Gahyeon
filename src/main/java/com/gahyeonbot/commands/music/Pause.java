package com.gahyeonbot.commands.music;

import com.gahyeonbot.commands.util.*;
import com.gahyeonbot.adapters.discord.audio.GuildMusicManager;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.DiscordLocale;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import java.util.List;
import java.util.Map;

/**
 * 음악을 일시정지하는 명령어 클래스.
 * 현재 재생 중인 음악을 일시정지 상태로 만듭니다.
 * 
 * @author GahyeonBot Team
 * @version 1.0
 */
public class Pause extends AbstractCommand {

    private final Map<Long, GuildMusicManager> musicManagers;

    /**
     * Pause 명령어 생성자.
     * 
     * @param musicManagers 서버별 음악 매니저 맵
     */
    public Pause(Map<Long, GuildMusicManager> musicManagers) {
        this.musicManagers = musicManagers;
    }

    @Override
    public String getName() {
        return Description.PAUSE_NAME;
    }

    @Override
    public Map<DiscordLocale, String> getNameLocalizations() {
        return localizeKorean(Description.PAUSE_NAME_KO);
    }

    @Override
    public String getDescription() {
        return Description.PAUSE_DESC;
    }

    @Override
    public String getDetailedDescription() {
        return Description.PAUSE_DETAIL;
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
            ResponseUtil.replyError(event, "길드 정보를 가져올 수 없습니다.");
            return;
        }

        GuildMusicManager musicManager = musicManagers.get(guild.getIdLong());
        if (musicManager == null || musicManager.getCurrentTrack() == null) {
            ResponseUtil.replyError(event, "현재 재생 중인 음악이 없습니다.");
            return;
        }

        if (musicManager.isPaused()) {
            ResponseUtil.replyError(event, "음악이 이미 일시정지 상태입니다.");
        } else {
            musicManager.pause(); // 일시정지
            ResponseUtil.replyEmbed(event, EmbedUtil.createPauseEmbed(event));
        }
    }
}
