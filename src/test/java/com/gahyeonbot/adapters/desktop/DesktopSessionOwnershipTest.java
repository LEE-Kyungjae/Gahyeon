package com.gahyeonbot.adapters.desktop;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DesktopSessionOwnershipTest {
    @Test
    void sameInstallationCanReuseSessionButAnotherCannotAttachOrCancel() {
        var ownership = new DesktopSessionOwnership();
        ownership.claim("session-1", "installation-a");
        ownership.claim("session-1", "installation-a");
        ownership.requireOwner("session-1", "installation-a");

        assertThatThrownBy(() -> ownership.claim("session-1", "installation-b"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("409 CONFLICT");
        assertThatThrownBy(() -> ownership.requireOwner("session-1", "installation-b"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("409 CONFLICT");
    }

    @Test
    void anUnclaimedSessionCannotBeCancelled() {
        var ownership = new DesktopSessionOwnership();
        assertThatThrownBy(() -> ownership.requireOwner("missing", "installation-a"))
                .isInstanceOf(ResponseStatusException.class);
    }
}
