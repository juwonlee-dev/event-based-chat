import base64, hashlib, hmac, json, os, subprocess, sys, time, urllib.error, urllib.parse, urllib.request, uuid

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from stomp_client import Stomp, on_sub, receipt  # noqa: E402

BASE = os.environ.get("BASE_URL", "http://127.0.0.1:8080")
def jwt_secret():
    """만료 토큰을 직접 만들어 보려고 서명 키가 필요하다. JWT_SECRET 환경 변수, 없으면 저장소 루트의 .env에서 읽는다."""
    if os.environ.get("JWT_SECRET"):
        return os.environ["JWT_SECRET"]
    env = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", ".env")
    with open(env, encoding="utf-8") as f:
        for line in f:
            if line.startswith("JWT_SECRET="):
                return line.split("=", 1)[1].strip()
    sys.exit("JWT_SECRET을 찾을 수 없습니다(.env 또는 환경 변수).")


SECRET = jwt_secret()
# redis(재시작·일시 정지)와 drain(앱 종료)은 컨테이너를 건드리므로 이름을 지정했을 때만 돈다.
STAGES = sys.argv[1].split(",") if len(sys.argv) > 1 else ["core", "resume"]
results = []


def call(method, path, body=None, token=None, params=None, headers=None):
    if params:
        path += "?" + urllib.parse.urlencode(params, doseq=True)
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(BASE + path, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    for k, v in (headers or {}).items():
        req.add_header(k, v)
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


def login(u):
    return call("POST", "/auth/login", {"username": u, "password": "chat1234!"})[1]


def b64(b):
    return base64.urlsafe_b64encode(b).rstrip(b"=").decode()


def forge(sub, role, exp_in):
    now = int(time.time())
    payload = {"iss": "event-based-chat", "sub": sub, "role": role, "iat": min(now - 5, now + exp_in - 60), "exp": now + exp_in, "jti": str(uuid.uuid4())}
    head = b64(json.dumps({"alg": "HS256"}).encode())
    body = b64(json.dumps(payload).encode())
    sig = b64(hmac.new(SECRET.encode(), f"{head}.{body}".encode(), hashlib.sha256).digest())
    return f"{head}.{body}.{sig}"


def topic(sid):
    return f"/topic/sessions/{sid}"


def app(sid):
    return f"/app/sessions/{sid}/events"


def msg(content, cid=None):
    return {"type": "MESSAGE", "clientEventId": cid or str(uuid.uuid4()), "payload": {"content": content}}


def stomp_user(token, sid=None, sub="s1", **extra):
    c = Stomp(token)
    c.subscribe("/user/queue/receipts", "receipts")
    c.subscribe("/user/queue/system", "system")
    if sid:
        c.subscribe(topic(sid), sub, **extra)
    time.sleep(0.3)
    return c


ua, ub, uc = login("car-sales.user"), login("car-sales.customer"), login("real-estate.user")
A, B, C = ua["accessToken"], ub["accessToken"], uc["accessToken"]
me = {t: call("GET", "/users/me", token=t)[1]["id"] for t in (A, B, C)}


def new_session(title, join=True):
    sid = call("POST", "/sessions", {"title": title}, A)[1]["id"]
    if join:
        call("POST", f"/sessions/{sid}/join", None, B)
    return sid


if "core" in STAGES:
    sid = new_session("실시간 송수신")
    a, b = stomp_user(A, sid), stomp_user(B, sid)
    check("CONNECTED with heart-beat", a.connected and a.connected.command == "CONNECTED"
          and a.connected.headers.get("heart-beat") == "10000,5000", a.connected)

    cid = str(uuid.uuid4())
    a.send(app(sid), msg("안녕하세요, 실시간입니다", cid))
    r = a.expect(receipt(lambda j: j.get("clientEventId") == cid))
    ev_b = b.expect(lambda f: on_sub("s1")(f) and f.json().get("clientEventId") == cid)
    ev_a = a.expect(lambda f: on_sub("s1")(f) and f.json().get("clientEventId") == cid)
    check("sender gets EVENT_RESULT ACCEPTED", r and r.json()["status"] == "ACCEPTED" and r.json()["seq"] > 0
          and r.json()["kind"] == "EVENT_RESULT", r)
    check("other user receives message on topic", ev_b and ev_b.json()["payload"]["content"] == "안녕하세요, 실시간입니다"
          and ev_b.json()["seq"] == r.json()["seq"], ev_b)
    check("sender also receives own message on topic", ev_a is not None, ev_a)
    check("receipt only to sender", b.expect(receipt(), 0.5) is None)

    a.send(app(sid), msg("안녕하세요, 실시간입니다", cid))
    r2 = a.expect(receipt(lambda j: j.get("clientEventId") == cid))
    again = b.expect(lambda f: on_sub("s1")(f) and f.json().get("clientEventId") == cid)
    check("resend same clientEventId -> DUPLICATE same seq and re-published", r2 and r2.json()["status"] == "DUPLICATE"
          and r2.json()["seq"] == r.json()["seq"] and again and again.json()["seq"] == r.json()["seq"], (r2, again))

    b.send(app(sid), {"type": "TYPING_STARTED"})
    t = a.expect(lambda f: on_sub("s1")(f) and f.json().get("type") == "TYPING_STARTED")
    check("typing via STOMP reaches other user with ttlMs", t and t.json()["ttlMs"] == 10000 and t.json()["subjectId"] == me[B], t)
    check("no receipt for accepted typing", b.expect(receipt(), 0.5) is None)

    s, rest = call("POST", f"/sessions/{sid}/events", msg("REST로 보낸 메시지"), A)
    ev = b.expect(lambda f: on_sub("s1")(f) and f.json().get("seq") == rest["seq"])
    check("REST message is delivered over STOMP", s == 201 and ev is not None, (s, ev))

    long_cid = str(uuid.uuid4())
    a.send(app(sid), msg("가" * 7000, long_cid))  # 약 21KB
    r = a.expect(receipt(lambda j: j.get("clientEventId") == long_cid))
    check("20KB message -> REJECTED MESSAGE_TOO_LONG", r and r.json()["status"] == "REJECTED"
          and r.json()["error"]["code"] == "MESSAGE_TOO_LONG" and r.json()["error"]["status"] == 400
          and r.json()["error"]["actualLength"] == 7000, r)
    ok_cid = str(uuid.uuid4())
    a.send(app(sid), msg("긴 글 뒤에도 연결 유지", ok_cid))
    r = a.expect(receipt(lambda j: j.get("clientEventId") == ok_cid))
    check("connection kept after long message", r and r.json()["status"] == "ACCEPTED" and not a.closed.is_set(), r)

    a.frame("SEND", {"destination": "/app/sessions/" + sid + "/events", "content-type": "application/json"}, "{not json")
    r = a.expect(receipt(lambda j: j.get("status") == "REJECTED" and j.get("clientEventId") is None))
    check("unparsable body -> REJECTED with clientEventId null", r and r.json()["error"]["code"] == "VALIDATION_FAILED", r)

    # 종단 세션 구독: 연결 유지 + SUBSCRIBE_REJECTED
    sid2 = new_session("종료된 세션")
    call("POST", f"/sessions/{sid2}/end", None, A)
    a.subscribe(topic(sid2), "s2")
    r = a.expect(receipt(lambda j: j.get("kind") == "SUBSCRIBE_REJECTED"))
    check("terminal session SUBSCRIBE -> SUBSCRIBE_REJECTED", r and r.json()["code"] == "SESSION_NOT_ACTIVE"
          and r.json()["sessionStatus"] == "COMPLETED" and r.json()["lastSeq"] == 5 and r.json()["sessionId"] == sid2, r)
    probe = str(uuid.uuid4())
    a.send(app(sid), msg("거부 뒤에도 연결 유지", probe))
    r = a.expect(receipt(lambda j: j.get("clientEventId") == probe))
    check("connection kept after SUBSCRIBE_REJECTED", r and r.json()["status"] == "ACCEPTED", r)

    c = stomp_user(C)
    c.subscribe(topic(sid), "x")
    r = c.expect(receipt(lambda j: j.get("kind") == "SUBSCRIBE_REJECTED"))
    check("non participant SUBSCRIBE -> NOT_PARTICIPANT", r and r.json()["code"] == "NOT_PARTICIPANT", r)
    c.send(app(sid), msg("끼어들기"))
    r = c.expect(receipt(lambda j: j.get("status") == "REJECTED"))
    check("non participant SEND -> REJECTED NOT_PARTICIPANT, connection kept", r
          and r.json()["error"]["code"] == "NOT_PARTICIPANT" and not c.closed.is_set(), r)

    # 허용 목록 밖 목적지 → ERROR + 연결 종료
    c.send(topic(sid), {"seq": 999, "type": "MESSAGE", "payload": {"content": "위조"}})
    e = c.expect(lambda f: f.command == "ERROR")
    check("SEND to /topic -> ERROR FORBIDDEN_DESTINATION and close", e and e.headers.get("message") == "FORBIDDEN_DESTINATION"
          and c.wait_closed(), e)
    check("forged /topic frame not delivered", b.expect(lambda f: on_sub("s1")(f) and f.json().get("seq") == 999, 1) is None)
    d = stomp_user(C)
    d.subscribe("/topic/anything", "y")
    e = d.expect(lambda f: f.command == "ERROR")
    check("SUBSCRIBE outside allow list -> ERROR FORBIDDEN_DESTINATION", e and e.headers.get("message") == "FORBIDDEN_DESTINATION"
          and d.wait_closed(), e)

    # CONNECT 검사
    for name, client, code in [
        ("no token", lambda: Stomp(None), "AUTH_INVALID"),
        ("bad token", lambda: Stomp(A[:-3] + "abc"), "AUTH_INVALID"),
        ("expired token", lambda: Stomp(forge(me[A], "USER", -120)), "AUTH_TOKEN_EXPIRED"),
        ("heart-beat 0,0", lambda: Stomp(A, heartbeat="0,0"), "HEARTBEAT_REQUIRED"),
        ("heart-beat 30000,10000", lambda: Stomp(A, heartbeat="30000,10000"), "HEARTBEAT_REQUIRED"),
        ("admin", lambda: Stomp(login("admin")["accessToken"]), "ADMIN_READ_ONLY"),
    ]:
        x = client()
        check(f"CONNECT {name} -> ERROR {code}", x.connected and x.connected.command == "ERROR"
              and x.connected.headers.get("message") == code and x.wait_closed(), x.connected)

    extra = [Stomp(C) for _ in range(5)]
    sixth = Stomp(C)
    check("6th connection -> TOO_MANY_CONNECTIONS", all(x.connected.command == "CONNECTED" for x in extra)
          and sixth.connected.command == "ERROR" and sixth.connected.headers.get("message") == "TOO_MANY_CONNECTIONS",
          [x.connected.command for x in extra] + [sixth.connected])
    for x in extra:
        x.close()
    time.sleep(0.5)
    again = Stomp(C)
    check("slot freed after disconnect", again.connected.command == "CONNECTED", again.connected)
    again.close()

    # 접근 토큰 만료 → AUTH_TOKEN_EXPIRED → 갱신 후 재연결
    short = Stomp(forge(me[B], "CUSTOMER", 4))
    short.subscribe(topic(sid), "s1")
    check("short-lived token connects", short.connected.command == "CONNECTED", short.connected)
    time.sleep(1)
    mark = max(i["seq"] for i in call("GET", f"/sessions/{sid}/events", token=A, params={"limit": 200})[1]["items"])
    e = short.expect(lambda f: f.command == "ERROR", 8)
    check("token expiry -> ERROR AUTH_TOKEN_EXPIRED and close", e and e.headers.get("message") == "AUTH_TOKEN_EXPIRED"
          and short.wait_closed(), e)
    s, refreshed = call("POST", "/auth/refresh", {"refreshToken": ub["refreshToken"]})
    re = stomp_user(refreshed["accessToken"], sid, resume_after_seq=0)
    check("refresh and reconnect works", s == 200 and re.connected.command == "CONNECTED", (s, re.connected))
    time.sleep(20)  # presence 유예(15초) + 스위퍼가 지나도
    s, page = call("GET", f"/sessions/{sid}/events", token=A, params={"afterSeq": mark, "limit": 200})
    check("no DISCONNECT recorded", not [i for i in page["items"] if i["type"] == "DISCONNECT"], page)

    # 전체 로그아웃 → 실시간 연결 즉시 AUTH_REVOKED
    s, _ = call("POST", "/auth/logout", {"all": True}, refreshed["accessToken"])
    e1 = re.expect(lambda f: f.command == "ERROR", 3)
    e2 = b.expect(lambda f: f.command == "ERROR", 3)
    check("logout all -> all B connections ERROR AUTH_REVOKED", s == 204 and e1 and e2
          and e1.headers.get("message") == "AUTH_REVOKED" and e2.headers.get("message") == "AUTH_REVOKED"
          and re.wait_closed() and b.wait_closed(), (s, e1, e2))
    check("A connection unaffected by B logout", not a.closed.is_set())

    # 대문자 UUID 구독은 라이브 이벤트를 받지 못하므로 허용 목록에서 거부한다(소문자만)
    up = stomp_user(A)
    up.subscribe(topic(sid).upper().replace("/TOPIC/SESSIONS/", "/topic/sessions/"), "upper")
    e = up.expect(lambda f: f.command == "ERROR", 5)
    check("SUBSCRIBE with uppercase session id -> ERROR FORBIDDEN_DESTINATION",
          e and e.headers.get("message") == "FORBIDDEN_DESTINATION" and up.wait_closed(), e)

    # 같은 멱등 키로 퇴장을 다시 보내면 LEAVE와 뒤따르는 상태 전이를 함께 다시 발행한다
    rsid = call("POST", "/sessions", {"title": "퇴장 재전송"}, A)[1]["id"]
    call("POST", f"/sessions/{rsid}/join", None, C)
    a.subscribe(topic(rsid), "r1")
    time.sleep(0.3)
    key = str(uuid.uuid4())
    s1, _ = call("POST", f"/sessions/{rsid}/leave", None, C, headers={"Idempotency-Key": key})
    first = [a.expect(lambda f: on_sub("r1")(f), 5) for _ in range(2)]
    s2, _ = call("POST", f"/sessions/{rsid}/leave", None, C, headers={"Idempotency-Key": key})
    again = [a.expect(lambda f: on_sub("r1")(f), 5) for _ in range(2)]
    kinds = lambda fs: [f.json().get("type") for f in fs if f]
    check("idempotent leave replay republishes LEAVE and SESSION_STATUS_CHANGED",
          s1 == 200 and s2 == 200 and kinds(first) == ["LEAVE", "SESSION_STATUS_CHANGED"]
          and kinds(again) == kinds(first)
          and [f.json()["seq"] for f in again] == [f.json()["seq"] for f in first], (kinds(first), kinds(again)))
    a.close()

if "resume" in STAGES:
    ua2 = login("b2b-sales.customer")
    sid = call("POST", "/sessions", {"title": "resume 300건"}, ua2["accessToken"])[1]["id"]
    ub2 = login("b2b-sales.user")
    # 참여 전에 연결해 두면 presenceBaseline이 ONLINE이라, 아래 resume 연결이 RECONNECT(seq)를 만들지 않는다.
    keep = stomp_user(ub2["accessToken"])
    call("POST", f"/sessions/{sid}/join", None, ub2["accessToken"])
    for i in range(300):
        call("POST", f"/sessions/{sid}/events", msg(f"{i:03d} " + "가" * 1990), ua2["accessToken"])
    s, page = call("GET", f"/sessions/{sid}/events", token=ua2["accessToken"], params={"afterSeq": 300, "limit": 200})
    last = page["items"][-1]["seq"] if page["items"] else 0
    check("300 messages stored", last == 304, last)

    x = stomp_user(ub2["accessToken"], sid, resume_after_seq=0)
    f = x.expect(on_sub("s1"))
    check("resume 300 (~1.8MB) -> SYSTEM_RESYNC, connection kept", f and f.json()["kind"] == "SYSTEM_RESYNC"
          and f.json()["fromSeq"] == 1 and not x.closed.is_set(), f)
    seqs, after = [], 0
    while True:
        s, p = call("GET", f"/sessions/{sid}/events", token=ub2["accessToken"], params={"afterSeq": after, "limit": 200})
        seqs += [i["seq"] for i in p["items"]]
        after = p["nextAfterSeq"]
        if not p["hasMore"]:
            break
    check("backfill after RESYNC is gap-free", seqs == list(range(1, 305)), (len(seqs), seqs[:3], seqs[-3:]))

    x.subscribe(topic(sid), "s2", resume_after_seq=204)
    f = x.expect(on_sub("s2"))
    check("resume 100 events (~600KB) -> SYSTEM_RESYNC by byte limit", f and f.json().get("kind") == "SYSTEM_RESYNC", f)

    x.subscribe(topic(sid), "s3", resume_after_seq=274)
    got = []
    end = time.time() + 5
    while time.time() < end:
        f = x.expect(on_sub("s3"), 2)
        if not f:
            break
        got.append(f.json())
        if f.json().get("kind") == "RESUMED":
            break
    events = [g["seq"] for g in got if "seq" in g]
    check("resume 30 events -> events then RESUMED", events == list(range(275, 305)) and got and got[-1].get("kind") == "RESUMED"
          and got[-1]["fromSeq"] == 275 and got[-1]["toSeq"] == 304, (events[:3], got[-1:] if got else None))

    x.subscribe(topic(sid), "s4", resume_after_seq=304)
    f = x.expect(on_sub("s4"))
    check("resume at head -> RESUMED with no events", f and f.json() == {"kind": "RESUMED", "sessionId": sid, "fromSeq": 305, "toSeq": 304}, f)
    live = str(uuid.uuid4())
    call("POST", f"/sessions/{sid}/events", msg("라이브", live), ua2["accessToken"])
    f = x.expect(lambda fr: on_sub("s4")(fr) and fr.json().get("clientEventId") == live)
    check("live event after RESUMED", f and f.json()["seq"] == 305, f)
    check("connection still open", not x.closed.is_set())
    x.close()

if "redis" in STAGES:
    sid = new_session("Redis 재연결")
    a = stomp_user(A, sid)
    redis = os.environ.get("COMPOSE_PROJECT", "event-based-chat") + "-redis-1"
    try:
        subprocess.run(["docker", "restart", redis], check=True, capture_output=True)
        resync = a.expect(lambda f: on_sub("s1")(f) and f.json().get("kind") == "SYSTEM_RESYNC", 30)
        check("redis restart: pub/sub re-subscribed -> SYSTEM_RESYNC on session topic", resync and resync.json()["sessionId"] == sid, resync)

        subprocess.run(["docker", "pause", redis], check=True, capture_output=True)
        deg = a.expect(lambda f: on_sub("system")(f) and f.json().get("kind") == "SYSTEM_DEGRADED", 15)
        check("redis unreachable -> SYSTEM_DEGRADED", deg and deg.json()["pollIntervalMs"] == 5000, deg)
        cid = str(uuid.uuid4())
        s, v = call("POST", f"/sessions/{sid}/events", msg("저하 중 메시지", cid), A)
        f = a.expect(lambda fr: on_sub("s1")(fr) and fr.json().get("clientEventId") == cid, 5)
        check("writes keep working while degraded, delivered to local subscribers", s == 201 and f is not None, (s, f))
        subprocess.run(["docker", "unpause", redis], check=True, capture_output=True)
        rec = a.expect(lambda f: on_sub("system")(f) and f.json().get("kind") == "SYSTEM_RECOVERED", 20)
        check("redis back -> SYSTEM_RECOVERED", rec is not None, rec)
        cid = str(uuid.uuid4())
        a.send(app(sid), msg("복구 후 메시지", cid))
        f = a.expect(lambda fr: on_sub("s1")(fr) and fr.json().get("clientEventId") == cid, 5)
        check("delivery works after redis recovery", f is not None, f)
        a.close()
    finally:
        subprocess.run(["docker", "unpause", redis], capture_output=True)

if "drain" in STAGES:
    sid = new_session("드레이닝")
    a = stomp_user(A, sid)
    started = time.time()
    app_container = os.environ.get("COMPOSE_PROJECT", "event-based-chat") + "-app-1"
    stopping = subprocess.Popen(["docker", "stop", "-t", "45", app_container], stdout=subprocess.DEVNULL,
                                stderr=subprocess.DEVNULL)
    try:
        a.wait_closed(20)
        check("shutdown closes websocket with 1012", a.close_code == 1012, (a.close_code, round(time.time() - started, 1)))
    finally:
        # 드레이닝을 확인했으면 앱을 다시 띄워 다음 스크립트를 이어서 돌릴 수 있게 한다.
        stopping.wait(60)
        subprocess.run(["docker", "start", app_container], capture_output=True)
        for _ in range(60):
            try:
                with urllib.request.urlopen(os.environ.get("MGMT_URL", "http://127.0.0.1:8081")
                                            + "/actuator/health/readiness") as r:
                    if r.status == 200:
                        break
            except Exception:
                pass
            time.sleep(2)

print(f"\n{sum(ok for _, ok in results)}/{len(results)} passed")

sys.exit(0 if results and all(ok for _, ok in results) else 1)
