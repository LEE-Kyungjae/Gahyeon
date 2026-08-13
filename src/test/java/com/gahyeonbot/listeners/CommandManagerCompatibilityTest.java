package com.gahyeonbot.listeners;

import com.gahyeonbot.commands.util.ICommand;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.DiscordLocale;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CommandManagerCompatibilityTest {

    @Test
    void resolvesLeaveCommandByStableAndKoreanNames() {
        var manager = new CommandManager();
        ICommand leave = command("schedule-kick", "퇴장");
        manager.addCommand(leave);

        assertThat(manager.resolveCommand("schedule-kick")).isSameAs(leave);
        assertThat(manager.resolveCommand("퇴장")).isSameAs(leave);
    }

    @Test
    void unknownOrBlankNamesDoNotResolve() {
        var manager = new CommandManager();
        manager.addCommand(command("schedule-kick", "퇴장"));

        assertThat(manager.resolveCommand("나가기")).isNull();
        assertThat(manager.resolveCommand(" ")).isNull();
        assertThat(manager.resolveCommand(null)).isNull();
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
