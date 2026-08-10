package com.gahyeonbot.commands.music;

import com.gahyeonbot.commands.util.*;
import com.gahyeonbot.adapters.discord.audio.GuildMusicManager;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.DiscordLocale;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import java.util.List;
import java.util.Map;

/**
 * 음악 재생을 재개하는 명령어 클래스.
 * 일시정지된 음악을 다시 재생합니다.
 * 
 * @author GahyeonBot Team
 * @version 1.0
 */
public class Resume extends AbstractCommand {
    private final Map<Long, GuildMusicManager> musicManagers;

    /**
     * Resume 명령어 생성자.
     * 
     * @param musicManagers 서버별 음악 매니저 맵
     */
    public Resume(Map<Long, GuildMusicManager> musicManagers) {
        this.musicManagers = musicManagers;
    }

    @Override
    public String getName() {
        return Description.RESUME_NAME;
    }

    @Override
    public Map<DiscordLocale, String> getNameLocalizations() {
        return localizeKorean(Description.RESUME_NAME_KO);
    }

    @Override
    public String getDescription() {
        return Description.RESUME_DESC;
    }

    @Override
    public String getDetailedDescription() {
        return Description.RESUME_DETAIL;
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

        if (!musicManager.isPaused()) {
            ResponseUtil.replyError(event, "음악이 이미 재생 중입니다.");
        } else {
            musicManager.setPaused(false); // 일시정지 해제
            var embed = EmbedUtil.createResumeEmbed(event);
            ResponseUtil.replyEmbed(event, embed);
        }
    }
}
