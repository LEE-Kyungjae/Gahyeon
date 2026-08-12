package com.gahyeonbot.adapters.discord.command;
import com.gahyeonbot.commands.util.ICommand;
import jakarta.annotation.PostConstruct;
import net.dv8tion.jda.api.interactions.DiscordLocale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 봇의 모든 명령어를 등록하고 관리하는 클래스.
 * 음악, 예약, 기타 명령어들을 생성하고 등록합니다.
 *
 * @author GahyeonBot Team
 * @version 1.0
 */
@Component
public class CommandRegistry {
    private static final Logger logger = LoggerFactory.getLogger(CommandRegistry.class);
    static final Set<String> REQUIRED_COMPATIBILITY_COMMANDS = Set.of(
            "agent", "assistant", "clean-chat", "dm-optin", "dm-optout", "dm-status",
            "gahyeona", "gather", "help", "link-desktop", "setup", "tts", "weather",
            "cancel-schedule", "kick-bots", "list-schedules", "schedule-kick",
            "schedule-kickall", "music");
    static final Map<String, String> REQUIRED_KOREAN_NAMES = Map.ofEntries(
            Map.entry("agent", "에이전트"),
            Map.entry("assistant", "비서"),
            Map.entry("clean-chat", "채팅정리"),
            Map.entry("dm-optin", "dm수신"),
            Map.entry("dm-optout", "dm거부"),
            Map.entry("dm-status", "dm상태"),
            Map.entry("gahyeona", "가현아"),
            Map.entry("gather", "모여"),
            Map.entry("help", "도움말"),
            Map.entry("link-desktop", "데스크톱연결"),
            Map.entry("setup", "설정"),
            Map.entry("tts", "티"),
            Map.entry("weather", "날씨"),
            Map.entry("cancel-schedule", "퇴장취소"),
            Map.entry("kick-bots", "봇퇴장"),
            Map.entry("list-schedules", "퇴장조회"),
            Map.entry("schedule-kick", "퇴장"),
            Map.entry("schedule-kickall", "함께퇴장"),
            Map.entry("music", "뮤직"));

    private final List<ICommand> commands;

    public CommandRegistry(List<ICommand> commands) {
        validateCompatibility(commands);
        this.commands = List.copyOf(commands);
    }

    static void validateCompatibility(List<ICommand> commands) {
        if (commands == null) throw new IllegalStateException("Discord command list is missing");
        Set<String> names = new HashSet<>();
        Map<String, String> koreanNames = new HashMap<>();
        for (ICommand command : commands) {
            if (command == null || command.getName() == null || command.getName().isBlank()) {
                throw new IllegalStateException("Discord command has no stable name");
            }
            String name = command.getName();
            if (!names.add(name)) {
                throw new IllegalStateException("Duplicate Discord command name: " + name);
            }
            if (command.getDescription() == null || command.getDescription().isBlank()) {
                throw new IllegalStateException("Discord command has no description: " + name);
            }
            String korean = command.getNameLocalizations().get(DiscordLocale.KOREAN);
            if (korean != null) {
                String previous = koreanNames.putIfAbsent(korean, name);
                if (previous != null) {
                    throw new IllegalStateException(
                            "Duplicate Korean Discord command name: " + korean
                                    + " (" + previous + ", " + name + ")");
                }
            }
        }
        Set<String> missing = new HashSet<>(REQUIRED_COMPATIBILITY_COMMANDS);
        missing.removeAll(names);
        if (!missing.isEmpty()) {
            throw new IllegalStateException("Required Discord commands are missing: " + missing);
        }
        REQUIRED_KOREAN_NAMES.forEach((name, expected) -> {
            ICommand command = commands.stream()
                    .filter(candidate -> name.equals(candidate.getName()))
                    .findFirst()
                    .orElseThrow();
            String actual = command.getNameLocalizations().get(DiscordLocale.KOREAN);
            if (!expected.equals(actual)) {
                throw new IllegalStateException(
                        "Discord command '" + name + "' must preserve Korean name '"
                                + expected + "' but was '" + actual + "'");
            }
        });
        ICommand assistant = commands.stream()
                .filter(candidate -> "assistant".equals(candidate.getName()))
                .findFirst()
                .orElseThrow();
        boolean preservesVoiceExit = assistant.getOptions() != null
                && assistant.getOptions().stream()
                .filter(option -> "action".equals(option.getName()))
                .flatMap(option -> option.getChoices().stream())
                .anyMatch(choice -> "stop".equals(choice.getAsString()));
        if (!preservesVoiceExit) {
            throw new IllegalStateException(
                    "Discord assistant command must preserve action choice 'stop'");
        }
    }

    @PostConstruct
    void logRegisteredCommands() {
        logger.info("총 {}개의 명령어 Bean이 감지되었습니다.", commands.size());
        commands.forEach(cmd -> logger.info("등록된 명령어 Bean: {}", cmd.getName()));
    }

    public List<ICommand> getCommands() {
        return List.copyOf(commands);
    }
}
