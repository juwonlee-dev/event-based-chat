# 1:1 실시간 상담 채팅 · 이벤트 기반 상태 복원

상담원과 고객이 1:1로 대화하는 채팅 서버다. 대화 중 일어난 일을 모두 이벤트로 남기고, 그 이벤트만으로 특정 시점의 대화 상태를 다시 만들어 낸다.

- 실시간 전달은 WebSocket(STOMP)으로 한다. 메시지, 입력 중 표시(typing), 읽음 커서, 접속 상태(presence)를 주고받고, 앱이 여러 대여도 Redis로 노드 사이를 잇는다.
- 순서는 세션마다 서버가 매기는 seq(1부터 빈틈 없음) 하나로만 판단한다. 재전송은 멱등 키로 한 번만 반영한다.
- `GET /sessions/{id}/timeline?at=...`은 이벤트를 처음부터 차례로 적용(리플레이)해 그 시점의 참여자, 메시지, presence·typing·읽음 커서를 돌려준다. 중간 결과인 스냅샷은 Kafka를 거쳐 비동기로 만든다.

## 주요 결정

| 문제 | 고른 방법 | 이유 | 대신 감수한 것 |
|---|---|---|---|
| 순서 기준 | 세션별 서버 seq. 쓰기는 세션 행을 잠그고 직렬화한다 | 클라이언트 시각은 기기마다 다르고, 순서가 의미 있는 범위는 세션 하나다 | 같은 세션 안의 쓰기는 동시에 처리하지 않는다(1:1이라 경합이 작다). 발신자가 의도한 순서까지는 보장하지 않는다 |
| 중복 처리 | 이벤트는 `clientEventId`, 세션 명령은 `Idempotency-Key` + 요청 해시. 멱등 조회를 상태 판정보다 먼저 한다 | 재전송은 처음 결과를 그대로 돌려주고, 같은 키로 다른 내용을 보내는 실수는 409로 잡는다 | 멱등 키를 이벤트와 함께 영구 보관한다 |
| 저장 모델 | 이벤트 테이블이 원천(추가 전용). 세션·참여자 행은 같은 트랜잭션에서 갱신하는 현재 상태 | 복원은 이벤트만 보고, 쓰기 판정은 현재 상태 한 줄로 빠르게 한다 | 같은 규칙을 쓰기 측과 리듀서 두 곳에서 지키고 테스트에서 비교한다 |
| 실시간 프로토콜 | STOMP over WebSocket, Spring 내장 브로커 | 구독 단위 권한 검사, heart-beat, 표준 프레임을 그대로 쓴다 | 외부 브로커가 없어 노드 간 전달을 직접 만들었다 |
| 노드 간 전달 | Redis Pub/Sub(패턴 구독) | 지연이 짧고 구성이 단순하다 | 전달을 보장하지 않는다. 빠진 구간은 seq 기반 resume과 RESYNC로 메운다 |
| presence | 사용자 단위로 Redis Lua에서 원자적으로 판정. 15초 유예 뒤 상태가 바뀔 때만 기록 | 탭 전환이나 짧은 망 끊김으로 깜빡이지 않게 한다 | 오프라인 표시가 늦다. 소켓이 닫히면 약 17초, heart-beat만 끊기면 약 30~40초 뒤에 기록된다 |
| 상태 복원 | 리듀서 하나로 리플레이. 스냅샷은 성능용 | 정확성의 근거를 한 곳(리듀서)에 모은다 | 스냅샷이 늦으면 리플레이 구간이 길어진다 |
| 스냅샷 생성 | outbox → Kafka → 작업자, 실패하면 백오프 재시도 후 DLT | 쓰기 경로에서 스냅샷 비용을 빼고, 재시도와 실패 격리는 Kafka 소비 쪽에 맡긴다 | Kafka가 하나 더 필요하다 |
| 테스트 | JUnit 단위·통합·클러스터 테스트. 실행은 `scripts/test`의 Bash 스크립트로 한다 | 노드 사이 전달, 재연결, 장애 상황까지 확인한다 | 통합·클러스터 테스트에는 Docker가 필요하다 |

## 과제 요구사항 대응

| 과제 항목 | 대응 | 문서 |
|---|---|---|
| 실시간 메시지 송수신 | STOMP over WebSocket, Redis Pub/Sub으로 노드 간 전달 | [아키텍처](docs/architecture.md) |
| join / leave | 세션 상태 머신(대기 → 진행 → 완료. 누군가 나가면 대기·진행 어디서든 중단) | [정합성과 상태 복원](docs/consistency-and-restore.md) |
| presence | 사용자 단위 접속 상태, 15초 유예, DISCONNECT·RECONNECT 기록 | [정합성과 상태 복원](docs/consistency-and-restore.md) |
| 이벤트 수집 API | REST `POST /sessions/{id}/events`와 STOMP `/app/sessions/{id}/events`가 같은 처리기를 탄다 | [API](docs/api.md) |
| 중복 방지 | 멱등 키 + 요청 해시, typing·읽음 커서는 같은 상태의 반복을 억제 | [정합성과 상태 복원](docs/consistency-and-restore.md) |
| 순서 뒤바뀜 | 세션별 서버 seq를 유일한 기준으로 | [정합성과 상태 복원](docs/consistency-and-restore.md) |
| 특정 시점 복원 | `GET /sessions/{id}/timeline?at=` 또는 `atSeq=` | [정합성과 상태 복원](docs/consistency-and-restore.md) |
| DB 설계(ERD, DDL, 인덱스) | Flyway 마이그레이션, [ERD](https://www.erdcloud.com/d/ngEbEsraLRdZBRhsb) | [데이터 모델과 쿼리](docs/data-model.md) |
| REST API 설계 | Swagger(swagger-ui): http://127.0.0.1:8080/swagger-ui.html | [API](docs/api.md) |
| 재연결 시 정합성 | 마지막으로 받은 seq부터 resume, 상한을 넘으면 RESYNC 후 보충 API | [정합성과 상태 복원](docs/consistency-and-restore.md) |
| 수평 확장 | 상태는 PostgreSQL·Redis에 두고 앱은 nginx 뒤 여러 대 | [운영과 장애 대응](docs/operations.md) |
| 관측 가능성 | ECS JSON 로그, 지표, OpenTelemetry 추적, Grafana 대시보드·알림 | [운영과 장애 대응](docs/operations.md) |
| 비동기 처리 | outbox → Kafka → 작업자, 지수 백오프 재시도, DLT, 멱등 | [정합성과 상태 복원](docs/consistency-and-restore.md) |
| 장애 대응 3종 | 서버 다운, DB 장애·성능 저하, 데이터 유실·정합성의 감지 → 완화 → 복구 | [운영과 장애 대응](docs/operations.md) |
| 쿼리 최적화 | 핫패스 쿼리 3개의 SQL·인덱스·병목 | [데이터 모델과 쿼리](docs/data-model.md) |

### 가정과 범위

- 세션 참여자는 두 명이다. 한 사람이 세션을 만들면 대기 상태가 되고, 다른 한 사람이 참여하면 진행 상태가 된다. 참여는 역할·분류와 관계없이 관리자가 아닌 사용자면 할 수 있고, 세션의 분류는 만든 사람의 분류를 따른다.
- 한 번 퇴장하면 그 세션에 다시 참여할 수 없고, 세션은 중단 상태로 끝난다.
- 메시지 수정·삭제는 만들지 않았다. 복원 결과의 메시지는 모두 "전송됨" 상태다. 추가하는 방법은 [정합성과 상태 복원](docs/consistency-and-restore.md)에 적었다.
- 메시지는 2000자까지 받는다. 그보다 긴 메시지는 [긴 메시지 설계](docs/design/long-message.md)만 했다.
- 인증은 과제 범위 밖이라 로그인, 토큰 갱신, 로그아웃까지만 만들었다. 사용자는 시드 데이터로 넣는다.
- 화면은 없다. 동작은 테스트와 swagger-ui로 확인한다.

### 한계와 고도화 계획

| 항목 | 현재 | 이유 | 고도화 방향 |
|---|---|---|---|
| 메시지 수정·삭제 | 이벤트 유형에 없다 | 현재는 변경 불가 기록과 결정적 재생을 우선해 변경 이력·권한 규칙 설계를 뒤로 뒀다 | 이벤트·리듀서·schema_version 확장 |
| 메시지 길이 | 최대 2000자 | 한 프레임과 한 DB 행에서 안전하게 다룰 수 있는 크기를 우선하고, 큰 본문 분할·객체 보관의 정합성 비용은 별도 설계로 남겼다 | [긴 메시지 설계](docs/design/long-message.md) |
| Snapshot 이력 | 세션당 최신 1행, 과거 시점은 전체 replay | 저장 공간과 정리 정책을 늘리지 않고 복원 기준점을 제공하는 쪽을 먼저 선택했다 | (session_id, last_seq) 이력 저장 |
| 부하 검증 | 부하 테스트가 없다 | 정합성·장애 시나리오 검증을 우선했고, 단일 개발 PC에서는 부하 발생기와 대상이 자원을 나눠 써 수치 신뢰도가 낮다 | 동시 연결·초당 메시지 부하를 전용 환경에서 측정 |
| 노드 전달 | 패턴 구독이 모든 노드로 전달 | 배포 단순성과 전체 노드 수가 작은 현재 구성을 우선했고, 세션별 라우팅은 구독 재배치 규칙이 더 필요하다 | [실시간 전달 수평 확장](docs/design/realtime-scale-out.md) |
| 사용자 관리 | 시드 사용자, 로그인·갱신·로그아웃 | 채팅 일관성과 접근 검증을 우선하고 가입·복구 UX는 과제 범위에서 제외했다 | 가입·계정 수명주기 설계 |
| 장애 검증 | DB 중단·지연, 네트워크 분할, 긴 GC 정지, 알림 실제 발화는 자동 테스트가 없다 | 컨테이너 조작만으로 재현되는 노드·Redis·Kafka 장애를 먼저 자동화했다. DB 장애는 공유 컨테이너를 쓰는 다른 통합 테스트까지 흔들고, 분할·GC 정지는 별도 장애 주입 도구가 필요하다 | DB 전용 컨테이너로 커넥션 고갈·락 경합 테스트 추가, 네트워크 장애 주입 도구 도입 |

### 가산점 항목

| 과제의 가산점 항목 | 한 것 | 확인 위치 |
|---|---|---|
| Snapshot 생성 자동화 | 이벤트 100건마다, 그리고 세션이 끝날 때 스냅샷 요청을 남긴다 | [정합성과 상태 복원](docs/consistency-and-restore.md), `RestoreIT` |
| Projection 비동기 파이프라인 | outbox → Kafka → 작업자 → DLT | [정합성과 상태 복원](docs/consistency-and-restore.md), `RestoreIT`, `InfraFailureIT` |
| Snapshot + Replay 복원 최적화 | 스냅샷 뒤의 이벤트만 리플레이한다. 응답에 상태 해시를 싣고, 관리자는 스냅샷 없이 전체를 리플레이해 결과를 비교할 수 있다 | [정합성과 상태 복원](docs/consistency-and-restore.md) |
| 재연결 시 resume / replay 고도화 | 구독할 때 마지막 seq를 넘기면 놓친 이벤트를 이어서 받는다. 상한(200건, 256KB)을 넘으면 RESYNC | [API](docs/api.md), `RealtimeIT`, `ClusterIT` |
| 운영 대시보드 | Grafana 대시보드 1개(14개 패널)와 알림 규칙 8개 | [운영과 장애 대응](docs/operations.md) |
| 테스트 전략 고도화 | 노드 강제 종료, Redis·Kafka 장애를 포함한 JUnit 테스트와 Bash 러너 | [테스트](docs/testing.md) |
| 추가 통신 방식 비교 | WebSocket, SSE, 롱폴링, WebRTC를 비교했다(구현은 WebSocket) | [아키텍처](docs/architecture.md) |

별도 부하 테스트는 하지 않았다. 테스트 전략은 [테스트](docs/testing.md)에 정리했다. 과제 항목 밖으로는 앱 여러 대 + nginx 구성과 노드 장애·일시 정지 시나리오를 만들었다.

## 빠른 시작

### 준비물

- Docker Desktop 또는 Docker Engine(Compose v2.24 이상). 앱 이미지는 컨테이너 안에서 빌드하므로 실행만 할 때는 JDK가 없어도 된다. 테스트를 돌리려면 JDK 21이 필요하다.
- 8080, 8081, 5432, 6379, 9094 포트가 비어 있어야 한다. 모두 `127.0.0.1`에만 연다. 클러스터 구성은 8000, 관측 구성은 3000·4318도 쓴다.
- 테스트 스크립트는 Bash와 저장소의 Gradle Wrapper를 사용한다. Windows에서는 Git Bash에서 실행한다.

### 실행

```bash
git clone https://github.com/juwonlee-dev/event-based-chat.git
cd event-based-chat
cp .env.example .env
docker compose up -d --build
```

첫 빌드는 의존성을 받느라 몇 분 걸린다. 아래 두 가지가 확인되면 준비가 끝난 것이다.

```bash
docker compose ps                                         # postgres, redis, kafka, app 모두 healthy
curl http://127.0.0.1:8081/actuator/health/readiness      # {"status":"UP"}
```

DB 스키마(Flyway)와 Kafka 토픽은 앱이 기동할 때 만든다.

| 주소 | 용도 |
|---|---|
| http://127.0.0.1:8080/swagger-ui.html | API 명세(Swagger UI) |
| http://127.0.0.1:8080/v3/api-docs | OpenAPI 명세(JSON) |
| `ws://127.0.0.1:8080/ws` | STOMP over WebSocket |
| http://127.0.0.1:8081/actuator/health | 상태 확인 |

### 환경 변수

`.env.example`을 그대로 복사해도 로컬에서는 동작한다. 다른 곳에 띄울 때는 비밀번호와 서명 키를 바꾼다.

| 변수 | 쓰임 |
|---|---|
| `POSTGRES_PASSWORD` | PostgreSQL 슈퍼유저 비밀번호(초기화 스크립트만 쓴다) |
| `DB_NAME`, `DB_OWNER_USER`, `DB_OWNER_PASSWORD` | 데이터베이스와 소유자 계정. 마이그레이션(Flyway)은 이 계정으로 돈다 |
| `DB_APP_USER`, `DB_APP_PASSWORD` | 앱이 쓰는 계정. 이벤트 테이블에는 추가·조회 권한만 있다 |
| `REDIS_USER`, `REDIS_PASSWORD` | Redis ACL 계정. 앱이 쓰는 키와 채널만 허용한다 |
| `KAFKA_CLUSTER_ID` | Kafka(KRaft) 클러스터 ID |
| `JWT_SECRET` | 접근 토큰 서명 키(HS256, 32바이트 이상. 짧으면 기동하지 않는다) |
| `OTEL_ENABLED` | `true`면 추적·지표를 관측 스택으로 내보낸다 |

DB 역할을 만드는 초기화 스크립트는 볼륨이 비어 있을 때만 돈다. DB 비밀번호를 바꿨다면 `docker compose down -v` 뒤에 다시 띄운다.

### 시드 계정

로컬 프로필에서만 들어가는 계정이다. 비밀번호는 모두 `chat1234!`다.

| 역할 | 아이디 | 할 수 있는 일 |
|---|---|---|
| 상담원 | `{분류}.user` | 세션 생성·참여, 메시지·typing·읽음 커서 전송, 실시간 구독 |
| 고객 | `{분류}.customer` | 상담원과 같다 |
| 관리자 | `admin` | 모든 세션의 상세·이벤트·시점 복원 조회(기간·유형 필터, 스냅샷 없는 전체 리플레이 포함), 스냅샷 수동 생성. 세션 참여, 메시지 전송, 실시간 연결은 할 수 없다 |

분류는 `insurance`, `bank`, `hospital`, `furniture`, `real-estate`, `community-center`, `b2b-sales`, `customer-center`, `car-sales`, `telecom` 열 가지다. 세션 목록은 역할과 관계없이 모두 볼 수 있다.

Windows PowerShell에서는 `curl` 대신 `curl.exe`를 쓴다.

```bash
curl -s -X POST http://127.0.0.1:8080/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"insurance.user","password":"chat1234!"}'
```

테스트 실행 방법은 [테스트 문서](docs/testing.md)를 참고한다.

### 중지와 초기화

```bash
docker compose down        # 중지(데이터 유지)
docker compose down -v     # 볼륨까지 삭제. 다음 기동 때 DB와 시드를 새로 만든다
```

## 문서

| 문서 | 내용 |
|---|---|
| [아키텍처](docs/architecture.md) | 전체 구성, 기술 선택과 대안, 모듈 경계, 요청 흐름 |
| [데이터 모델과 쿼리](docs/data-model.md) | ERD, 원문 DDL, 인덱스 근거, 핫패스 쿼리 3개와 병목 |
| [정합성과 상태 복원](docs/consistency-and-restore.md) | 순서 기준, 멱등, 상태 머신, 재연결, presence, 복원과 비동기 파이프라인 |
| [운영과 장애 대응](docs/operations.md) | 관측, 장애 3종의 감지 → 완화 → 복구, 수평 확장, 설정 기본값 |
| [API](docs/api.md) | REST·STOMP 계약, 오류 코드, 클라이언트가 지켜야 할 규칙 |
| [테스트](docs/testing.md) | 테스트 묶음, 실행 방법, 장애 주입 |
| [확장 설계: 실시간 전달 수평 확장](docs/design/realtime-scale-out.md) | 노드가 많아질 때 패턴 구독을 세션별 sharded Pub/Sub(Redis Cluster)로 바꾸는 설계, 전환 절차와 기준 |
| [확장 설계: 긴 메시지](docs/design/long-message.md) | 2000자를 넘는 메시지를 위한 바이트 단위 상한, 조각 업로드·객체 저장 방안 |
| [확장 설계: 중앙 로그 수집](docs/design/elk-logging.md) | 노드별 로그 파일을 Loki, 이후 ELK로 모으는 단계별 도입안 |

## 테스트

단위 테스트는 Docker 없이 실행한다. 통합 테스트는 Testcontainers로 의존 서비스를 띄우며, 클러스터 테스트는 Compose 환경에서 앱 여러 대와 nginx를 대상으로 한다. 실행 명령과 각 테스트의 범위는 [테스트 문서](docs/testing.md)를 참고한다.

## 다른 실행 구성

### 앱 여러 대 + nginx

```bash
docker compose -f compose.yaml -f compose.cluster.yaml up -d --build --scale app=3
```

- 앱 포트(8080, 8081)는 열지 않고 nginx(`http://127.0.0.1:8000`, `ws://127.0.0.1:8000/ws`)로만 접근한다.
- 응답 헤더 `X-Upstream`에 처리한 인스턴스의 주소(컨테이너 IP:포트)가 붙는다.
- 인스턴스별 상태는 컨테이너 안에서 본다. 예: `docker exec event-based-chat-app-2 curl -s localhost:8081/actuator/health`

### 관측 스택(Grafana·Tempo·Prometheus)

```bash
OTEL_ENABLED=true docker compose --profile obs up -d --build
OTEL_ENABLED=true docker compose -f compose.yaml -f compose.cluster.yaml --profile obs up -d --build --scale app=2
```

- Windows PowerShell에서는 앞의 변수 지정 대신 `.env`의 `OTEL_ENABLED`를 `true`로 바꾸고 띄운다.
- Grafana 주소는 `http://127.0.0.1:3000`이다. 로그인 없이 볼 수 있고, 설정을 바꿀 때는 admin/admin을 쓴다. 대시보드는 chat 폴더의 "Event-based Chat", 알림 규칙 8개는 Alerting에 있다.
- 앱 3대에 관측 스택까지 켜면 컨테이너 메모리 상한 합계가 8GB를 넘는다. Docker에 할당한 메모리가 부족하면 앱을 2대로 줄인다.

### 로컬 빌드만

```bash
./gradlew build            # Windows: gradlew.bat build
```

## 기술 스택

| 영역 | 사용한 것 |
|---|---|
| 언어·프레임워크 | Java 21, Spring Boot 4.1(Spring MVC, WebSocket/STOMP, Data JPA, Security, Kafka) |
| 데이터 접근 | JPA(Hibernate 7) + QueryDSL, Flyway |
| 저장소·브로커 | PostgreSQL 18, Redis 8, Kafka 4(KRaft) |
| 관측 | Micrometer, OpenTelemetry, grafana/otel-lgtm(Grafana·Tempo·Prometheus·Loki) |
| 실행 환경 | Docker Compose, nginx 1.30(클러스터 구성) |

## 저장소 구조

```
compose.yaml            기본 구성(PostgreSQL, Redis, Kafka, 앱, 관측 프로필)
compose.cluster.yaml    앱 여러 대 + nginx 구성(compose.yaml 위에 겹쳐 쓴다)
Dockerfile              앱 이미지(빌드 포함)
.env.example            환경 변수 견본
chat-domain/            순수 도메인: 상태 머신 판정(SessionDecider), 복원 리듀서(SessionReducer)
chat-persistence/       JPA 엔티티, 저장소(QueryDSL), Flyway 마이그레이션, 시드
chat-api/               Spring Boot 앱: REST, STOMP, 실시간 중계, presence, 복원, 비동기 작업자
docker/                 PostgreSQL 초기화, Redis 설정, nginx, Grafana 프로비저닝
scripts/test/           단위·통합·클러스터 테스트 Bash 러너
docs/                   설계·운영 문서
```
