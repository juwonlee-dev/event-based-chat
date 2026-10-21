-- 상담 세션. 상태 머신 행이면서 세션별 seq 카운터다(쓰기는 이 행을 잠그고 순서를 정한다).
-- 제목·분류코드는 생성 후 바뀌지 않는다. 자주 바뀌는 컬럼에는 인덱스를 걸지 않아 HOT update를 살린다.
CREATE TABLE chat_session (
    id                uuid         PRIMARY KEY DEFAULT uuidv7(),
    title             varchar(100) NOT NULL,
    category          varchar(30)  NOT NULL,
    status            varchar(20)  NOT NULL,
    created_by        uuid         NOT NULL REFERENCES app_user (id),
    participant_count smallint     NOT NULL DEFAULT 0,
    last_seq          bigint       NOT NULL DEFAULT 0,
    last_event_at     timestamptz  NOT NULL,
    message_count     integer      NOT NULL DEFAULT 0,
    created_at        timestamptz  NOT NULL,
    started_at        timestamptz,
    ended_at          timestamptz,
    end_reason        varchar(20),
    CONSTRAINT chat_session_status_ck CHECK (status IN ('WAITING', 'IN_PROGRESS', 'SUSPENDED', 'COMPLETED')),
    CONSTRAINT chat_session_category_ck CHECK (category IN (
        'INSURANCE', 'BANK', 'HOSPITAL', 'FURNITURE', 'REAL_ESTATE',
        'COMMUNITY_CENTER', 'B2B_SALES', 'CUSTOMER_CENTER', 'CAR_SALES', 'TELECOM')),
    CONSTRAINT chat_session_end_reason_ck CHECK (end_reason IN ('LEFT', 'ENDED'))
) WITH (fillfactor = 90, autovacuum_vacuum_scale_factor = 0.05);

-- 목록은 created_at DESC, id DESC 키셋으로만 읽는다.
CREATE INDEX chat_session_created_idx ON chat_session (created_at DESC, id DESC);
CREATE INDEX chat_session_waiting_idx ON chat_session (created_at DESC, id DESC) WHERE status = 'WAITING';
CREATE INDEX chat_session_category_created_idx ON chat_session (category, created_at DESC, id DESC);
-- 제목 검색은 lower(title) LIKE '%q%'로 들어온다.
CREATE INDEX chat_session_title_trgm_idx ON chat_session USING gin (lower(title) gin_trgm_ops);

-- 참여자. 다시 참여할 수 없으므로 세션·사용자당 1행이다.
-- 역할·분류코드·표시 이름은 참여 시점 값을 복사해 둔다(과거 화면이 바뀌지 않도록).
CREATE TABLE session_participant (
    session_id         uuid        NOT NULL REFERENCES chat_session (id),
    user_id            uuid        NOT NULL REFERENCES app_user (id),
    role               varchar(20) NOT NULL,
    category           varchar(30) NOT NULL,
    display_name       varchar(50) NOT NULL,
    active             boolean     NOT NULL DEFAULT true,
    join_seq           bigint      NOT NULL,
    left_seq           bigint,
    joined_at          timestamptz NOT NULL,
    left_at            timestamptz,
    presence           varchar(10) NOT NULL DEFAULT 'OFFLINE',
    presence_ver       bigint      NOT NULL DEFAULT 0,
    typing_seq         bigint,
    typing_at          timestamptz,
    read_seq           bigint      NOT NULL DEFAULT 0,
    read_msg_no        integer     NOT NULL DEFAULT 0,
    last_read_store_at timestamptz,
    PRIMARY KEY (session_id, user_id),
    CONSTRAINT session_participant_presence_ck CHECK (presence IN ('ONLINE', 'OFFLINE'))
) WITH (fillfactor = 90);

-- 사용자가 진행 중인 세션만 좁게 찾는다(종단 전이 때 active = false).
CREATE INDEX session_participant_active_user_idx ON session_participant (user_id) WHERE active;

-- 세션 이벤트. 추가 전용이고 세션 안의 순서는 seq(1부터 빈틈 없음)로만 정한다.
-- 메시지 본문은 content에 두고, 상태 fold 쿼리는 이 컬럼을 읽지 않는다.
CREATE TABLE session_event (
    session_id      uuid        NOT NULL REFERENCES chat_session (id),
    seq             bigint      NOT NULL,
    event_id        uuid        NOT NULL,
    type            varchar(30) NOT NULL,
    actor_id        uuid,
    subject_id      uuid,
    client_event_id varchar(64),
    request_hash    bytea,
    payload         jsonb,
    content         text,
    msg_no          integer,
    server_ts       timestamptz NOT NULL,
    client_ts       timestamptz,
    schema_version  smallint    NOT NULL DEFAULT 1,
    PRIMARY KEY (session_id, seq),
    CONSTRAINT session_event_type_ck CHECK (type IN (
        'SESSION_CREATED', 'JOIN', 'LEAVE', 'SESSION_STATUS_CHANGED', 'MESSAGE',
        'TYPING_STARTED', 'TYPING_STOPPED', 'READ_CURSOR', 'DISCONNECT', 'RECONNECT'))
);

-- 멱등 키: 세션 안에서 (보낸 사람, 키)가 유일하다.
CREATE UNIQUE INDEX session_event_idempotency_uq ON session_event (session_id, actor_id, client_event_id)
    WHERE client_event_id IS NOT NULL;
-- 세션 생성은 세션이 아직 없으므로 사용자 기준으로 유일하게 둔다.
CREATE UNIQUE INDEX session_event_create_idempotency_uq ON session_event (actor_id, client_event_id)
    WHERE type = 'SESSION_CREATED' AND client_event_id IS NOT NULL;
-- 시점 → seq 변환
CREATE INDEX session_event_ts_idx ON session_event (session_id, server_ts, seq);
-- 화면에 보이는 항목만(typing·읽음 커서 제외)
CREATE INDEX session_event_display_idx ON session_event (session_id, seq)
    WHERE type IN ('SESSION_CREATED', 'JOIN', 'LEAVE', 'SESSION_STATUS_CHANGED', 'MESSAGE', 'DISCONNECT', 'RECONNECT');

-- 앱 계정은 이벤트를 추가·조회만 한다.
REVOKE UPDATE, DELETE, TRUNCATE ON session_event FROM ${appUser};

-- 스냅샷 갱신 요청을 Kafka로 내보내기 위한 outbox. 발행하면 삭제한다.
CREATE TABLE event_outbox (
    id          bigserial    PRIMARY KEY,
    topic       varchar(100) NOT NULL,
    key         varchar(100) NOT NULL,
    payload     jsonb        NOT NULL,
    traceparent varchar(64),
    created_at  timestamptz  NOT NULL DEFAULT now()
);
