# API

Swagger UI: [http://127.0.0.1:8080/swagger-ui.html](http://127.0.0.1:8080/swagger-ui.html) · OpenAPI JSON: [http://127.0.0.1:8080/v3/api-docs](http://127.0.0.1:8080/v3/api-docs). 클러스터 구성에서는 같은 경로를 `http://127.0.0.1:8000`으로 연다.

API 명세는 `springdoc`이 코드에서 생성하는 OpenAPI 3 문서로 제공하고 Swagger UI로 본다. 사용 순서는 로그인으로 `accessToken`을 얻고, Swagger UI의 **Authorize**에 Bearer 토큰을 넣은 다음 원하는 경로를 호출하는 방식이다. 관리자 전용 기능을 보려면 관리자 계정으로 로그인해야 한다.

## REST 엔드포인트

참여자는 세션에 참여한 일반 사용자이고 관리자는 감사·복원 권한을 가진 계정이다. 생성·명령 키는 선택 헤더지만 재시도할 호출에서는 보내는 편이 안전하다.

| 기능 | 경로 | 권한 | 멱등 헤더 |
|---|---|---|---|
| 로그인·토큰 갱신 | `POST /auth/login`, `/auth/refresh` | 공개 | 없음(갱신 토큰은 회전) |
| 로그아웃 | `POST /auth/logout` | 인증 사용자 | 없음 |
| 내 계정 | `GET /users/me` | 인증 사용자 | 없음 |
| 세션 생성 | `POST /sessions` | 상담원·고객 | 선택 `Idempotency-Key` |
| 세션 목록 | `GET /sessions` | 인증 사용자 | 없음 |
| 세션 상세 | `GET /sessions/{id}` | 참여자 또는 관리자 | 없음 |
| 참여·퇴장·종료 | `POST /sessions/{id}/join`, `/leave`, `/end` | 일반 사용자(전이는 상태 규칙 적용) | 선택 `Idempotency-Key` |
| 이벤트 수집 | `POST /sessions/{id}/events` | 해당 세션 참여자 | 메시지는 본문 `clientEventId` |
| 이벤트 조회 | `GET /sessions/{id}/events` | 참여자는 보충 조회, 관리자만 `from`·`to`·`types` 필터 | 없음 |
| 시점 복원 | `GET /sessions/{id}/timeline` | 참여자 또는 관리자 | 없음 |
| 스냅샷 생성 | `POST /sessions/{id}/snapshots` | 관리자 | 요청은 최신 상태로 멱등 갱신 |

과제에서 제시한 예시 경로는 다음 구현 경로와 대응한다.

| 과제 경로 | 이 구현의 경로 | 차이와 이유 |
|---|---|---|
| `POST /sessions/{id}/events` | 동일 | REST와 STOMP가 같은 이벤트 처리기를 호출한다 |
| `GET /sessions/{id}/timeline?at=...` | 동일 | `atSeq`도 지원하고, 응답에 복원 모드·해시를 포함한다 |
| `GET /sessions` | 동일 | 날짜·상태·분류·제목·참여자 필터와 키셋 커서를 지원한다 |
| `POST /sessions/{id}/join`, `/leave`, `/end` | 동일 | 수정 요청마다 선택 멱등 키를 받아 재전송을 안전하게 한다 |
| 선택 스냅샷 생성 API | `POST /sessions/{id}/snapshots` | 관리자 동기 생성과 DLT 복구 경로로 제공한다 |

## 요청·응답 예시

예시 값은 DTO 필드 이름과 JSON 변환 규칙을 따른다. 인증 후 실제 응답의 UUID와 시각은 서버가 정한다.

### 세션 생성

```http
POST /sessions
Authorization: Bearer <accessToken>
Idempotency-Key: create-20261022-001
Content-Type: application/json
```

```json
{"title":"보험 계약 상담"}
```

최초 생성은 `201 Created`, 재전송은 기존 세션 본문과 함께 `200 OK`다. `SessionView`의 응답은 다음 모양이다.

```json
{
  "id":"7c90a160-2b2d-7a7f-8a6c-7a257c64e001",
  "title":"보험 계약 상담",
  "category":"INSURANCE",
  "status":"WAITING",
  "createdBy":"7c90a160-2b2d-7a7f-8a6c-7a257c64e002",
  "createdAt":"2026-10-22T10:00:00Z",
  "startedAt":null,
  "endedAt":null,
  "endReason":null,
  "lastSeq":2,
  "participants":[{"userId":"7c90a160-2b2d-7a7f-8a6c-7a257c64e002","displayName":"보험 상담원","role":"USER","category":"INSURANCE","state":"JOINED","joinedAt":"2026-10-22T10:00:00Z","leftAt":null,"presence":"OFFLINE"}]
}
```

### 이벤트 수집

```http
POST /sessions/7c90a160-2b2d-7a7f-8a6c-7a257c64e001/events
Authorization: Bearer <accessToken>
Content-Type: application/json
```

```json
{"clientEventId":"web-17","type":"MESSAGE","payload":{"content":"계약 내용을 확인하겠습니다."},"clientTime":"2026-10-22T10:01:00Z"}
```

메시지 저장은 `201`, 같은 `clientEventId` 재전송은 최초 이벤트와 함께 `200`이다. `EventView`에서는 DB의 별도 `content` 열이 응답의 `payload.content`로 합쳐진다.

```json
{"seq":3,"eventId":"7c90a160-2b2d-7a7f-8a6c-7a257c64e003","sessionId":"7c90a160-2b2d-7a7f-8a6c-7a257c64e001","type":"MESSAGE","actorId":"7c90a160-2b2d-7a7f-8a6c-7a257c64e002","clientEventId":"web-17","serverTime":"2026-10-22T10:01:00Z","msgNo":1,"payload":{"content":"계약 내용을 확인하겠습니다."}}
```

### 시점 복원

```http
GET /sessions/7c90a160-2b2d-7a7f-8a6c-7a257c64e001/timeline?at=2026-10-22T10:01:00Z&itemLimit=50
Authorization: Bearer <accessToken>
```

복원 응답은 세션, 기준 시점, 복원 출처, 참여자 상태, 화면 이벤트를 반환한다. 항목이 많으면 응답의 `nextBeforeSeq`를 다음 요청의 `beforeSeq`로 전달한다.

```json
{
  "session":{"id":"7c90a160-2b2d-7a7f-8a6c-7a257c64e001","title":"보험 계약 상담","category":"INSURANCE","status":"WAITING","createdAt":"2026-10-22T10:00:00Z","startedAt":null,"endedAt":null,"lastSeq":3,"lastEventAt":"2026-10-22T10:01:00Z"},
  "asOf":{"requestedAt":"2026-10-22T10:01:00Z","seq":3,"referenceTime":"2026-10-22T10:01:00Z","status":"WAITING","messageCount":1},
  "restoredFrom":{"mode":"SNAPSHOT_INCREMENTAL","snapshotSeq":2,"replayedEvents":1,"stateHash":"0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"},
  "participants":[{"userId":"7c90a160-2b2d-7a7f-8a6c-7a257c64e002","displayName":"보험 상담원","role":"USER","category":"INSURANCE","state":"JOINED","joinedAt":"2026-10-22T10:00:00Z","leftAt":null,"presence":{"status":"OFFLINE","since":null},"typing":{"active":false,"expiresAt":null},"readCursor":{"seq":0,"at":null},"unreadCount":1}],
  "items":{"list":[{"seq":3,"eventId":"7c90a160-2b2d-7a7f-8a6c-7a257c64e003","sessionId":"7c90a160-2b2d-7a7f-8a6c-7a257c64e001","type":"MESSAGE","actorId":"7c90a160-2b2d-7a7f-8a6c-7a257c64e002","clientEventId":"web-17","serverTime":"2026-10-22T10:01:00Z","msgNo":1,"payload":{"content":"계약 내용을 확인하겠습니다."}}],"hasMore":false,"nextBeforeSeq":3}
}
```

## 인증과 오류

REST는 `Authorization: Bearer <accessToken>`을 사용한다. access token 기본 수명은 30분, refresh token은 14일이며 갱신 토큰은 회전한다. 관리자만 스냅샷 생성과 전체 재생 비교를 할 수 있다.

오류는 RFC 9457 기반 `application/problem+json`이며 확장 필드 `code`를 포함한다.

| HTTP | 코드 예 |
|---:|---|
| 400 | `VALIDATION_FAILED`, `CURSOR_INVALID`, `MESSAGE_TOO_LONG`, `INVALID_TIME_FORMAT` |
| 401 | `AUTH_INVALID`, `AUTH_TOKEN_EXPIRED`, `REFRESH_TOKEN_REUSED` |
| 403 | `ACCESS_DENIED`, `NOT_PARTICIPANT`, `ADMIN_ONLY` |
| 404 | `SESSION_NOT_FOUND` |
| 409 | `SESSION_NOT_JOINABLE`, `IDEMPOTENCY_KEY_REUSED` |
| 422 | `BEFORE_SESSION_START`, `REPLAY_UNSUPPORTED` |
| 429 | `RATE_LIMITED` |
| 503 | `TEMPORARILY_UNAVAILABLE`, `RESTORE_TIMEOUT` |

## STOMP over WebSocket

연결 주소는 `ws://127.0.0.1:8080/ws`이며 클러스터에서는 8000 포트를 사용한다. 토큰은 STOMP `CONNECT`의 native `Authorization` 헤더에 넣는다.

### 1. 연결

```text
CONNECT
accept-version:1.2
heart-beat:5000,10000
Authorization:Bearer <accessToken>

^@
```

### 2. 구독

```text
SUBSCRIBE
id:session-1
destination:/topic/sessions/7c90a160-2b2d-7a7f-8a6c-7a257c64e001
resume-after-seq:42
ack:auto

^@
```

세션 topic은 참여자만 구독할 수 있다. 복구가 필요 없으면 `resume-after-seq`를 생략한다. 연결 직후에는 `/user/queue/receipts`와 `/user/queue/system`도 구독해 거절·RESYNC 안내를 받는다.

### 3. 전송

```text
SEND
destination:/app/sessions/7c90a160-2b2d-7a7f-8a6c-7a257c64e001/events
content-type:application/json

{"clientEventId":"web-18","type":"MESSAGE","payload":{"content":"확인했습니다."},"clientTime":"2026-10-22T10:02:00Z"}
^@
```

클라이언트 전송 가능 유형은 `MESSAGE`, `TYPING_STARTED`, `TYPING_STOPPED`, `READ_CURSOR`다. 서버는 이벤트를 저장한 뒤 topic에 전달하고 요청 결과는 receipts로 보낸다.

### 4. Resume

성공하면 놓친 이벤트 뒤에 `RESUMED` 프레임이 오며 `fromSeq`, `toSeq`를 포함한다. 클라이언트는 이미 적용한 seq 이하를 버린다. 최대 200개·256KB·노드 동시 3건을 넘기면 `/user/queue/system`으로 `SYSTEM_RESYNC`를 받고 REST 보충을 한다.

```json
{"kind":"RESUMED","sessionId":"7c90a160-2b2d-7a7f-8a6c-7a257c64e001","fromSeq":43,"toSeq":49}
```

```text
GET /sessions/7c90a160-2b2d-7a7f-8a6c-7a257c64e001/events?afterSeq=42&limit=200
Authorization: Bearer <accessToken>
```

중복 수집에는 메시지의 같은 `clientEventId`, 세션 생성·참여·퇴장·종료에는 같은 `Idempotency-Key`를 사용한다. 자세한 중복·순서 계약은 [정합성과 상태 복원](consistency-and-restore.md)을 참고한다.
