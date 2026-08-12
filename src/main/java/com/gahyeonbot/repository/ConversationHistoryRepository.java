package com.gahyeonbot.repository;

import com.gahyeonbot.entity.ConversationHistory;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 대화 히스토리 Repository.
 * 사용자별 대화 맥락 유지를 위한 조회를 지원합니다.
 *
 * @author GahyeonBot Team
 * @version 1.0
 */
@Repository
public interface ConversationHistoryRepository extends JpaRepository<ConversationHistory, Long> {

    /**
     * 사용자의 최근 N건 대화 조회 (최신순)
     *
     * @param actorId Gahyeon 내부 Actor ID
     * @param pageable 페이징 정보 (limit 포함)
     * @return 대화 히스토리 리스트
     */
    @Query("SELECT c FROM ConversationHistory c WHERE c.actorId = :actorId ORDER BY c.createdAt DESC")
    List<ConversationHistory> findRecentByActorId(@Param("actorId") Long actorId, Pageable pageable);

    /**
     * 사용자의 요약된 대화 조회 (최신순, 최근 5건 제외한 요약된 대화)
     *
     * @param actorId Gahyeon 내부 Actor ID
     * @param pageable 페이징 정보
     * @return 요약된 대화 리스트
     */
    @Query("SELECT c FROM ConversationHistory c WHERE c.actorId = :actorId AND c.summarized = true ORDER BY c.createdAt DESC")
    List<ConversationHistory> findSummarizedByActorId(@Param("actorId") Long actorId, Pageable pageable);

    /**
     * 사용자의 요약되지 않은 오래된 대화 조회 (요약 대상)
     * 최근 5건을 제외하고, 아직 요약되지 않은 대화를 조회
     *
     * @param actorId Gahyeon 내부 Actor ID
     * @param excludeCount 제외할 최근 대화 수 (5)
     * @return 요약 대상 대화 리스트
     */
    @Query(value = """
            SELECT c.* FROM conversation_history c
            WHERE c.user_id = :actorId AND c.summarized = false
            AND c.id NOT IN (
                SELECT id FROM conversation_history
                WHERE user_id = :actorId
                ORDER BY created_at DESC
                LIMIT :excludeCount
            )
            ORDER BY c.created_at ASC
            LIMIT :limit
            """, nativeQuery = true)
    List<ConversationHistory> findUnsummarizedOldConversations(
            @Param("actorId") Long actorId,
            @Param("excludeCount") int excludeCount,
            @Param("limit") int limit);

    /**
     * 사용자의 전체 대화 수 조회
     *
     * @param actorId Gahyeon 내부 Actor ID
     * @return 대화 수
     */
    long countByActorId(Long actorId);

    /**
     * 사용자의 요약된 대화 중 가장 최근 요약 조회 (컨텍스트용)
     *
     * @param actorId Gahyeon 내부 Actor ID
     * @param pageable 페이징 (limit 1)
     * @return 최근 요약 대화
     */
    @Query("SELECT c FROM ConversationHistory c WHERE c.actorId = :actorId AND c.summarized = true AND c.summary IS NOT NULL ORDER BY c.createdAt DESC")
    List<ConversationHistory> findLatestSummary(@Param("actorId") Long actorId, Pageable pageable);
}
