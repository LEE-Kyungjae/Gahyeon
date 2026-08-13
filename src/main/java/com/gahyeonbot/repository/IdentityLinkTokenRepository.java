package com.gahyeonbot.repository;

import com.gahyeonbot.entity.IdentityLinkToken;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface IdentityLinkTokenRepository extends JpaRepository<IdentityLinkToken, String> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select token from IdentityLinkToken token join fetch token.principal where token.tokenHash = :hash")
    Optional<IdentityLinkToken> findForConsume(@Param("hash") String hash);

    @Modifying
    @Query("delete from IdentityLinkToken token where token.expiresAt < :cutoff")
    int deleteExpiredBefore(@Param("cutoff") java.time.LocalDateTime cutoff);
}
