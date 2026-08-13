package com.gahyeonbot.adapters.discord.command;

import com.gahyeonbot.commands.util.ICommand;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.DiscordLocale;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CommandRegistryCompatibilityTest {

    @Test
    void acceptsTheCompleteLegacyCommandSurface() {
        CommandRegistry.validateCompatibility(completeCommands());
    }

    @Test
    void refusesToStartWhenLeaveCommandDisappears() {
        var commands = completeCommands();
        commands.removeIf(command -> command.getName().equals("schedule-kick"));

        assertThatThrownBy(() -> CommandRegistry.validateCompatibility(commands))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("schedule-kick");
    }

    @Test
    void preservesTheKoreanLeaveCommandName() {
        var commands = completeCommands();
        replace(commands, "schedule-kick", "나가기");

        assertThatThrownBy(() -> CommandRegistry.validateCompatibility(commands))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must preserve Korean name '퇴장'");
    }

    @Test
    void preservesTheConsolidatedMusicCommand() {
        var commands = completeCommands();
        commands.removeIf(command -> command.getName().equals("music"));

        assertThatThrownBy(() -> CommandRegistry.validateCompatibility(commands))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("music");
    }

    @Test
    void refusesToStartWhenVoiceAssistantExitChoiceDisappears() {
        var commands = completeCommands();
        commands.removeIf(command -> command.getName().equals("assistant"));
        commands.add(command("assistant", "비서"));

        assertThatThrownBy(() -> CommandRegistry.validateCompatibility(commands))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("action choice 'stop'");
    }

    @Test
    void rejectsDuplicateStableNames() {
        var commands = completeCommands();
        commands.add(command("schedule-kick", "다른퇴장"));

        assertThatThrownBy(() -> CommandRegistry.validateCompatibility(commands))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Duplicate Discord command name");
    }

    private static ArrayList<ICommand> completeCommands() {
        var commands = new ArrayList<ICommand>();
        CommandRegistry.REQUIRED_COMPATIBILITY_COMMANDS.forEach(name -> {
            String korean = CommandRegistry.REQUIRED_KOREAN_NAMES.get(name);
            commands.add(name.equals("assistant") ? assistantCommand() : command(name, korean));
        });
        return commands;
    }

    private static ICommand assistantCommand() {
        return new ICommand() {
            @Override public String getName() { return "assistant"; }
            @Override public String getDescription() { return "description"; }
            @Override public String getDetailedDescription() { return "details"; }
            @Override public Map<DiscordLocale, String> getNameLocalizations() {
                return Map.of(DiscordLocale.KOREAN, "비서");
            }
            @Override public List<OptionData> getOptions() {
                return List.of(new OptionData(OptionType.STRING, "action", "action", true)
                        .addChoice("종료", "stop"));
            }
            @Override public void execute(SlashCommandInteractionEvent event) { }
        };
    }

    private static void replace(List<ICommand> commands, String name, String koreanName) {
        commands.removeIf(command -> command.getName().equals(name));
        commands.add(command(name, koreanName));
    }

    private static ICommand command(String name, String koreanName) {
        return new ICommand() {
            @Override public String getName() { return name; }
            @Override public String getDescription() { return "description"; }
            @Override public String getDetailedDescription() { return "details"; }
            @Override public Map<DiscordLocale, String> getNameLocalizations() {
                return Map.of(DiscordLocale.KOREAN, koreanName);
            }
            @Override public List<OptionData> getOptions() { return List.of(); }
            @Override public void execute(SlashCommandInteractionEvent event) { }
        };
    }
}
