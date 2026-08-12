package com.gahyeonbot.application.identity;

import com.gahyeonbot.core.identity.ActorId;

import java.time.LocalDateTime;
import java.util.List;

public interface IdentityLinkUseCase {
    IssuedLink issueDesktopLink(ActorId actorId);
    LinkedDesktop consumeDesktopLink(String code, String installationId, String displayName);
    boolean isDesktopLinked(ActorId actorId, String installationId);
    ActorId authenticateDesktopCredential(String credential);
    ActorId desktopActor(String installationId);
    List<DesktopDevice> listDesktopDevices(ActorId actorId);
    boolean revokeDesktopDevice(ActorId actorId, String deviceId);
    boolean renameDesktopDevice(ActorId actorId, String deviceId, String label);
    boolean revokeCurrentDesktop(ActorId actorId, String installationId);
    LocalDateTime desktopCredentialExpiresAt(ActorId actorId, String installationId);

    record IssuedLink(String code, LocalDateTime expiresAt) {}
    record LinkedDesktop(ActorId actorId, String credential) {}
    record DesktopDevice(String id, String installationId, String label,
                         LocalDateTime createdAt, LocalDateTime lastUsedAt,
                         LocalDateTime expiresAt) {}
}
