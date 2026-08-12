package com.gahyeonbot.commands.general;

import com.gahyeonbot.adapters.discord.DiscordIdentityMapper;
import com.gahyeonbot.application.identity.IdentityLinkUseCase;
import com.gahyeonbot.commands.util.AbstractCommand;
import lombok.RequiredArgsConstructor;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.DiscordLocale;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import org.springframework.stereotype.Component;

import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.List;

@Component
@RequiredArgsConstructor
public class LinkDesktop extends AbstractCommand {
    private final DiscordIdentityMapper identities;
    private final IdentityLinkUseCase links;

    @Override public String getName() { return "link-desktop"; }
    @Override public Map<DiscordLocale, String> getNameLocalizations() {
        return localizeKorean("데스크톱연결");
    }
    @Override public String getDescription() { return "Discord 계정을 Gahyeon Desktop에 연결합니다."; }
    @Override public String getDetailedDescription() {
        return "/데스크톱연결 action:코드발급·기기목록·이름변경·기기폐기";
    }
    @Override public List<OptionData> getOptions() {
        return List.of(
                new OptionData(OptionType.STRING, "action", "Desktop 연결 관리 작업", false)
                        .setNameLocalization(DiscordLocale.KOREAN, "동작")
                        .addChoice("연결 코드 발급", "issue")
                        .addChoice("연결 기기 목록", "list")
                        .addChoice("기기 이름 변경", "rename")
                        .addChoice("기기 연결 폐기", "revoke"),
                new OptionData(OptionType.STRING, "device", "관리할 기기 ID", false)
                        .setNameLocalization(DiscordLocale.KOREAN, "기기")
                        .setMaxLength(36),
                new OptionData(OptionType.STRING, "label", "새 기기 이름", false)
                        .setNameLocalization(DiscordLocale.KOREAN, "이름")
                        .setMaxLength(100));
    }

    @Override
    public void execute(SlashCommandInteractionEvent event) {
        try {
            var actor = identities.toActorId(event.getUser().getIdLong(), event.getUser().getName());
            String action = event.getOption("action") == null
                    ? "issue" : event.getOption("action").getAsString();
            switch (action) {
                case "issue" -> {
                    var issued = links.issueDesktopLink(actor);
                    event.reply("Desktop 연결 코드: `" + issued.code() + "`\n"
                                    + "10분 안에 한 번만 사용할 수 있습니다. 다른 사람에게 보여주지 마세요.\n"
                                    + "만료: " + issued.expiresAt().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME))
                            .setEphemeral(true).queue();
                }
                case "list" -> {
                    var devices = links.listDesktopDevices(actor);
                    String body = devices.isEmpty() ? "연결된 Desktop 기기가 없습니다."
                            : devices.stream().map(device -> "**" + safeDisplay(device.label()) + "**\n`"
                                    + device.id() + "`\n" + safeDisplay(abbreviate(device.installationId()))
                                    + "\n등록 " + device.createdAt().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                                    + " · 최근 " + (device.lastUsedAt() == null ? "없음"
                                    : device.lastUsedAt().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME))
                                    + "\n만료 " + device.expiresAt().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME))
                            .collect(java.util.stream.Collectors.joining("\n\n"));
                    event.reply(body).setEphemeral(true).queue();
                }
                case "rename" -> {
                    String device = option(event, "device");
                    String label = option(event, "label");
                    if (device == null || label == null) {
                        event.reply("기기 ID와 새 이름을 모두 입력해 주세요.")
                                .setEphemeral(true).queue();
                    } else if (links.renameDesktopDevice(actor, device, label)) {
                        event.reply("Desktop 기기 이름을 변경했습니다.")
                                .setEphemeral(true).queue();
                    } else {
                        event.reply("활성 상태인 본인 기기를 찾지 못했거나 이름이 올바르지 않습니다.")
                                .setEphemeral(true).queue();
                    }
                }
                case "revoke" -> {
                    String device = event.getOption("device") == null
                            ? null : event.getOption("device").getAsString();
                    if (device == null || device.isBlank()) {
                        event.reply("폐기할 기기 ID를 입력해 주세요.").setEphemeral(true).queue();
                    } else if (links.revokeDesktopDevice(actor, device)) {
                        event.reply("해당 Desktop credential을 폐기했습니다.")
                                .setEphemeral(true).queue();
                    } else {
                        event.reply("활성 상태인 본인 기기를 찾지 못했습니다.")
                                .setEphemeral(true).queue();
                    }
                }
                default -> event.reply("지원하지 않는 연결 관리 작업입니다.")
                        .setEphemeral(true).queue();
            }
        } catch (Exception error) {
            event.reply("Desktop 연결 관리 작업에 실패했습니다.").setEphemeral(true).queue();
        }
    }

    private static String abbreviate(String value) {
        return value.length() <= 60 ? value : value.substring(0, 57) + "...";
    }

    private static String option(SlashCommandInteractionEvent event, String name) {
        return event.getOption(name) == null ? null : event.getOption(name).getAsString();
    }

    private static String safeDisplay(String value) {
        String safe = value.replaceAll("[`*_~|>@]", "").trim();
        return safe.isEmpty() ? "Desktop" : safe;
    }
}
