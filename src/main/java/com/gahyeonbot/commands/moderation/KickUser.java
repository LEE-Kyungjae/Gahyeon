package com.gahyeonbot.commands.moderation;

import com.gahyeonbot.commands.util.AbstractCommand;
import com.gahyeonbot.commands.util.Description;
import com.gahyeonbot.commands.util.EmbedUtil;
import com.gahyeonbot.commands.util.ICommand;
import com.gahyeonbot.commands.util.ResponseUtil;
import com.gahyeonbot.adapters.discord.scheduler.LeaveSchedulerManager;
import com.gahyeonbot.models.Reservation;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.DiscordLocale;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.Map;

/**
 * 특정 사용자를 서버에서 추방하는 명령어.
 * 관리자 권한이 필요하며, 지정된 시간 후에 사용자를 추방합니다.
 * 
 * @author GahyeonBot Team
 * @version 1.0
 */
@Component
public class KickUser extends AbstractCommand implements ICommand {
    private final LeaveSchedulerManager schedulerManager;

    /**
     * KickUser 생성자.
     * 
     * @param schedulerManager 스케줄러 매니저
     */
    public KickUser(LeaveSchedulerManager schedulerManager) {
        this.schedulerManager = schedulerManager;
    }

    @Override
    public String getName() {
        return Description.OUT_NAME;
    }

    @Override
    public Map<DiscordLocale, String> getNameLocalizations() {
        return localizeKorean(Description.OUT_NAME_KO);
    }

    @Override
    public String getDescription() {
        return Description.OUT_DESC;
    }

    @Override
    public String getDetailedDescription() {
        return Description.OUT_DETAIL;
    }

    @Override
    public void execute(SlashCommandInteractionEvent event) {
        logger.info("명령어 실행 시작: {}", getName());

        var requester = event.getMember();

        if (requester == null || requester.getVoiceState() == null || requester.getVoiceState().getChannel() == null) {
            ResponseUtil.replyError(event, "오류: 보이스 채널에 접속 중이 아닙니다.");
            return;
        }

        Member target = resolveTargetMember(event, requester);
        if (target == null) {
            return;
        }

        if (!requester.getId().equals(target.getId()) && !requester.hasPermission(Permission.KICK_MEMBERS)) {
            ResponseUtil.replyError(event, "다른 사용자의 취침 예약은 관리자 권한이 필요해요.");
            return;
        }

        if (target.getVoiceState() == null || target.getVoiceState().getChannel() == null
                || !target.getVoiceState().getChannel().equals(requester.getVoiceState().getChannel())) {
            ResponseUtil.replyError(event, "대상 사용자가 같은 보이스 채널에 접속해 있지 않습니다.");
            return;
        }

        // 시간 처리
        int minutes = resolveTime(event);
        if (minutes <= 0) {
            ResponseUtil.replyError(event, "시간을 올바르게 지정해주세요. (preset 또는 time)");
            return;
        }

        long reservationId = schedulerManager.generateId();
        var nickname = target.getEffectiveName();

        // 예약 생성
        var task = schedulerManager.scheduleTask(() -> {
            target.getGuild().kickVoiceMember(target).queue(
                    success -> ResponseUtil.sendMessageToChannel(event, nickname + "님을 보이스 채널에서 내보냈습니다."),
                    failure -> ResponseUtil.sendMessageToChannel(event, "오류 발생: " + failure.getMessage())
            );
            schedulerManager.completeReservation(reservationId);
        }, minutes, TimeUnit.MINUTES);

        // 예약 추가
        schedulerManager.addReservation(new Reservation(
                reservationId,
                requester.getIdLong(),
                nickname,
                requester.getGuild().getIdLong(),
                task,
                "개인 취침 예약",
                minutes
        ));

        // 응답 전송
        var embed = EmbedUtil.createReservationEmbed(reservationId, nickname, minutes);
        ResponseUtil.replyEmbed(event, embed);
    }

    private Member resolveTargetMember(SlashCommandInteractionEvent event, Member requester) {
        var userOption = event.getOption("user");
        if (userOption == null) {
            return requester;
        }
        var target = userOption.getAsMember();
        if (target == null) {
            ResponseUtil.replyError(event, "대상 사용자를 찾을 수 없습니다.");
            return null;
        }
        return target;
    }

    private int resolveTime(SlashCommandInteractionEvent event) {
        var presetOption = event.getOption("preset");
        var timeOption = event.getOption("time");

        if (presetOption != null) {
            return Integer.parseInt(presetOption.getAsString());
        }
        if (timeOption != null) {
            return timeOption.getAsInt();
        }
        return -1; // 시간 미지정
    }

    @Override
    public List<OptionData> getOptions() {
        List<OptionData> options = new ArrayList<>();
        options.add(new OptionData(OptionType.USER, "user", "취침 예약 대상 (미입력 시 본인)", false));
        options.add(new OptionData(OptionType.STRING, "preset", "빠른 시간 선택", false)
                .addChoice("30분", "30")
                .addChoice("1시간", "60")
                .addChoice("90분", "90")
                .addChoice("2시간", "120"));
        options.add(new OptionData(OptionType.INTEGER, "time", "직접 입력(분)", false)
                .setMinValue(1)
                .setMaxValue(240));
        return options;
    }
}
