-- 세션 상태 스냅샷. 세션당 1행이고, 더 새로운 seq나 다른 리듀서 버전일 때만 덮어쓴다(UPSERT).
-- 복원은 스냅샷 + 그 뒤 이벤트만 fold한다. 메시지 본문은 담지 않는다.
CREATE TABLE session_snapshot (
    session_id      uuid        PRIMARY KEY REFERENCES chat_session (id),
    last_seq        bigint      NOT NULL,
    last_event_ts   timestamptz NOT NULL,
    reducer_version smallint    NOT NULL,
    state           jsonb       NOT NULL,
    state_hash      varchar(64) NOT NULL,
    build_count     integer     NOT NULL DEFAULT 1,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now()
);
