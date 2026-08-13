package com.gahyeonbot.commands.general;

import com.gahyeonbot.commands.util.AbstractCommand;
import com.gahyeonbot.commands.util.ResponseUtil;
import com.gahyeonbot.adapters.discord.voice.VoiceAssistantService;
import lombok.RequiredArgsConstructor;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.DiscordLocale;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class Assistant extends AbstractCommand {
    private final VoiceAssistantService assistantService;

    @Override public String getName() { return "assistant"; }
    @Override public Map<DiscordLocale, String> getNameLocalizations() { return localizeKorean("비서"); }
    @Override public String getDescription() { return "음성 채널에서 AI 업무 비서 세션을 관리합니다."; }
    @Override public String getDetailedDescription() { return "/비서 action:시작 또는 종료"; }

    @Override
    public List<OptionData> getOptions() {
        return List.of(new OptionData(OptionType.STRING, "action", "실행할 동작", true)
                .setNameLocalization(DiscordLocale.KOREAN, "동작")
                .addChoice("시작", "start")
                .addChoice("종료", "stop")
                .addChoice("상태", "status"));
    }

    @Override
    public void execute(SlashCommandInteractionEvent event) {
        if (event.getGuild() == null) {
            ResponseUtil.replyError(event, "서버 안에서만 사용할 수 있습니다.");
            return;
        }
        String action = event.getOption("action").getAsString();
        switch (action) {
            case "start" -> {
                var result = assistantService.start(event.getGuild(), event.getMember(), event.getChannel());
                if (result.started()) ResponseUtil.replySuccess(event, result.message());
                else ResponseUtil.replyError(event, result.message());
            }
            case "stop" -> {
                if (assistantService.stop(event.getGuild())) ResponseUtil.replySuccess(event, "음성 비서 세션을 종료했습니다.");
                else ResponseUtil.replyError(event, "실행 중인 비서 세션이 없습니다.");
            }
            case "status" -> ResponseUtil.replySuccess(event,
                    assistantService.isRunning(event.getGuild().getIdLong()) ? "비서 세션 실행 중" : "비서 세션 정지 상태");
            default -> ResponseUtil.replyError(event, "지원하지 않는 동작입니다.");
        }
    }
}
