package com.eventchat.domain.session;

import java.util.List;

public sealed interface Decision {

    /** @param transition 상태가 바뀌지 않으면 null */
    record Accepted(List<NewEvent> events, Transition transition) implements Decision {
    }

    record Rejected(Rejection reason) implements Decision {
    }

    /** 허용하지만 같은 상태의 반복이라 저장하지 않는다(typing·읽음 커서). */
    record Suppressed() implements Decision {
    }
}
