package com.eventchat.persistence.session;

import com.eventchat.domain.session.PresenceStatus;
import java.util.UUID;

public record PresenceRow(UUID sessionId, UUID userId, PresenceStatus presence, long presenceVer) {
}
