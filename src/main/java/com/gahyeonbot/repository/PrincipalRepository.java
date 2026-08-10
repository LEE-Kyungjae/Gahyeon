package com.gahyeonbot.repository;

import com.gahyeonbot.entity.Principal;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PrincipalRepository extends JpaRepository<Principal, Long> {
}
