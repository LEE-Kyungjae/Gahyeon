package com.gahyeonbot.services.ai;

import com.gahyeonbot.core.memory.MemoryRole;
import com.gahyeonbot.entity.ConversationHistory;
import com.gahyeonbot.repository.ConversationHistoryRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ConversationHistoryServiceTest {

    @Mock
    private ConversationHistoryRepository repository;

    @Mock
    private GlmService glmService;

    @Test
    void preservesUserAndAssistantRolesInChronologicalOrder() {
        ConversationHistory older = conversation(1L, "첫 질문", "첫 답");
        ConversationHistory newer = conversation(2L, "후속 질문", "후속 답");
        when(repository.findLatestSummary(eq(7L), any(Pageable.class))).thenReturn(List.of());
        when(repository.findRecentByUserId(eq(7L), any(Pageable.class)))
                .thenReturn(List.of(newer, older));

        var context = new ConversationHistoryService(repository, glmService).buildAgentContext(7L);

        assertThat(context.recentMessages()).hasSize(4);
        assertThat(context.recentMessages().get(0).role()).isEqualTo(MemoryRole.USER);
        assertThat(context.recentMessages().get(0).content()).isEqualTo("첫 질문");
        assertThat(context.recentMessages().get(1).role()).isEqualTo(MemoryRole.ASSISTANT);
        assertThat(context.recentMessages().get(1).content()).isEqualTo("첫 답");
        assertThat(context.recentMessages().get(2).content()).isEqualTo("후속 질문");
        assertThat(context.recentMessages().get(3).content()).isEqualTo("후속 답");
    }

    private ConversationHistory conversation(Long id, String user, String assistant) {
        return ConversationHistory.builder()
                .id(id)
                .userId(7L)
                .userMessage(user)
                .aiResponse(assistant)
                .createdAt(LocalDateTime.now())
                .build();
    }
}
