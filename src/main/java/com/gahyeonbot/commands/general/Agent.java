package com.gahyeonbot.commands.general;

import com.gahyeonbot.adapters.discord.DiscordIdentityMapper;
import com.gahyeonbot.commands.util.AbstractCommand;
import com.gahyeonbot.commands.util.ResponseUtil;
import com.gahyeonbot.services.ai.agent.AgentControlService;
import com.gahyeonbot.services.ai.agent.AgentResult;
import com.gahyeonbot.services.ai.agent.AgentRunView;
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
public class Agent extends AbstractCommand {
    private final AgentControlService controlService;
    private final DiscordIdentityMapper identityMapper;

    @Override public String getName() { return "agent"; }
    @Override public Map<DiscordLocale, String> getNameLocalizations() { return localizeKorean("에이전트"); }
    @Override public String getDescription() { return "에이전트 실행 상태와 승인을 관리합니다."; }
    @Override public String getDetailedDescription() {
        return "/에이전트 action:상태·승인·거부·취소";
    }

    @Override
    public List<OptionData> getOptions() {
        return List.of(
                new OptionData(OptionType.STRING, "action", "실행할 동작", true)
                        .setNameLocalization(DiscordLocale.KOREAN, "동작")
                        .addChoice("상태", "status")
                        .addChoice("승인하고 재개", "approve")
                        .addChoice("거부", "reject")
                        .addChoice("취소", "cancel"),
                new OptionData(OptionType.STRING, "id", "실행 ID 또는 승인 ID", false)
                        .setNameLocalization(DiscordLocale.KOREAN, "아이디"));
    }

    @Override
    public void execute(SlashCommandInteractionEvent event) {
        var actorId = identityMapper.toActorId(
                event.getUser().getIdLong(), event.getUser().getName());
        String action = event.getOption("action").getAsString();
        String id = event.getOption("id") == null ? null : event.getOption("id").getAsString();
        try {
            switch (action) {
                case "status" -> {
                    AgentRunView view = id == null
                            ? controlService.latest(actorId)
                            : controlService.get(id, actorId);
                    ResponseUtil.replySuccess(event, format(view));
                }
                case "approve" -> {
                    requireId(id, "승인 ID");
                    event.deferReply(true).queue();
                    AgentResult result = controlService.approveAndResume(id, actorId);
                    event.getHook().editOriginal("승인 후 실행 완료\nrun: `" + result.runId()
                            + "`\n\n" + limited(result.content())).queue();
                }
                case "reject" -> {
                    requireId(id, "승인 ID");
                    ResponseUtil.replySuccess(event, format(controlService.reject(id, actorId)));
                }
                case "cancel" -> {
                    requireId(id, "실행 ID");
                    ResponseUtil.replySuccess(event, format(controlService.cancel(id, actorId)));
                }
                default -> ResponseUtil.replyError(event, "지원하지 않는 동작입니다.");
            }
        } catch (Exception e) {
            ResponseUtil.replyError(event, e.getMessage() == null ? "에이전트 제어에 실패했습니다." : e.getMessage());
        }
    }

    private static void requireId(String id, String label) {
        if (id == null || id.isBlank()) throw new IllegalArgumentException(label + "를 입력해 주세요.");
    }

    private static String format(AgentRunView view) {
        StringBuilder text = new StringBuilder()
                .append("run: `").append(view.runId()).append("`\n")
                .append("상태: ").append(view.status()).append("\n")
                .append("단계: ").append(view.currentStep()).append("/").append(view.maxSteps());
        for (var approval : view.approvals()) {
            text.append("\n승인: `").append(approval.approvalId()).append("` ")
                    .append(approval.toolName()).append(" (").append(approval.status()).append(")");
        }
        if (view.errorCode() != null) text.append("\n오류: ").append(view.errorCode());
        return text.toString();
    }

    private static String limited(String value) {
        if (value == null) return "";
        return value.length() <= 1700 ? value : value.substring(0, 1697) + "...";
    }
}
