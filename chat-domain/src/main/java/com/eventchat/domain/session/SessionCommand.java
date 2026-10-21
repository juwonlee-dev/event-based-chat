package com.eventchat.domain.session;

import com.eventchat.domain.user.Category;
import com.eventchat.domain.user.Role;
import java.util.UUID;

public sealed interface SessionCommand {

    UUID actorId();

    record Join(UUID actorId, Role role, Category category, String displayName) implements SessionCommand {
    }

    record Leave(UUID actorId) implements SessionCommand {
    }

    record End(UUID actorId) implements SessionCommand {
    }
}
