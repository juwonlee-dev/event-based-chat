# 테스트

테스트는 단위, 통합, 클러스터 세 묶음이다. 각 묶음이 확인하는 범위와 실행 방법, 자동화하지 않은 항목을 정리한다.

## 한눈에 보기

| 묶음 | 확인하는 것 | 필요한 것 | 실행 | 걸리는 시간 |
|---|---|---|---|---|
| 단위 | 상태 전이, 복원 리듀서, 커서·목적지 형식 등 순수 로직 | JDK 21 | `scripts/test/unit.sh` | 수 분 |
| 통합 | 앱과 PostgreSQL·Redis·Kafka를 띄워 REST·STOMP·presence·복원·인프라 장애 확인 | JDK 21, Docker | `scripts/test/integration.sh` | 수 분 (작성자 환경에서 약 3분) |
| 클러스터 | nginx 뒤 앱 여러 대의 노드 간 전달과 장애 주입 | JDK 21, Docker | `scripts/test/cluster.sh` | 수 분 |
| 전체 | 단위, 통합, 클러스터 순서 실행 | JDK 21, Docker | `scripts/test/run-all.sh` | 수 분 |

스크립트는 Bash이며 저장소의 Gradle Wrapper를 사용한다. Windows에서는 Git Bash에서 실행한다. 오류가 나면 종료 코드가 0이 아니며, Gradle 테스트 보고서는 `chat-*/build/reports/tests/<스위트>/index.html`에 생성된다.

## 단위 테스트

```bash
scripts/test/unit.sh
```

Docker 없이 실행하는 JUnit 테스트다. 저장소에는 41개 단위 테스트가 있다. `./gradlew build`도 단위 테스트를 실행한다.

| 대상 | 내용 |
|---|---|
| `SessionDecider` | 세션 상태 전이, 입력 중 표시 갱신 규칙, 읽음 커서 억제, presence 전이 |
| `SessionReducer` | 이벤트 리플레이와 기대 상태 비교, 스냅샷과 증분 리플레이, 복원 불가 입력 |
| `RelayEnvelope`, `StompDestinations`, `SessionCursors` | 노드 간 전달 봉투, 구독 목적지 허용 목록, 목록 커서 인코딩 |
| `ConnectionBudgetCheck` | 커넥션 풀이 쓰기·읽기·resume 동시 처리 허용량보다 작을 때 기동을 막는지 |

## 통합 테스트

```bash
scripts/test/integration.sh                  # 전체 통합 테스트
scripts/test/integration.sh RealtimeIT       # 클래스 하나
scripts/test/integration.sh 'Session*'       # 클래스 이름 패턴
```

Testcontainers가 PostgreSQL·Redis·Kafka를 시작한다. 별도로 Compose를 올릴 필요는 없지만 Docker가 실행 중이어야 한다. 앱은 테스트 프로세스와 같은 JVM에서 임의 포트로 뜬다.

통합 테스트는 8개 클래스에 걸쳐 83건이다. 매개변수화 테스트는 각 입력 사례를 한 건으로 계산했다. 작성자 환경에서 전체 실행에 약 3분 걸렸다.

테스트로 resume 프레임이 순서와 다르게 도착할 수 있는 문제를 찾아 수정했다. 구독별 전송을 `OrderedMessageChannelDecorator`로 직렬화하도록 바꿨다.

| 클래스 | 건수 | 내용 |
|---|---:|---|
| `AuthIT` | 9 | 로그인·갱신·로그아웃, 갱신 토큰 재사용, 만료·위조 토큰, 로그인 횟수 제한 |
| `SessionLifecycleIT` | 10 | 생성·참여·퇴장·종료, 멱등 키, 동시 참여, 이벤트 테이블 접근 제한 |
| `SessionListIT` | 16 | 세션 목록 필터, 키셋 페이지, 잘못된 커서와 조건 |
| `EventIngestIT` | 16 | 메시지 멱등·길이 제한, 입력 중 표시, 읽음 커서 억제, 참여자·관리자 조회 |
| `RealtimeIT` | 10 | STOMP 송수신, 목적지·CONNECT 검사, 연결 제한, 만료·폐기 토큰, resume |
| `PresenceIT` | 6 | 유예 중 재연결, heart-beat 중단·재연결, 복수 탭과 대조 작업 |
| `RestoreIT` | 12 | 스냅샷·전체 리플레이 결과 비교, 시점 조회, 복원 불가 응답, DLT |
| `InfraFailureIT` | 4 | Redis 재시작·응답 없음, 재시작 후 presence 등록, Kafka 중단 중 쓰기와 복구 |

presence 테스트는 테스트용 짧은 시간 설정을 사용한다. 테스트 클래스 실행 순서와 공유 컨테이너를 사용하는 장애 테스트의 위치는 `junit-platform.properties`에 지정되어 있다. Kafka 장애는 재시작 대신 일시 정지로 만든다.

## 클러스터 테스트

```bash
scripts/test/cluster.sh              # 기본 노드 간 전달 검사
scripts/test/cluster.sh --faults     # 노드 강제 종료·일시 정지·정상 종료 검사 추가
scripts/test/cluster.sh --obs        # Tempo 분산 추적 검사 추가
scripts/test/cluster.sh --down       # 실행 후 클러스터 종료
```

`compose.yaml`과 `compose.cluster.yaml`로 앱 3대와 nginx를 띄운다. 이미 클러스터가 떠 있으면 그대로 사용한다. 기본 검사는 서로 다른 노드에 연결한 클라이언트 사이의 양방향 메시지 전달과 100건 연속 전송을 확인한다. `--faults`는 앱 컨테이너의 `kill`, `pause`, `stop` 시나리오를 더한다. 실행 중 실패하더라도 스크립트가 종료 시 컨테이너 상태를 복구하도록 테스트 코드에 정리 절차가 있다.

`--obs`는 관측 프로필을 켜고 REST 요청부터 JDBC·Redis 발행, 다른 인스턴스의 전달까지 한 트레이스로 연결되는지 확인한다.

| 시험 | 태그 | 내용 |
|---|---|---|
| 노드 간 전달 | 기본(태그 없음) | 다른 인스턴스에 연결한 두 클라이언트의 양방향 전달, 100건 전송의 누락 및 RESYNC 여부 |
| 장애 주입 | `fault` | 노드 강제 종료 후 resume 및 `NODE_LOST`, 일시 정지 후 재등록, 정상 종료 시 WebSocket 1012 종료 코드 |
| 분산 추적 | `obs` | REST 요청, JDBC, Redis 발행, 원격 노드의 실시간 전달이 한 트레이스에 있는지 |

## 전체 실행

```bash
scripts/test/run-all.sh                  # 단위 → 통합 → 클러스터(장애 주입 포함, 종료 후 내림)
scripts/test/run-all.sh --skip-cluster   # 단위 → 통합
scripts/test/run-all.sh --obs            # 클러스터 단계에 분산 추적 추가
```

한 단계가 실패해도 다음 단계는 실행하며, 끝에 단계별 통과 여부와 실행 시간을 출력한다. 출력 형식은 다음과 같으며 시간 값은 실행 환경에 따라 달라진다.

```text
결과
  PASS 단위      <실행 시간>
  PASS 통합      <실행 시간>
  PASS 클러스터   <실행 시간>
```

## Postman을 쓰지 않은 이유

현재 테스트는 JUnit으로 작성되어 있다. REST 요청은 JDK `HttpClient`, STOMP 연결은 OkHttp WebSocket 위에 만든 테스트 클라이언트를 사용한다. 테스트에서 컨테이너는 Testcontainers 또는 `docker` 명령으로 제어한다. 통합·클러스터 테스트가 이 코드와 컨테이너 수명 주기를 함께 사용하므로 별도 Postman 컬렉션을 두지 않았다.

## 테스트로 확인하지 못한 것

| 항목 | 자동화하지 않은 이유 | 대신 한 것 |
|---|---|---|
| 날짜가 바뀔 때 로그 파일 교체 | 실행 중 날짜 경계에서 파일이 실제로 교체되는 동작은 자동화 테스트에 포함하지 않았다 | `chat-api/src/main/resources/logback-spring.xml`에 날짜·크기 기준 rolling 정책이 설정되어 있다. 파일 교체 동작 자체를 확인한 것은 아니다 |
| 실제 네트워크 단절과 긴 GC 정지 | 클러스터 테스트는 컨테이너 `pause`, `kill`, `stop`을 사용한다. 네트워크만 끊거나 GC 정지를 만드는 시나리오는 포함되어 있지 않다 | 일시 정지 중 lease와 heart-beat가 끊기는 경로를 클러스터 테스트로 확인한다 |
| Grafana 알림 발화 | 테스트가 Grafana의 알림 발화와 지속 조건을 기다리지는 않는다 | `docker/grafana/alerts.yaml`에 8개 알림 규칙이 프로비저닝되어 있다. 규칙 발화 여부는 테스트로 확인하지 않는다 |
| 부하와 용량 한계 | 처리량이나 동시 연결 수의 합격 목표가 정해져 있지 않다 | 클러스터 테스트에서 100건 메시지 전달을 확인하고, `ConnectionBudgetCheck`가 설정된 연결 예산을 기동 시 검사한다 |

