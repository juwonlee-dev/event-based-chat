# 운영과 장애 대응

운영은 이벤트 저장의 건강 상태와 실시간 전달의 건강 상태를 따로 살핀다. 메시지는 PostgreSQL에 커밋된 뒤 전달되므로 Redis 이상과 DB 이상은 사용자에게 미치는 영향이 다르다.

## 관측

### 로그

애플리케이션 로그는 ECS 구조화 형식이며 노드 이름은 `HOSTNAME`에서 온다. `RequestMdcFilter`가 `userId`와 `sessionId`를 MDC에 넣고, Micrometer Tracing이 trace ID와 span ID를 넣는다. 추적은 항상 만들고(샘플링 1.0) 외부로 내보내는 것만 `OTEL_ENABLED`로 켠다. 따로 발급하는 요청 ID는 없으며 trace ID가 그 역할을 한다. nginx 로그에는 요청·상태·upstream·처리 시간이 기록된다.

보안 감사 로그는 일반 앱 로그와 파일·보관 한도를 분리한다. 인증과 보안 이벤트를 진단용 상세 로그와 섞지 않아 일반 로그 순환이나 접근 정책이 감사 기록 보존을 바꾸지 않게 했다. 앱 로그는 파일당 50MB, 7일 보관, 총 1GB 상한이고 감사 로그는 파일당 50MB, 30일 보관, 총 500MB 상한이다. 감사 로그는 콘솔에도 함께 출력된다. 컨테이너에서는 json-file 회전이 적용된다. 중앙 보관소의 장기 보존은 기본 구성에서 보장하지 않는다.

| 로그 종류 | 찾아볼 필드 | 목적 |
|---|---|---|
| 앱 | `service.node.name`, logger, level, trace ID, `userId`, `sessionId` | 예외와 요청 흐름 연결 |
| 감사 | 행위(로그인, 토큰 갱신·재사용 감지, 로그아웃, 401·403 접근 거부), 행위자, 결과, IP | 보안 사건과 토큰 수명주기 확인 |
| nginx | 상태 코드, upstream 주소, 응답 시간 | 프록시가 고른 노드와 실패 지점 확인 |

### 지표와 대시보드

`OTEL_ENABLED=true`이면 앱이 15초마다 OTLP로 지표를 관측 스택에 보낸다. 앱이 Prometheus 수집 엔드포인트를 여는 방식은 아니다. Grafana의 Event-based Chat 대시보드는 14개 패널을 제공한다.

| 지표 | 의미 | 운영에서 보는 곳 |
|---|---|---|
| `http_server_requests_milliseconds` | 요청 지연과 건수 | API 지연 패널, 수집 p99 경보 |
| `chat_events_ingested_total` | 이벤트 종류·결과별 수집 건수 | 저장·억제·재전송 추이 |
| `chat_realtime_delivery_latency_milliseconds` | 커밋 뒤 발행부터 노드 전달까지 지연 | 실시간 전달 p99 경보 |
| `chat_realtime_resume_total` | resume 성공과 RESYNC 횟수 | 재연결 품질 패널·경보 |
| `hikaricp_connections_pending` | DB 커넥션을 기다리는 요청 | DB 풀 고갈 경보 |
| `chat_write_rejected_total` | 노드 쓰기 허용량 초과 거절 | 과부하 거절 경보 |
| `chat_outbox_oldest_age_seconds` | 가장 오래된 미발행 outbox 나이 | Kafka 또는 relay 지연 경보 |
| `chat_dlt_published_total` | DLT로 격리된 요청 | 수동 스냅샷 복구 경보 |
| `chat_realtime_degraded` | Redis 실시간 버스 저하 여부 | Redis 중계 경보 |
| `chat_timeline_replayed_events` | 복원 요청당 적용 이벤트 수 | 스냅샷 신선도와 복원 비용. 대시보드 패널은 없어 직접 조회한다 |

대시보드 패널은 실시간 연결 수, 이벤트 수집 처리량과 p99, 실시간 전달 지연 p99, resume 결과, 과부하 거절, DB 커넥션 대기, outbox 적체, 스냅샷·DLT, presence 전이, 연결 끊김 이유, Redis 중계 상태와 메모리, 복원 모드로 구성했다. 지표가 한 번도 발생하지 않은 경보는 설정에서 정상으로 취급하므로, 처음 배포한 시스템의 무활동은 장애와 구분해야 한다.

### 분산 추적

HTTP 요청과 내부 서비스 호출은 한 trace로 이어진다. Kafka producer와 listener 관측이 켜져 있다. Redis 봉투에는 현재 `traceparent`를 담아 노드 간 실시간 전달 trace를 잇는다. `event_outbox`에도 `traceparent` 열이 정의돼 있지만 현재 생성 경로에서 값을 채우지 않아 outbox를 통한 스냅샷 발행·소비까지 이어지는 trace는 보장되지 않는다. 로컬은 trace 샘플링 확률 1.0으로 생성하고, `OTEL_ENABLED=true`일 때 OTLP 내보내기를 활성화한다. `TraceIT`는 REST 요청부터 다른 노드의 실시간 전달까지 한 trace로 이어지는지 확인한다.

### 알림 규칙

아래 임계값과 대기 시간은 `docker/grafana/alerts.yaml`에서 확인했다.

| 규칙 | 조건과 임계값 | 지속 시간 | 의도 |
|---|---|---:|---|
| `chat-ingest-p99` | 이벤트 POST p99 > 500ms(5분 구간) | 5분 | 목표 200ms에서 벗어나면 잠금·커넥션 대기 조사 |
| `chat-delivery-p99` | 실시간 전달 p99 > 1초 | 5분 | 목표 300ms를 넘으면 Redis 출력 버퍼·노드 부하 조사 |
| `chat-resync-ratio` | 10분 resume 중 RESYNC 비율 > 20% | 5분 | 재연결 공백이나 resume 보충 상한 초과 확인 |
| `chat-write-rejected` | 쓰기 거절 건수 > 0(1분) | 1분 | 노드 허용량 6 초과 과부하 확인 |
| `chat-hikari-pending` | 대기 커넥션 > 0 | 30초 | 풀 고갈·장기 트랜잭션 조사 |
| `chat-outbox-age` | 가장 오래된 outbox > 60초 | 즉시 | 스냅샷 요청만 지연되는 상황 확인 |
| `chat-dlt` | DLT 적재 증가 > 0 | 즉시 | 실패한 세션의 스냅샷 재생성 필요 |
| `chat-redis-degraded` | Redis 저하 지표 > 0 | 30초 | 노드 간 전달 중단, 보충 API 의존 확인 |

## 장애 대응

### 앱 노드 다운

**감지.** 노드 다운만을 알리는 경보 규칙은 없다. nginx 접근 로그의 upstream 오류, 응답 헤더 `X-Upstream`에서 특정 노드가 사라지는지, 대시보드의 인스턴스별 연결 수가 0이 되는지로 판단한다. `chat-redis-degraded` 경보는 Redis 문제이므로 노드 종료와 구분한다.

**완화.** 자동으로 일어나는 일은 세 가지다. 프로세스가 죽으면 Compose의 `restart: unless-stopped`가 다시 띄운다. nginx는 연결에 실패한 인스턴스를 10초 동안 빼고 다음 인스턴스로 넘긴다(`max_fails=1 fail_timeout=10s`). 노드 lease(15초)가 끝나면 다른 노드가 그 노드의 연결을 `NODE_LOST`로 정리하고 DISCONNECT를 기록한다. 끊긴 WebSocket 클라이언트는 재접속하면서 마지막 seq로 resume한다. 운영자는 노드가 반복 재시작 중이면 해당 컨테이너를 제외하고 남은 노드 수가 커넥션 풀 총량을 감당하는지 확인한다.

**복구.** `docker compose ... ps`와 해당 앱의 로그·actuator를 확인한 뒤 앱을 재기동한다. 필요하면 클라이언트가 `SYSTEM_RESYNC`를 받은 세션에서 `GET /sessions/{id}/events?afterSeq=...`를 호출한다. `ClusterIT`는 노드 강제 종료와 재연결 흐름을 검증한다.

### DB 장애·성능 저하

**감지.** `chat-hikari-pending`은 `hikaricp_connections_pending > 0`이 30초 지속될 때, 이벤트 p99는 500ms 초과가 5분 지속될 때 알린다. 앱 로그의 DB 연결 예외와 `pg_stat_activity`의 대기·`idle in transaction`을 함께 보고, 커넥션 수 고갈인지 세션 행 잠금 경합인지 분리한다.

**완화.** 풀 대기는 최대 1500ms 뒤 실패하고, 쓰기 허용량 6·읽기 허용량 3·resume 3의 노드 상한이 과도한 DB 동시 요청을 줄인다. 앱 DB 계정에는 역할 단위 타임아웃 세 가지를 걸었다. `lock_timeout` 1초는 세션 행 잠금을 오래 기다리지 않게 하고, `statement_timeout` 3초는 느린 쿼리가 커넥션을 붙잡지 않게 하며, `idle_in_transaction_session_timeout` 10초는 열어 둔 채 방치된 트랜잭션을 끊는다. 허용량 초과, 잠금 대기 초과, 문장 제한 초과, 커넥션 획득 실패는 모두 `503 TEMPORARILY_UNAVAILABLE`과 `Retry-After`로 응답한다(STOMP는 receipts의 같은 코드). 클라이언트가 일시 장애와 버그(`500`)를 구분해 같은 멱등 키로 다시 보낼 수 있게 하려는 것이다. 운영자는 `pg_locks`와 `pg_stat_activity`로 `chat_session` 행을 기다리는 세션과 오래 걸린 트랜잭션을 찾아 원인을 끊고, 5xx가 이어지면 호출 측 재시도 간격을 늘린다.

**복구.** readiness는 DB 상태를 반영하지 않으므로 회복 여부는 `hikaricp_connections_pending`, 5xx 비율, 이벤트 수집 p99가 정상으로 돌아왔는지로 확인한다. 이벤트 쓰기는 한 트랜잭션이므로 실패한 요청은 같은 멱등 키로 다시 시도할 수 있다. 같은 키 재전송이 한 번만 반영되는 것은 `EventIngestIT`와 `SessionLifecycleIT`가 확인한다. DB를 실제로 멈추는 장애 주입 테스트는 아직 없다. SQL별 실행 계획이나 복구 명령은 운영 환경의 DB 배포 방식에 따라 달라 이 문서에서 임의 명령을 고정하지 않는다.

### 데이터 유실·정합성 문제

**감지.** 정합성 이상을 자동으로 알리는 경보는 없다. 쓸 수 있는 신호는 다음과 같다.

- 복원 API의 `422 REPLAY_UNSUPPORTED`: 이벤트 seq 빈틈이나 해석할 수 없는 이벤트가 있다는 뜻이다(응답에 해당 seq 포함).
- 스냅샷이 의심되면 관리자가 같은 시점을 `ignoreSnapshot=true`와 일반 복원으로 조회해 `stateHash`를 비교한다. 두 값이 같다는 것은 `RestoreIT`가 확인한다.
- 같은 seq 중복은 `(session_id, seq)` 기본 키가 거부하고, 같은 멱등 키에 다른 본문이 오면 409로 드러난다.

부분 실패는 두 경우를 염두에 둔다. 이벤트는 커밋됐지만 Redis 발행 전에 노드가 멈추면 상대는 라이브 이벤트를 받지 못한다. 이 구간은 resume과 보충 API가 DB에서 채우고, 보낸 쪽이 같은 키로 재전송하면 저장된 이벤트를 다시 발행한다. outbox를 Kafka에 보낸 뒤 삭제 전에 멈추면 같은 스냅샷 요청이 다시 나가지만, 스냅샷 UPSERT가 더 큰 seq만 반영해 결과는 같다.

**완화.** 이벤트는 UPDATE·DELETE·TRUNCATE가 앱 계정에 금지되어 있고, 현재 상태 변경은 같은 트랜잭션에서 이뤄진다. 일부 반영처럼 보이면 추가 쓰기를 중지하고 먼저 이벤트 로그와 상태를 보존한다. 스냅샷 경로 문제면 `ignoreSnapshot=true`로 전체 리플레이 비교를 수행한다.

**복구.** 중복 요청은 원래 멱등 키로 재전송해 처음 결과를 받는다. 스냅샷 API는 같은 리듀서 버전이면 기존 스냅샷 위에 뒤의 이벤트만 더하므로, 최신 seq까지 와 있는 손상된 스냅샷은 이 API로 고쳐지지 않는다. 이때는 해당 `session_snapshot` 행을 지운 뒤 API를 호출해 처음부터 다시 만든다. 그 전까지도 `ignoreSnapshot=true` 복원은 정상 결과를 준다. 이벤트 로그 자체가 유실된 경우 자동 복구 경로는 없으므로 PostgreSQL 백업 복구가 필요하다. `EventIngestIT`는 중복 재전송, `RestoreIT`는 스냅샷과 전체 리플레이의 해시 일치, `InfraFailureIT`는 Kafka 중단 중 outbox 보존을 확인한다.

### Redis 장애

**감지.** `RealtimeHealth`가 5초마다 Redis에 ping을 보내고, 실패하면 `chat_realtime_degraded`를 1로 올린다. 이 상태가 30초 이어지면 `chat-redis-degraded` 경보가 울린다. Redis가 멈춰도 이벤트 저장은 DB만으로 계속된다.

**완화.** 자동으로 일어나는 일은 다음과 같다.

- 발행에 실패하면 같은 노드의 구독자에게는 Redis를 거치지 않고 직접 전달한다. 다른 노드로 가는 라이브 전달만 멈춘다.
- 그 노드의 모든 연결에 `/user/queue/system`으로 `SYSTEM_DEGRADED`(`pollIntervalMs` 5000)를 보낸다. 클라이언트는 이 간격으로 보충 API를 호출해 상대 메시지를 받는다.
- presence 등록·해제가 실패하면 그 노드를 재등록 대상으로 표시해 두고, Redis가 돌아온 뒤 lease 갱신 때 노드의 연결을 다시 등록해 맞춘다. 그동안 presence 표시는 늦는다.

운영자는 Redis를 복구한다.

**복구.** Redis가 돌아오면 노드가 패턴 구독을 다시 맺고, 그 노드의 모든 세션 구독에 `SYSTEM_RESYNC`(`fromSeq` 없음)를 보내며, presence를 재등록한다. ping이 성공하면 `SYSTEM_RECOVERED`를 보내 클라이언트가 보충 주기 호출을 멈춘다. 운영자는 degraded 지표가 0으로 돌아왔는지 확인한다. `InfraFailureIT`가 Redis 재시작과 응답 없음 상황, 재시작 후 presence 재등록을 확인한다.

### Kafka 장애

**감지.** `chat-outbox-age`가 60초를 넘으면 relay 지연을, `chat-dlt`는 재시도 종료를 알린다. 이는 쓰기나 Redis 전달이 아니라 스냅샷 갱신 지연이다.

**완화.** Kafka 발행은 최대 2초 차단·3초 요청 제한으로 실패하고 outbox 행은 발행 성공 전에 삭제하지 않는다. 운영자는 DB outbox 적체량과 Kafka·작업자 상태를 보고 복구한다.

**복구.** Kafka와 relay가 다시 돌면 남은 outbox를 처리한다. DLT 세션은 원인을 고친 뒤 관리자 `POST /sessions/{id}/snapshots`로 재생성한다. `InfraFailureIT`에서 Kafka 중단 중 outbox 보존과 회복 경로를 검증한다.

## 수평 확장

앱 프로세스는 이벤트 로그와 사용자 세션 상태를 로컬 디스크에 저장하지 않는다. 다만 활성 소켓, STOMP 구독, 송신 버퍼, 세마포어, 일부 캐시는 노드 로컬이다. 인증 토큰은 서명 검증으로 어느 노드에서나 받아들인다.

클러스터 구성은 nginx가 HTTP/WebSocket 연결을 앱에 분산하고, Redis가 노드 사이 이벤트를 전달한다. 특정 사용자 세션을 한 노드에 고정할 필요는 없다. 연결은 하나의 WebSocket 수명 동안 한 upstream에 붙지만 재연결이 다른 노드로 가도 공유된 DB seq와 Redis 채널로 이어진다.

먼저 막힐 가능성이 있는 지점은 단일 세션 행 잠금, 모든 노드에 복제되는 Redis 패턴 채널의 전달량, 그리고 노드당 PostgreSQL 커넥션 총합이다. 늘어나는 노드마다 풀 크기를 단순히 키우면 DB 동시 연결이 더 빨리 고갈될 수 있다. 세션별 전달 분할의 설계와 전환 기준은 [실시간 전달 수평 확장](design/realtime-scale-out.md)에 정리했다.

## 설정과 기본값

| 설정 키 | 기본값 | 의미 |
|---|---:|---|
| `spring.datasource.hikari.maximum-pool-size` | 16 | 노드당 DB 연결 상한 |
| `spring.datasource.hikari.connection-timeout` | 1500ms | DB 연결 대기 제한 |
| `chat.write.max-concurrent-per-node` | 6 | 노드당 쓰기 동시 처리 허용량 |
| `chat.read-path.max-concurrent-per-node` | 3 | 읽기 동시 처리 허용량 |
| `chat.restore.timeout` | 5s | 시점 복원 제한 |
| `chat.restore.default-item-limit` / `max-item-limit` | 50 / 200 | 화면 항목 페이지 크기 |
| `chat.message.max-length` | 2000 | 메시지 본문 유니코드 문자 상한 |
| `chat.outbox.poll-interval` | 1s | outbox 조회 주기 |
| `spring.kafka.producer.properties.max.block.ms` | 2000 | Kafka 메타데이터·버퍼 대기 상한 |
| `spring.kafka.producer.properties.request.timeout.ms` | 3000 | Kafka 요청 응답 제한 |
| `spring.kafka.producer.properties.delivery.timeout.ms` | 5000 | Kafka 전달 전체 제한 |
| `chat.stomp.max-connections-per-user-per-node` | 5 | 사용자·노드별 연결 상한 |
| `chat.stomp.message-size-limit` | 64KB | STOMP 프레임 상한 |
| `chat.stomp.resume-max-events` / `resume-max-bytes` | 200 / 256KB | resume 보충량 상한 |
| `chat.presence.grace` | 15s | 오프라인 판정 유예 |
| `chat.presence.node-heartbeat` / `node-lease-ttl` | 5s / 15s | 노드 lease 갱신·만료 |
| `management.tracing.sampling.probability` | 1.0 | 추적 생성 비율 |

환경별 값은 `application.yml`과 환경 변수에서 조정한다. 동시성 상한을 올리기 전에 Hikari 합계, DB 최대 연결, 실제 지연과 거절 지표를 함께 확인해야 한다.

## 알려진 한계

이 문서 범위에서 Grafana 알림은 설정되어 있지만 실제 운영 채널로 발송되는지는 별도 연락처 설정이 필요하다. 로그는 노드별 파일과 컨테이너 표준 출력에만 남고 중앙 수집은 없다. 관측 프로필에 Loki가 있지만 로그 내보내기(`management.otlp.logging.export.enabled`)는 꺼져 있다. 단계별 도입안은 [중앙 로그 수집 확장](design/elk-logging.md)에 있다. 네트워크 분할과 긴 GC를 포함한 모든 운영 장애를 시험한 것은 아니다. 스키마 보존과 쿼리 성장 대응은 [데이터 모델과 쿼리](data-model.md)를 참고한다.
