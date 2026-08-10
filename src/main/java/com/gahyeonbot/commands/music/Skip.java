package com.gahyeonbot.commands.music;

import com.gahyeonbot.commands.util.*;
import com.gahyeonbot.adapters.discord.audio.GuildMusicManager;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.DiscordLocale;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import java.util.List;
import java.util.Map;

/**
 * 현재 재생 중인 음악을 건너뛰는 명령어 클래스.
 * 다음 곡으로 넘어갑니다.
 * 
 * @author GahyeonBot Team
 * @version 1.0
 */
public class Skip extends AbstractCommand {

    private final Map<Long, GuildMusicManager> musicManagers;

    /**
     * Skip 명령어 생성자.
     * 
     * @param musicManagers 서버별 음악 매니저 맵
     */
    public Skip(Map<Long, GuildMusicManager> musicManagers) {
        this.musicManagers = musicManagers;
    }

    @Override
    public String getName() {
        return Description.SKIP_NAME;
    }

    @Override
    public Map<DiscordLocale, String> getNameLocalizations() {
        return localizeKorean(Description.SKIP_NAME_KO);
    }

    @Override
    public String getDescription() {
        return Description.SKIP_DESC;
    }

    @Override
    public String getDetailedDescription() {
        return Description.SKIP_DETAIL;
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
            ResponseUtil.replyError(event, "현재 재생 중인 트랙이 없습니다.");
            return;
        }

        var currentTrack = musicManager.getCurrentTrack();
        boolean trackSkipped = musicManager.skipCurrentTrack();

        if (trackSkipped) {
            var embed = EmbedUtil.createSkipEmbed(event, currentTrack.getInfo().title);
            ResponseUtil.replyEmbed(event, embed);
        } else {
            ResponseUtil.replyError(event, "트랙을 건너뛸 수 없습니다.");
        }
    }
}
