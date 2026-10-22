import json, os, subprocess, sys, time, urllib.error, urllib.parse, urllib.request, uuid

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from stomp_client import Stomp, on_sub  # noqa: E402

BASE = os.environ.get("BASE_URL", "http://127.0.0.1:8080")
# redis는 Redis를 재시작하므로 이름을 지정했을 때만 돈다. 대조 주기를 줄여(CHAT_PRESENCE_RECONCILE_INTERVAL=5s)
# 앱을 띄우면 "Redis 재시작 직후 거짓 DISCONNECT/RECONNECT가 없다"를 매번 확인할 수 있다.
STAGES = sys.argv[1].split(",") if len(sys.argv) > 1 else ["core", "stale"]
PROJECT = os.environ.get("COMPOSE_PROJECT", "event-based-chat")
results = []


def call(method, path, body=None, token=None, params=None):
    if params:
        path += "?" + urllib.parse.urlencode(params, doseq=True)
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(BASE + path, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(req) as r:
            raw = r.read()
            return r.status, (json.loads(raw) if raw else None)
    except urllib.error.HTTPError as e:
        raw = e.read()
        return e.code, (json.loads(raw) if raw else None)


def check(name, cond, detail=""):
    results.append((name, bool(cond)))
    print(("PASS " if cond else "FAIL ") + name + ("" if cond else "  " + str(detail)[:700]), flush=True)


def log(text):
    print("     " + text, flush=True)


def login(u):
    return call("POST", "/auth/login", {"username": u, "password": "chat1234!"})[1]["accessToken"]


def psql(sql):
    return subprocess.run(["docker", "exec", PROJECT + "-postgres-1", "psql", "-U", "chat_owner", "-d", "chat",
                           "-tAc", sql], check=True, capture_output=True, text=True).stdout.strip()


def topic(sid):
    return f"/topic/sessions/{sid}"


def connect(token, sid=None):
    c = Stomp(token, heartbeat="5000,10000")
    if sid:
        c.subscribe(topic(sid), "s1")
    time.sleep(0.3)
    return c


def events(sid, token):
    return call("GET", f"/sessions/{sid}/events", token=token, params={"afterSeq": 0, "limit": 200})[1]["items"]


def presence_events(sid, token):
    return [e for e in events(sid, token) if e["type"] in ("DISCONNECT", "RECONNECT")]


def wait_event(watcher, kind, subject, timeout):
    """watcher 연결의 세션 구독에서 kind 이벤트를 기다린다. (이벤트, 걸린 초)"""
    start = time.time()
    f = watcher.expect(lambda f: on_sub("s1")(f) and f.json().get("type") == kind
                       and f.json().get("subjectId") == subject, timeout)
    return (f.json() if f else None), time.time() - start


A = login("insurance.user")
B = login("insurance.customer")
ida = call("GET", "/users/me", token=A)[1]["id"]
idb = call("GET", "/users/me", token=B)[1]["id"]

if "core" in STAGES:
    bad = Stomp(A, heartbeat="0,0")
    check("heart-beat 0 CONNECT 거부", bad.connected and bad.connected.command == "ERROR"
          and "HEARTBEAT_REQUIRED" in bad.connected.body, bad.connected)
    bad.close()

    a = connect(A)
    b = connect(B)
    sid = call("POST", "/sessions", {"title": "presence 확인"}, A)[1]["id"]
    st, joined = call("POST", f"/sessions/{sid}/join", None, B)
    a.subscribe(topic(sid), "s1")
    time.sleep(0.3)
    check("참여 응답에 두 사람 presence ONLINE", st == 200 and all(p["presence"] == "ONLINE" for p in joined["participants"]), joined)
    changed = [e for e in events(sid, A) if e["type"] == "SESSION_STATUS_CHANGED"][0]
    baseline = changed["payload"].get("presenceBaseline", {})
    check("presenceBaseline에 두 사람 {state, ver}", baseline.get(ida, {}).get("state") == "ONLINE"
          and baseline.get(idb, {}).get("state") == "ONLINE" and baseline[idb]["ver"] > 0, changed)

    # 1. 유예 안에 다시 붙으면 기록 없음
    b.close()
    time.sleep(3)
    b = connect(B, sid)
    time.sleep(20)
    check("3초 뒤 재연결 → 20초 동안 DISCONNECT/RECONNECT 없음", presence_events(sid, A) == [], presence_events(sid, A))

    # 2. 프로세스 정지(heart-beat 중단, 소켓은 열린 채) → 약 32~42초 안에 DISCONNECT
    call("POST", f"/sessions/{sid}/events", {"type": "TYPING_STARTED"}, B)
    b.paused = True
    ev, took = wait_event(a, "DISCONNECT", idb, 60)
    log(f"heart-beat 중단 → DISCONNECT {took:.1f}초, payload={ev and ev.get('payload')}")
    check("heart-beat 중단 → DISCONNECT(HEARTBEAT_TIMEOUT, 서버 기록이라 actor 없음)",
          ev and ev["payload"].get("reason") == "HEARTBEAT_TIMEOUT" and "actorId" not in ev, ev)
    check("표시 지연 약 32~42초 범위", ev and 28 <= took <= 44, took)
    check("서버가 정지한 연결을 닫음", b.wait_closed(1), b.close_code)
    check("DISCONNECT payload에 lastSeenAt", ev and "lastSeenAt" in ev["payload"], ev)
    st, again = call("POST", f"/sessions/{sid}/events", {"type": "TYPING_STOPPED"}, B)
    check("DISCONNECT 뒤 typing은 꺼진 상태(STOPPED가 저장되지 않음 204)", st == 204, (st, again))

    # 3. 다시 붙으면 RECONNECT
    b = connect(B, sid)
    ev, took = wait_event(a, "RECONNECT", idb, 10)
    log(f"재연결 → RECONNECT {took:.1f}초, payload={ev and ev.get('payload')}")
    check("재연결 → RECONNECT(offlineSince, offlineMs)", ev and "offlineSince" in ev["payload"]
          and ev["payload"]["offlineMs"] > 0, ev)

    # 4. 정상 종료(소켓 닫힘) → 약 17초
    b.close()
    ev, took = wait_event(a, "DISCONNECT", idb, 30)
    log(f"소켓 닫힘 → DISCONNECT {took:.1f}초")
    check("소켓 닫힘 → 약 17초 뒤 DISCONNECT(SOCKET_CLOSED)", ev and ev["payload"]["reason"] == "SOCKET_CLOSED"
          and 14 <= took <= 22, (ev, took))

    # 5. 같은 상태가 반복되지 않는다: 지금까지 기록은 정확히 D, R, D
    kinds = [e["type"] for e in presence_events(sid, A)]
    check("기록 순서 DISCONNECT, RECONNECT, DISCONNECT", kinds == ["DISCONNECT", "RECONNECT", "DISCONNECT"], kinds)
    detail = call("GET", f"/sessions/{sid}", token=A)[1]
    check("상세 응답에 B OFFLINE", [p["presence"] for p in detail["participants"] if p["userId"] == idb] == ["OFFLINE"], detail)

    # 6. 탭 두 개 중 하나만 닫으면 기록 없음
    b1, b2 = connect(B, sid), connect(B, sid)
    ev, _ = wait_event(a, "RECONNECT", idb, 10)
    check("두 번째 연결로 RECONNECT 한 번", ev is not None)
    b1.close()
    time.sleep(20)
    check("연결 하나가 남아 있으면 DISCONNECT 없음", [e["type"] for e in presence_events(sid, A)][-1] == "RECONNECT")
    b2.close()

    # 7. WAITING 세션·종료된 세션에는 기록하지 않는다
    waiting = call("POST", "/sessions", {"title": "대기 세션"}, A)[1]["id"]
    ended = call("POST", "/sessions", {"title": "종료 세션"}, A)[1]["id"]
    b = connect(B)
    call("POST", f"/sessions/{ended}/join", None, B)
    call("POST", f"/sessions/{ended}/end", None, A)
    a.close()
    time.sleep(20)
    check("WAITING 세션에는 presence 기록 없음", presence_events(waiting, A) == [])
    check("종료된 세션에는 presence 기록 없음", presence_events(ended, A) == [])
    b.close()

if "stale" in STAGES:
    # 일부러 미래 ver를 심으므로 다른 단계와 사용자를 나눈다.
    A, B = login("hospital.user"), login("hospital.customer")
    ida, idb = call("GET", "/users/me", token=A)[1]["id"], call("GET", "/users/me", token=B)[1]["id"]
    a = connect(A)
    b = connect(B)
    sid = call("POST", "/sessions", {"title": "늦은 신호"}, A)[1]["id"]
    call("POST", f"/sessions/{sid}/join", None, B)
    a.subscribe(topic(sid), "s1")
    time.sleep(0.5)
    # DB가 이미 더 새로운 버전(1시간 뒤)을 반영했다고 만든다 → 이후 Redis 전이는 늦게 온 신호다.
    future = int((time.time() + 3600) * 1_000_000)
    psql(f"UPDATE session_participant SET presence_ver = {future} WHERE session_id = '{sid}' AND user_id = '{idb}'")
    b.close()
    # 스위퍼가 낸 DISCONNECT{SOCKET_CLOSED}(ver가 DB보다 작음)는 버려지고, 다음 대조 작업이 DB보다 큰 ver로 맞춘다.
    ev, took = wait_event(a, "DISCONNECT", idb, 80)
    log(f"끊긴 뒤 첫 DISCONNECT {took:.1f}초, payload={ev and ev.get('payload')}")
    check("ver가 더 작은 신호(늦게 온 신호)는 기록하지 않음: 첫 DISCONNECT가 SOCKET_CLOSED가 아니라 RECONCILED",
          ev and ev["payload"].get("reason") == "RECONCILED", ev)
    kinds = [e["payload"].get("reason") for e in presence_events(sid, A) if e["type"] == "DISCONNECT"]
    check("DISCONNECT는 한 건", kinds == ["RECONCILED"], kinds)
    row = psql(f"SELECT presence || ',' || (presence_ver > {future}) FROM session_participant WHERE session_id = '{sid}' AND user_id = '{idb}'")
    check("대조 뒤 참여자 행 OFFLINE, ver는 DB 값보다 큼", row == "OFFLINE,true", row)
    a.close()

if "redis" in STAGES:
    A, B = login("bank.user"), login("bank.customer")
    ida, idb = call("GET", "/users/me", token=A)[1]["id"], call("GET", "/users/me", token=B)[1]["id"]
    a = connect(A)
    b = connect(B)
    sid = call("POST", "/sessions", {"title": "Redis 재시작"}, A)[1]["id"]
    call("POST", f"/sessions/{sid}/join", None, B)
    a.subscribe(topic(sid), "s1")
    time.sleep(0.5)
    before = presence_events(sid, A)
    subprocess.run(["docker", "restart", PROJECT + "-redis-1"], check=True, capture_output=True)
    time.sleep(12)
    logs = subprocess.run(["docker", "logs", "--since", "60s", PROJECT + "-app-1"], capture_output=True, text=True).stdout
    check("Redis 재시작 뒤 연결 재등록", "presence re-registered" in logs and ("REDIS_RESTARTED" in logs or "RESUBSCRIBED" in logs or "LEASE_LOST" in logs),
          [l for l in logs.splitlines() if "presence" in l][-5:])
    # 다음 대조 주기(60초)까지 지나도 같은 상태라 기록이 생기지 않는다.
    time.sleep(65)
    check("재등록·대조 뒤에도 presence 기록 없음(상태 변화 없음)", presence_events(sid, A) == before, presence_events(sid, A))
    b.close()
    ev, took = wait_event(a, "DISCONNECT", idb, 30)
    check("재시작 뒤에도 끊김은 정상 기록", ev and ev["payload"]["reason"] == "SOCKET_CLOSED", ev)
    a.close()

passed = sum(1 for _, ok in results if ok)
print(f"\n{passed}/{len(results)} passed")

sys.exit(0 if results and all(ok for _, ok in results) else 1)
