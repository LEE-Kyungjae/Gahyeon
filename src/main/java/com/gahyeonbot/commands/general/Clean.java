package com.gahyeonbot.commands.general;

import com.gahyeonbot.commands.util.AbstractCommand;
import com.gahyeonbot.commands.util.Description;
import com.gahyeonbot.commands.util.ResponseUtil;
import com.gahyeonbot.adapters.discord.moderation.MessageCleanService;
import lombok.extern.slf4j.Slf4j;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.DiscordLocale;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 채팅 메시지를 삭제하는 명령어 클래스.
 * 전체 채팅 또는 사용자 본인의 채팅을 지정된 수만큼 삭제할 수 있습니다.
 *
 * @author GahyeonBot Team
 * @version 1.0
 */
@Slf4j
@Component
public class Clean extends AbstractCommand {
    private final MessageCleanService messageCleanService;

    public Clean(MessageCleanService messageCleanService) {
        this.messageCleanService = messageCleanService;
    }

    @Override
    public String getName() {
        return Description.CLEAN_NAME;
    }

    @Override
    public Map<DiscordLocale, String> getNameLocalizations() {
        return localizeKorean(Description.CLEAN_NAME_KO);
    }

    @Override
    public String getDescription() {
        return Description.CLEAN_DESC;
    }

    @Override
    public String getDetailedDescription() {
        return Description.CLEAN_DETAIL;
    }

    @Override
    public List<OptionData> getOptions() {
        return List.of(
                new OptionData(OptionType.INTEGER, "line", "최근순으로 채팅을 삭제합니다. (최대 1000줄)", false)
                        .setMinValue(1)
                        .setMaxValue(1000),
                new OptionData(OptionType.INTEGER, "my", "최근 1000개의 채팅에서 최신순으로 지정한 값의 내 채팅을 삭제합니다.", false)
                        .setMinValue(1)
                        .setMaxValue(1000)
        );
    }

    @Override
    public void execute(SlashCommandInteractionEvent event) {
        log.info("명령어 실행 시작: {}", getName());

        if (event.getGuild() == null || event.getMember() == null) {
            ResponseUtil.replyError(event, "이 명령어는 서버에서만 사용할 수 있습니다.");
            return;
        }

        if (!event.getChannelType().isGuild()) {
            ResponseUtil.replyError(event, "이 명령어는 서버 텍스트 채널에서만 사용할 수 있습니다.");
            return;
        }

        GuildMessageChannel channel = (GuildMessageChannel) event.getChannel();

        if (!event.getGuild().getSelfMember().hasPermission(channel, Permission.MESSAGE_MANAGE)) {
            ResponseUtil.replyError(event, "이 채널에서 메시지를 삭제할 권한이 없습니다.");
            return;
        }

        event.deferReply().queue();

        OptionMapping lineOption = event.getOption("line");
        OptionMapping myOption = event.getOption("my");

        if (myOption != null && lineOption != null) {
            ResponseUtil.replyError(event, "옵션 'line'과 'my'를 동시에 선택할 수 없습니다. 하나만 선택해주세요.");
            return;
        }

        if (myOption != null) {
            int linesToDelete = myOption.getAsInt();
            messageCleanService.deleteUserMessages(channel, event, linesToDelete);
        } else if (lineOption != null) {
            int linesToDelete = lineOption.getAsInt();
            messageCleanService.deleteMessages(channel, event, linesToDelete);
        } else {
            ResponseUtil.replyError(event, "삭제할 줄 수를 입력해주세요.");
        }
    }
}
