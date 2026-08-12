package com.gahyeonbot.listeners;

import com.gahyeonbot.commands.util.ICommand;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import net.dv8tion.jda.api.requests.restaction.CommandCreateAction;
import net.dv8tion.jda.api.sharding.ShardManager;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * Discord 슬래시 명령어를 관리하는 클래스.
 * 명령어 등록, 동기화, 실행을 담당하며 Discord API와의 명령어 동기화를 처리합니다.
 *
 * @author GahyeonBot Team
 * @version 1.0
 */
public class CommandManager extends ListenerAdapter {
    private static final Logger logger = LoggerFactory.getLogger(CommandManager.class);
    private final Map<String, ICommand> commandMap = new HashMap<>();
    private final Map<String, ICommand> localizedNameMap = new HashMap<>();
    private ShardManager shardManager;

    /**
     * ShardManager를 설정합니다.
     *
     * @param shardManager Discord ShardManager 인스턴스
     */
    public void setShardManager(ShardManager shardManager) {
        this.shardManager = shardManager;
    }

    /**
     * 등록된 명령어들을 Discord API와 동기화합니다.
     * 모든 서버에 명령어를 등록하고 불필요한 명령어를 삭제합니다.
     *
     * @throws IllegalStateException ShardManager가 설정되지 않은 경우
     */
    public CompletableFuture<Void> synchronizeCommands() {
        if (shardManager == null) {
            throw new IllegalStateException("ShardManager가 설정되지 않았습니다!");
        }

        List<CompletableFuture<Void>> guildFutures = shardManager.getGuilds().stream()
                .map(guild -> guild.retrieveCommands().submit()
                        .thenCompose(existingCommands -> {
                            logger.info("길드 '{}' 기존 명령어 목록: {}", guild.getName(),
                                    existingCommands.stream()
                                            .map(net.dv8tion.jda.api.interactions.commands.Command::getName)
                                            .toList());
                            return synchronizeGuildCommands(guild, existingCommands);
                        })
                        .whenComplete((ignored, throwable) -> {
                            if (throwable != null) {
                                logger.error("길드 '{}' 명령어 동기화 중 오류 발생 - {}",
                                        guild.getName(), throwable.getMessage(), throwable);
                            }
                        }))
                .toList();

        if (guildFutures.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        return CompletableFuture.allOf(guildFutures.toArray(new CompletableFuture[0]));
    }

    /**
     * 특정 서버의 명령어를 동기화합니다.
     *
     * @param guildId 서버 ID
     * @param existingCommands 기존에 등록된 명령어 목록
     */
    private CompletableFuture<Void> synchronizeGuildCommands(Guild guild, List<net.dv8tion.jda.api.interactions.commands.Command> existingCommands) {
        List<CompletableFuture<Void>> operations = new ArrayList<>();
        logger.info("Discord에 등록할 명령어: {}", commandMap.keySet());
        commandMap.values().forEach(command -> {
            logger.info("명령어 '{}' Discord 등록 시도 (옵션: {}개)", command.getName(),
                    command.getOptions() != null ? command.getOptions().size() : 0);
            CommandCreateAction action = guild.upsertCommand(command.getName(), command.getDescription());
            List<OptionData> options = command.getOptions();
            if (options != null && !options.isEmpty()) {
                action.addOptions(options);
            }
            command.getNameLocalizations()
                    .forEach(action::setNameLocalization);
            command.getDescriptionLocalizations()
                    .forEach(action::setDescriptionLocalization);

            CompletableFuture<Void> operation = action.submit()
                    .thenAccept(success -> logger.info("명령어 '{}' 등록/업데이트 성공", command.getName()))
                    .whenComplete((ignored, throwable) -> {
                        if (throwable != null) {
                            logger.error("명령어 '{}' 등록/업데이트 실패 - {}", command.getName(), throwable.getMessage(), throwable);
                        }
                    });
            operations.add(operation);
        });

        existingCommands.stream()
                .filter(cmd -> !commandMap.containsKey(cmd.getName()))
                .forEach(cmd -> {
                    logger.debug("명령어 '{}' 삭제 시도", cmd.getName());
                    CompletableFuture<Void> deletion = guild.deleteCommandById(cmd.getId())
                            .submit()
                            .thenAccept(success -> logger.info("명령어 '{}' 삭제 성공", cmd.getName()))
                            .whenComplete((ignored, throwable) -> {
                                if (throwable != null) {
                                    logger.error("명령어 '{}' 삭제 실패 - {}", cmd.getName(), throwable.getMessage(), throwable);
                                }
                            });
                    operations.add(deletion);
                });

        CompletableFuture<Void> combined = operations.isEmpty()
                ? CompletableFuture.completedFuture(null)
                : CompletableFuture.allOf(operations.toArray(new CompletableFuture[0]));

        return combined.whenComplete((ignored, throwable) -> {
            if (throwable == null) {
                logger.info("길드 '{}'에 동기화된 명령어: {}", guild.getName(), String.join(", ", commandMap.keySet()));
            }
        });
    }

    /**
     * 슬래시 명령어 상호작용 이벤트를 처리합니다.
     *
     * @param event 슬래시 명령어 상호작용 이벤트
     */
    @Override
    public void onSlashCommandInteraction(@NotNull SlashCommandInteractionEvent event) {
        ICommand command = resolveCommand(event.getName());
        logger.info("명령어 '{}' 실행 요청 - 사용자: {} - 옵션: {}",
                event.getName(),
                event.getUser().getAsTag(),
                event.getOptions().stream()
                        .map(option -> option.getName() + "=" + option.getAsString())
                        .collect(Collectors.joining(", "))
        );

        if (command != null) {
            long startTime = System.currentTimeMillis();
            try {
                command.execute(event);
            } catch (Exception e) {
                logger.error("명령어 '{}' 실행 중 오류 발생", event.getName(), e);
                event.reply("명령어 실행 중 오류가 발생했습니다. 문제가 지속되면 관리자에게 문의하세요.").setEphemeral(true).queue();
            } finally {
                long endTime = System.currentTimeMillis();
                logger.info("명령어 '{}' 실행 시간: {}ms", event.getName(), (endTime - startTime));
            }
        } else {
            event.reply("알 수 없는 명령어입니다.").setEphemeral(true).queue();
        }
    }

    public ICommand resolveCommand(String interactionName) {
        if (interactionName == null || interactionName.isBlank()) return null;
        ICommand command = commandMap.get(interactionName);
        return command != null ? command : localizedNameMap.get(interactionName);
    }

    /**
     * 단일 명령어를 등록합니다.
     *
     * @param command 등록할 명령어
     */
    public void addCommand(ICommand command) {
        if (commandMap.containsKey(command.getName())) {
            logger.warn("명령어 '{}'가 이미 등록되어 있습니다.", command.getName());
            return;
        }
        commandMap.put(command.getName(), command);
        // localized name도 매핑에 추가 (기존 Discord 등록과의 호환성)
        command.getNameLocalizations().values().forEach(localizedName -> {
            localizedNameMap.put(localizedName, command);
        });
        logger.info("명령어 '{}' 등록 완료.", command.getName());
    }

    /**
     * 여러 명령어를 등록합니다.
     *
     * @param commands 등록할 명령어 목록
     */
    public void addCommands(List<ICommand> commands) {
        commands.forEach(this::addCommand);
    }

    /** Immutable diagnostic surface used to prove Registry→Discord assembly completeness. */
    public Set<String> registeredStableNames() {
        return Set.copyOf(commandMap.keySet());
    }
}
