package com.gahyeonbot.commands.moderation;

import com.gahyeonbot.commands.util.AbstractCommand;
import com.gahyeonbot.commands.util.Description;
import com.gahyeonbot.commands.util.EmbedUtil;
import com.gahyeonbot.commands.util.ICommand;
import com.gahyeonbot.commands.util.ResponseUtil;
import com.gahyeonbot.adapters.discord.moderation.BotManagerService;
import lombok.extern.slf4j.Slf4j;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.DiscordLocale;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 모든 봇을 서버에서 추방하는 명령어.
 * 관리자 권한이 필요하며, 현재 보이스 채널에 있는 봇을 즉시 추방합니다.
 *
 * @author GahyeonBot Team
 * @version 1.0
 */
@Slf4j
@Component
public class KickAllBots extends AbstractCommand implements ICommand {
    private final BotManagerService botManagerService;

    public KickAllBots(BotManagerService botManagerService) {
        this.botManagerService = botManagerService;
    }

    @Override
    public String getName() {
        return Description.BOTOUT_NAME;
    }

    @Override
    public Map<DiscordLocale, String> getNameLocalizations() {
        return localizeKorean(Description.BOTOUT_NAME_KO);
    }

    @Override
    public String getDescription() {
        return Description.BOTOUT_DESC;
    }

    @Override
    public String getDetailedDescription() {
        return Description.BOTOUT_DETAIL;
    }

    @Override
    public void execute(SlashCommandInteractionEvent event) {
        log.info("명령어 실행 시작: {}", getName());

        if (event.getGuild() == null || event.getMember() == null) {
            ResponseUtil.replyError(event, "이 명령어는 서버에서만 사용할 수 있습니다.");
            return;
        }

        Member executor = event.getMember();

        if (!executor.hasPermission(Permission.KICK_MEMBERS)) {
            ResponseUtil.replyError(event, "이 명령어를 사용할 권한이 없습니다.");
            return;
        }

        event.deferReply().queue();

        if (executor.getVoiceState() == null || executor.getVoiceState().getChannel() == null) {
            ResponseUtil.replyError(event, "보이스 채널에 접속 중이어야 명령어를 사용할 수 있습니다.");
            return;
        }

        var channel = executor.getVoiceState().getChannel();

        List<Member> removedBots = botManagerService.removeBotsFromChannel(event, channel);

        if (removedBots.isEmpty()) {
            ResponseUtil.replyError(event, "현재 채널에 봇이 없습니다.");
        } else {
            var embed = EmbedUtil.createBotOutEmbed(removedBots);
            ResponseUtil.replyEmbed(event, embed);
        }
    }

    @Override
    public List<OptionData> getOptions() {
        return List.of();
    }
}
