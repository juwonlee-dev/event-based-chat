import json, os, subprocess, sys, time, urllib.error, urllib.parse, urllib.request, uuid

os.environ.setdefault("STOMP_URL", "ws://127.0.0.1:8000/ws")
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from stomp_client import Stomp, on_sub  # noqa: E402

BASE = os.environ.get("BASE_URL", "http://127.0.0.1:8000")
# kill(앱 강제 종료)과 pause(일시 정지)는 컨테이너를 건드리므로 이름을 지정했을 때만 돈다.
STAGES = sys.argv[1].split(",") if __name__ == "__main__" and len(sys.argv) > 1 else ["cross"]
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


def me(t):
    return call("GET", "/users/me", token=t)[1]["id"]


def topic(sid):
    return f"/topic/sessions/{sid}"


def app_containers():
    """업스트림 IP → 컨테이너 이름"""
    names = subprocess.run(["docker", "ps", "-a", "--filter", f"label=com.docker.compose.project={PROJECT}",
                            "--filter", "label=com.docker.compose.service=app", "--format", "{{.Names}}"],
                           capture_output=True, text=True).stdout.split()
    out = {}
    for n in names:
        ip = subprocess.run(["docker", "inspect", "-f", "{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}", n],
                            capture_output=True, text=True).stdout.strip()
        if ip:
            out[ip] = n
    return out


def ip(upstream):
    return upstream.split(":")[0] if upstream else None


def connect(token, avoid=None, want=None, tries=12):
    """nginx(least_conn)를 거쳐 연결하되, 원하는 인스턴스에 붙을 때까지 다시 연결한다."""
    held = []
    try:
        for _ in range(tries):
            c = Stomp(token)
            if c.connected is None or c.connected.command != "CONNECTED":
                c.close()
                time.sleep(0.5)
                continue
            if (want is None or ip(c.upstream) == want) and (avoid is None or ip(c.upstream) != avoid):
                return c
            held.append(c)  # 붙잡아 두면 least_conn이 다음 연결을 다른 인스턴스로 보낸다
        raise RuntimeError(f"원하는 인스턴스에 붙지 못함 want={want} avoid={avoid}")
    finally:
        for c in held:
            c.close()


def events_of(sid, token, after=0):
    return call("GET", f"/sessions/{sid}/events", token=token, params={"afterSeq": after, "limit": 200})[1]["items"]


def presence_events(sid, token, subject):
    return [e for e in events_of(sid, token) if e["type"] in ("DISCONNECT", "RECONNECT") and e["subjectId"] == subject]


def collect(client, sub, until_seq, timeout):
    """구독에서 seq를 모은다(도착 순서 그대로). RESYNC가 오면 따로 센다."""
    got, resync, end = [], 0, time.time() + timeout
    while time.time() < end:
        f = client.expect(on_sub(sub), max(0.05, end - time.time()))
        if not f:
            break
        body = f.json()
        if body.get("kind") == "SYSTEM_RESYNC":
            resync += 1
        elif "seq" in body:
            got.append(body["seq"])
            if max(got) >= until_seq and set(range(min(got), until_seq + 1)) <= set(got):
                break
    return got, resync


A, B = login("insurance.user"), login("insurance.customer")
ida, idb = me(A), me(B)
nodes = app_containers()
log(f"앱 인스턴스 {len(nodes)}대: {sorted(nodes.values())}")

a = connect(A)
b = connect(B, avoid=ip(a.upstream))
sid = call("POST", "/sessions", {"title": "클러스터 확인"}, A)[1]["id"]
call("POST", f"/sessions/{sid}/join", None, B)
for c in (a, b):
    c.subscribe(topic(sid), "s1")
time.sleep(0.5)

def run_stages():
    global a, b, nodes
    restore = []
    try:
        if "cross" in STAGES:
            log(f"A → {nodes.get(ip(a.upstream))}, B → {nodes.get(ip(b.upstream))}")
            check("두 사용자가 서로 다른 앱 인스턴스에 연결", ip(a.upstream) != ip(b.upstream), (a.upstream, b.upstream))
            a.send(f"/app/sessions/{sid}/events", {"type": "MESSAGE", "clientEventId": str(uuid.uuid4()),
                                                   "payload": {"content": "다른 노드로"}})
            f = b.expect(lambda f: on_sub("s1")(f) and f.json().get("type") == "MESSAGE", 5)
            check("다른 인스턴스의 사용자에게 실시간 전달(A → B)", f and f.json()["payload"]["content"] == "다른 노드로", f)
            b.send(f"/app/sessions/{sid}/events", {"type": "MESSAGE", "clientEventId": str(uuid.uuid4()),
                                                   "payload": {"content": "답장"}})
            f = a.expect(lambda f: on_sub("s1")(f) and f.json().get("type") == "MESSAGE" and f.json()["payload"]["content"] == "답장", 5)
            check("반대 방향도 전달(B → A)", f is not None)

            # 번갈아 빠르게 보내기: 서로 다른 노드가 각자 커밋·발행하므로 도착 순서가 seq와 다를 수 있다.
            a.drain(0.5), b.drain(0.1)
            start = events_of(sid, A)[-1]["seq"]
            n = 100
            for i in range(n):
                (a if i % 2 == 0 else b).send(f"/app/sessions/{sid}/events", {
                    "type": "MESSAGE", "clientEventId": str(uuid.uuid4()), "payload": {"content": f"번갈아 {i}"}})
            until = start + n
            got_a, resync_a = collect(a, "s1", until, 20)
            got_b, resync_b = collect(b, "s1", until, 20)
            expected = set(range(start + 1, until + 1))
            inversions = lambda g: sum(1 for i in range(1, len(g)) if g[i] < max(g[:i]))
            log(f"A: {len(got_a)}건, 도착 순서 역전 {inversions(got_a)}회 / B: {len(got_b)}건, 역전 {inversions(got_b)}회")
            check("번갈아 100건: 양쪽 모두 빠짐없이 받음(클라이언트가 seq로 재정렬)",
                  expected <= set(got_a) and expected <= set(got_b), (len(set(got_a) & expected), len(set(got_b) & expected)))
            check("RESYNC 0건", resync_a == 0 and resync_b == 0, (resync_a, resync_b))

        if "kill" in STAGES:
            C, D = login("bank.user"), login("bank.customer")
            idd = me(D)
            dead = ip(b.upstream)
            d = connect(D, want=dead)
            c = connect(C, avoid=dead)
            sid2 = call("POST", "/sessions", {"title": "노드 장애"}, C)[1]["id"]
            call("POST", f"/sessions/{sid2}/join", None, D)
            c.subscribe(topic(sid2), "s2")
            time.sleep(0.5)
            last = events_of(sid, A)[-1]["seq"]
            victim = nodes[dead]
            log(f"B·D가 붙은 {victim}를 강제 종료")
            killed_at = time.time()
            subprocess.run(["docker", "kill", victim], check=True, capture_output=True)
            restore.append(["docker", "start", victim])
            check("강제 종료된 인스턴스의 연결이 끊김", b.wait_closed(10) and d.wait_closed(10), (b.close_code, d.close_code))

            # B는 바로 다시 붙는다(nginx가 죽은 업스트림을 빼고 다른 인스턴스로 보낸다).
            b = connect(B, avoid=dead)
            b.subscribe(topic(sid), "s1", resume_after_seq=last)
            f = b.expect(lambda f: on_sub("s1")(f) and f.json().get("kind") in ("RESUMED", "SYSTEM_RESYNC"), 10)
            check("nginx를 거쳐 다른 인스턴스로 재연결 + resume", ip(b.upstream) != dead and f and f.json()["kind"] == "RESUMED",
                  (b.upstream, f))
            a.send(f"/app/sessions/{sid}/events", {"type": "MESSAGE", "clientEventId": str(uuid.uuid4()),
                                                   "payload": {"content": "재연결 뒤"}})
            f = b.expect(lambda f: on_sub("s1")(f) and f.json().get("type") == "MESSAGE", 5)
            check("재연결한 B가 실시간으로 계속 받음", f is not None)

            # D는 다시 붙지 않는다 → lease 만료(15초) + 스위퍼 → NODE_LOST로 해제 → 유예 15초 → DISCONNECT
            f = c.expect(lambda f: on_sub("s2")(f) and f.json().get("type") == "DISCONNECT" and f.json().get("subjectId") == idd, 60)
            took = time.time() - killed_at
            log(f"D DISCONNECT {took:.1f}초 뒤, payload={f and f.json().get('payload')}")
            check("다시 붙지 않은 사용자는 DISCONNECT(NODE_LOST), 약 32초", f and f.json()["payload"]["reason"] == "NODE_LOST"
                  and 25 <= took <= 45, (took, f))
            check("다시 붙은 B는 DISCONNECT 없음", [e for e in presence_events(sid, A, idb) if e["type"] == "DISCONNECT"] == [])
            c.close()
            subprocess.run(["docker", "start", victim], check=True, capture_output=True)
            nodes = app_containers()

        if "pause" in STAGES:
            target = ip(a.upstream)
            victim = nodes[target]
            log(f"A가 붙은 {victim}를 20초 일시 정지")
            before = len(presence_events(sid, B, ida))
            subprocess.run(["docker", "pause", victim], check=True, capture_output=True)
            restore.append(["docker", "unpause", victim])
            time.sleep(20)
            subprocess.run(["docker", "unpause", victim], check=True, capture_output=True)
            time.sleep(8)
            logs = subprocess.run(["docker", "logs", "--since", "40s", victim], capture_output=True, text=True,
                                  encoding="utf-8", errors="replace").stdout
            check("재개한 노드가 lease 상실을 알아채고 자기 연결을 다시 등록", "trigger=LEASE_LOST" in logs,
                  [l for l in logs.splitlines() if "presence" in l][-3:])
            if a.closed.is_set():
                log("정지 중 heart-beat가 끊겨 A 연결이 닫힘 → 바로 재연결")
                a = connect(A)
            time.sleep(25)
            after = presence_events(sid, B, ida)[before:]
            check("정지·재개 뒤 A에 DISCONNECT 기록 없음(유예 안에 바로잡힘)",
                  [e for e in after if e["type"] == "DISCONNECT"] == [], after)
    finally:
        for cmd in restore:
            subprocess.run(cmd, capture_output=True)


if __name__ == "__main__":
    run_stages()
    passed = sum(1 for _, ok in results if ok)
    print(f"\n{passed}/{len(results)} passed")
    sys.exit(0 if results and all(ok for _, ok in results) else 1)
