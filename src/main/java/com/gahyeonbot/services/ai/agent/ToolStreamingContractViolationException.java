package com.gahyeonbot.services.ai.agent;

final class ToolStreamingContractViolationException extends IllegalStateException {
    ToolStreamingContractViolationException() {
        super("provider mixed tool calls and speakable text in one streamed step");
    }
}
