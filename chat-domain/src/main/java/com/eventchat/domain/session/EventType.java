package com.eventchat.domain.session;

public enum EventType {
    SESSION_CREATED,
    JOIN,
    LEAVE,
    SESSION_STATUS_CHANGED,
    MESSAGE,
    TYPING_STARTED,
    TYPING_STOPPED,
    READ_CURSOR,
    DISCONNECT,
    RECONNECT
}
