package com.gahyeonbot.adapters.discord.bootstrap;

import com.gahyeonbot.adapters.discord.command.CommandRegistry;
import com.gahyeonbot.commands.util.ICommand;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.DiscordLocale;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import net.dv8tion.jda.api.sharding.ShardManager;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BotInitializerRunnerCommandAssemblyTest {
    @Test
    void copiesEveryRegistryCommandIntoTheDiscordManager() {
        CommandRegistry registry = mock(CommandRegistry.class);
        ShardManager shardManager = mock(ShardManager.class);
        List<ICommand> commands = List.of(
                command("assistant", "비서"),
                command("schedule-kick", "퇴장"),
                command("music", "뮤직"));
        when(registry.getCommands()).thenReturn(commands);

        var manager = BotInitializerRunner.assembleCommandManager(registry, shardManager);

        assertThat(manager.registeredStableNames())
                .containsExactlyInAnyOrder("assistant", "schedule-kick", "music");
        commands.forEach(command -> {
            assertThat(manager.resolveCommand(command.getName())).isSameAs(command);
            assertThat(manager.resolveCommand(
                    command.getNameLocalizations().get(DiscordLocale.KOREAN))).isSameAs(command);
        });
    }

    private static ICommand command(String name, String korean) {
        return new ICommand() {
            @Override public String getName() { return name; }
            @Override public String getDescription() { return "description"; }
            @Override public String getDetailedDescription() { return "details"; }
            @Override public Map<DiscordLocale, String> getNameLocalizations() {
                return Map.of(DiscordLocale.KOREAN, korean);
            }
            @Override public List<OptionData> getOptions() { return List.of(); }
            @Override public void execute(SlashCommandInteractionEvent event) { }
        };
    }
}
