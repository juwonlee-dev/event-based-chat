-- 갱신 토큰. 원문은 저장하지 않고 SHA-256 해시만 둔다.
-- 같은 로그인에서 회전된 토큰들은 family_id를 공유하고, 만료 시각도 그대로 이어받는다.
CREATE TABLE refresh_token (
    id          uuid         PRIMARY KEY DEFAULT uuidv7(),
    user_id     uuid         NOT NULL REFERENCES app_user (id),
    family_id   uuid         NOT NULL,
    token_hash  bytea        NOT NULL,
    expires_at  timestamptz  NOT NULL,
    created_at  timestamptz  NOT NULL DEFAULT now(),
    used_at     timestamptz,
    revoked_at  timestamptz,
    replaced_by uuid,
    user_agent  varchar(255),
    ip          varchar(64)
);

CREATE UNIQUE INDEX refresh_token_hash_uq ON refresh_token (token_hash);
CREATE INDEX refresh_token_active_user_idx ON refresh_token (user_id) WHERE revoked_at IS NULL;
CREATE INDEX refresh_token_family_idx ON refresh_token (family_id);
