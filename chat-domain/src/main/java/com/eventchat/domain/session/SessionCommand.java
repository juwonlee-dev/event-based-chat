package com.eventchat.domain.session;

import com.eventchat.domain.user.Category;
import com.eventchat.domain.user.Role;
import java.time.Instant;
import java.util.UUID;

public sealed interface SessionCommand {

    UUID actorId();

    record Join(UUID actorId, Role role, Category category, String displayName) implements SessionCommand {
    }

    record Leave(UUID actorId) implements SessionCommand {
    }

    record End(UUID actorId) implements SessionCommand {
    }

    record Message(UUID actorId, String content) implements SessionCommand {
    }

    /** @param at 잠금을 얻은 뒤 정한 이벤트 시각. 저장 여부를 이 시각으로 판정한다 */
    record Typing(UUID actorId, boolean started, Instant at) implements SessionCommand {
    }

    record ReadCursor(UUID actorId, long upToSeq, Instant at) implements SessionCommand {
    }
}
