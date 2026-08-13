package com.gahyeonbot.adapters.memory;

import com.gahyeonbot.core.memory.MemorySummarizationPort;
import com.gahyeonbot.services.ai.GlmService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Keeps the Core memory worker independent from the currently selected summarization provider. */
@Component
@RequiredArgsConstructor
final class GlmMemorySummarizationAdapter implements MemorySummarizationPort {

    private final GlmService glmService;

    @Override
    public String summarize(String userMessage, String assistantResponse) {
        return glmService.summarize(userMessage, assistantResponse);
    }
}
