package com.gahyeonbot.commands.moderation;

import com.gahyeonbot.commands.util.AbstractCommand;
import com.gahyeonbot.commands.util.ICommand;
import com.gahyeonbot.commands.util.Description;
import com.gahyeonbot.commands.util.ResponseUtil;
import com.gahyeonbot.commands.util.EmbedUtil;
import com.gahyeonbot.adapters.discord.scheduler.LeaveSchedulerManager;
import lombok.extern.slf4j.Slf4j;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.DiscordLocale;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 예약된 추방을 취소하는 명령어.
 * 관리자 권한이 필요하며, 지정된 사용자의 추방 예약을 취소합니다.
 *
 * @author GahyeonBot Team
 * @version 1.0
 */
@Slf4j
@Component
public class CancelKick extends AbstractCommand implements ICommand {
    private final LeaveSchedulerManager schedulerManager;

    public CancelKick(LeaveSchedulerManager schedulerManager) {
        this.schedulerManager = schedulerManager;
    }

    @Override
    public String getName() {
        return Description.CANCLEOUT_NAME;
    }

    @Override
    public Map<DiscordLocale, String> getNameLocalizations() {
        return localizeKorean(Description.CANCLEOUT_NAME_KO);
    }

    @Override
    public String getDescription() {
        return Description.CANCLEOUT_DESC;
    }

    @Override
    public String getDetailedDescription() {
        return Description.CANCLEOUT_DETAIL;
    }

    @Override
    public void execute(SlashCommandInteractionEvent event) {
        log.info("명령어 실행 시작: {}", getName());

        if (event.getGuild() == null || event.getMember() == null) {
            ResponseUtil.replyError(event, "이 명령어는 서버에서만 사용할 수 있습니다.");
            return;
        }

        if (!event.getMember().hasPermission(Permission.KICK_MEMBERS)) {
            ResponseUtil.replyError(event, "이 명령어를 사용할 권한이 없습니다.");
            return;
        }

        event.deferReply().queue();

        OptionMapping idOption = event.getOption("id");
        if (idOption == null) {
            ResponseUtil.replyError(event, "취소할 예약 ID를 입력하세요.");
            return;
        }

        int reservationId = idOption.getAsInt();
        boolean cancelled = schedulerManager.cancelReservation(reservationId);

        if (cancelled) {
            var embed = EmbedUtil.createReservationCancelledEmbed(reservationId);
            ResponseUtil.replyEmbed(event, embed);
        } else {
            ResponseUtil.replyError(event, "해당 ID의 예약을 찾을 수 없습니다. 예약이 이미 취소되었거나 존재하지 않을 수 있습니다.");
        }
    }

    @Override
    public List<OptionData> getOptions() {
        return List.of(
                new OptionData(OptionType.INTEGER, "id", "취소할 예약 ID", true)
        );
    }
}
