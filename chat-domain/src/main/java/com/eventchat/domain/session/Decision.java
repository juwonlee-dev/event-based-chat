package com.eventchat.domain.session;

import java.util.List;

public sealed interface Decision {

    record Accepted(List<NewEvent> events, Transition transition) implements Decision {
    }

    record Rejected(Rejection reason) implements Decision {
    }
}
