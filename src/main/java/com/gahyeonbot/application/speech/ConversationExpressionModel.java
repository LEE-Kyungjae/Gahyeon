package com.gahyeonbot.application.speech;

import com.gahyeonbot.core.speech.VoiceExpression;

import java.util.Optional;

/** Optional, bounded small-model refinement for a deterministic expression plan. */
public interface ConversationExpressionModel {
    Optional<VoiceExpression> plan(ConversationExpressionModelRequest request);
}
