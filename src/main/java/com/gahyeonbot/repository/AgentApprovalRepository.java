package com.gahyeonbot.repository;

import com.gahyeonbot.entity.AgentApproval;
import com.gahyeonbot.services.ai.agent.AgentApprovalStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface AgentApprovalRepository extends JpaRepository<AgentApproval, String> {
    Optional<AgentApproval> findByRunIdAndToolNameAndArgumentHash(
            String runId, String toolName, String argumentHash);

    @Query("select a.run.id from AgentApproval a where a.id = :id")
    Optional<String> findRunIdById(@Param("id") String id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from AgentApproval a where a.id = :id")
    Optional<AgentApproval> findByIdForUpdate(@Param("id") String id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select a from AgentApproval a
            where a.run.id = :runId
              and a.toolName = :toolName
              and a.argumentHash = :argumentHash
            """)
    Optional<AgentApproval> findByCallForUpdate(
            @Param("runId") String runId,
            @Param("toolName") String toolName,
            @Param("argumentHash") String argumentHash);

    List<AgentApproval> findByRunIdOrderByRequestedAtAsc(String runId);

    Optional<AgentApproval> findFirstByRunIdAndStatusOrderByRequestedAtAsc(
            String runId, AgentApprovalStatus status);
}
