package com.gahyeonbot.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 대화 히스토리를 저장하는 엔티티.
 * 사용자별 대화 맥락을 유지하기 위해 사용됩니다.
 * 최근 5건은 전체 내용을 유지하고, 이전 대화는 요약됩니다.
 *
 * @author GahyeonBot Team
 * @version 1.0
 */
@Entity
@Table(name = "conversation_history", indexes = {
        @Index(name = "idx_conv_user_id", columnList = "user_id"),
        @Index(name = "idx_conv_created_at", columnList = "created_at"),
        @Index(name = "idx_conv_user_created", columnList = "user_id, created_at DESC")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConversationHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Gahyeon 내부 Actor ID. */
    @Column(name = "user_id", nullable = false)
    private Long actorId;

    /**
     * 사용자 메시지 (질문)
     */
    @Column(name = "user_message", nullable = false, columnDefinition = "TEXT")
    private String userMessage;

    /**
     * AI 응답
     */
    @Column(name = "ai_response", columnDefinition = "TEXT")
    private String aiResponse;

    /**
     * 요약된 대화 내용 (null이면 아직 요약되지 않음)
     * 6건 이상 된 대화는 GLM으로 요약하여 저장
     */
    @Column(name = "summary", columnDefinition = "TEXT")
    private String summary;

    /**
     * 요약 완료 여부
     */
    @Column(name = "summarized", nullable = false)
    @Builder.Default
    private Boolean summarized = false;

    /**
     * 생성 시간
     */
    @Column(name = "created_at", nullable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();
}
