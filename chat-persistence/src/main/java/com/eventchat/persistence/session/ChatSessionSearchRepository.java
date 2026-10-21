package com.eventchat.persistence.session;

import java.util.List;

public interface ChatSessionSearchRepository {

    /** created_at DESC, id DESC 순서로 조건에 맞는 세션을 최대 limit건 읽는다. */
    List<SessionSummaryRow> search(SessionSearch condition, int limit);
}
