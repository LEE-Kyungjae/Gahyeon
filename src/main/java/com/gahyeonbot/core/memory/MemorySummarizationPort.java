package com.gahyeonbot.core.memory;

/** Provider-neutral boundary used to compact a completed conversation into long-term memory. */
@FunctionalInterface
public interface MemorySummarizationPort {

    String summarize(String userMessage, String assistantResponse);
}
