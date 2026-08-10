package com.gahyeonbot.commands.general;

import com.gahyeonbot.adapters.discord.DiscordIdentityMapper;
import com.gahyeonbot.core.conversation.ConversationUseCase;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;

/**
 * Gahyeona Command 테스트
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Gahyeona Command Tests")
class GahyeonaTest {

    @Mock
    private ConversationUseCase conversation;

    @Mock
    private DiscordIdentityMapper identityMapper;

    private Gahyeona command;

    @BeforeEach
    void setUp() {
        command = new Gahyeona(conversation, identityMapper);
    }

    @Test
    @DisplayName("명령어 메타데이터 검증")
    void testCommandMetadata() {
        assertThat(command.getName()).isNotNull().isNotEmpty();
        assertThat(command.getDescription()).isNotNull().isNotEmpty();
        assertThat(command.getDetailedDescription()).isNotNull().isNotEmpty();
        assertThat(command.getOptions()).isNotNull().hasSize(1);
        assertThat(command.getOptions().get(0).getName()).isEqualTo("question");
        assertThat(command.getOptions().get(0).isRequired()).isTrue();
    }
}
