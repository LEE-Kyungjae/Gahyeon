package com.gahyeonbot.adapters.desktop;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.concurrent.ConcurrentHashMap;

/** Prevents one Desktop installation from attaching to another installation's live session. */
@Service
public class DesktopSessionOwnership {
    private final ConcurrentHashMap<String, String> owners = new ConcurrentHashMap<>();

    public void claim(String sessionId, String installationId) {
        require(installationId);
        String existing = owners.putIfAbsent(sessionId, installationId);
        if (existing != null && !existing.equals(installationId)) deny();
    }

    public void requireOwner(String sessionId, String installationId) {
        require(installationId);
        String existing = owners.get(sessionId);
        if (existing == null || !existing.equals(installationId)) deny();
    }

    private static void require(String installationId) {
        if (installationId == null || installationId.isBlank() || installationId.length() > 200) {
            throw new IllegalArgumentException("installationId가 필요하며 200자 이하여야 합니다.");
        }
    }

    private static void deny() {
        throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Desktop session belongs to another installation");
    }
}
