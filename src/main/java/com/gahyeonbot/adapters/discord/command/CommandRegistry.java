package com.gahyeonbot.adapters.discord.command;
import com.gahyeonbot.commands.util.ICommand;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 봇의 모든 명령어를 등록하고 관리하는 클래스.
 * 음악, 예약, 기타 명령어들을 생성하고 등록합니다.
 *
 * @author GahyeonBot Team
 * @version 1.0
 */
@Component
@RequiredArgsConstructor
public class CommandRegistry {
    private static final Logger logger = LoggerFactory.getLogger(CommandRegistry.class);

    private final List<ICommand> commands;

    @PostConstruct
    void logRegisteredCommands() {
        logger.info("총 {}개의 명령어 Bean이 감지되었습니다.", commands.size());
        commands.forEach(cmd -> logger.info("등록된 명령어 Bean: {}", cmd.getName()));
    }

    public List<ICommand> getCommands() {
        return List.copyOf(commands);
    }
}
