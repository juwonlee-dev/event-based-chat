import json, os, subprocess, sys, time, urllib.error, urllib.parse, urllib.request, uuid
from datetime import datetime, timedelta, timezone

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from stomp_client import Stomp, on_sub  # noqa: E402

BASE = os.environ.get("BASE_URL", "http://127.0.0.1:8080")
# kafka는 Kafka를 멈추므로 이름을 지정했을 때만 돈다.
STAGES = sys.argv[1].split(",") if len(sys.argv) > 1 else ["core", "dlt"]
PROJECT = os.environ.get("COMPOSE_PROJECT", "event-based-chat")
results = []


def call(method, path, body=None, token=None, params=None, headers=False):
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
            out = (r.status, (json.loads(raw) if raw else None))
            return out + (dict(r.headers),) if headers else out
    except urllib.error.HTTPError as e:
        raw = e.read()
        out = (e.code, (json.loads(raw) if raw else None))
        return out + (dict(e.headers),) if headers else out


def check(name, cond, detail=""):
    results.append((name, bool(cond)))
    print(("PASS " if cond else "FAIL ") + name + ("" if cond else "  " + str(detail)[:700]), flush=True)


def log(text):
    print("     " + text, flush=True)


def login(u):
    return call("POST", "/auth/login", {"username": u, "password": "chat1234!"})[1]["accessToken"]


def me(t):
    return call("GET", "/users/me", token=t)[1]["id"]


def psql(sql):
    return subprocess.run(["docker", "exec", PROJECT + "-postgres-1", "psql", "-U", "chat_owner", "-d", "chat",
                           "-tAc", sql], check=True, capture_output=True, text=True).stdout.strip()


def kafka(*args, stdin=None):
    return subprocess.run(["docker", "exec", "-i", PROJECT + "-kafka-1", *args], input=stdin, capture_output=True,
                          encoding="utf-8", errors="replace",
                          ).stdout


def timeline(sid, token, **params):
    return call("GET", f"/sessions/{sid}/timeline", token=token, params=params)


def msg(sid, token, text):
    return call("POST", f"/sessions/{sid}/events",
                {"type": "MESSAGE", "clientEventId": str(uuid.uuid4()), "payload": {"content": text}}, token)


def snapshot_seq(sid):
    v = psql(f"SELECT last_seq FROM session_snapshot WHERE session_id = '{sid}'")
    return int(v) if v else None


def wait_snapshot(sid, at_least, timeout=20):
    end = time.time() + timeout
    while time.time() < end:
        v = snapshot_seq(sid)
        if v is not None and v >= at_least:
            return v
        time.sleep(0.5)
    return snapshot_seq(sid)


def same_as_timeline(sid, token, label):
    detail = call("GET", f"/sessions/{sid}", token=token)[1]
    view = timeline(sid, token)[1]
    write_side = {p["userId"]: (p["state"], p["presence"]) for p in detail["participants"]}
    folded = {p["userId"]: (p["state"], p["presence"]["status"]) for p in view["participants"]}
    check(f"세션 상세 = timeline(현재) 참여 상태·presence ({label})", write_side == folded, (write_side, folded))
    check(f"세션 상세 상태 = timeline asOf 상태 ({label})", detail["status"] == view["asOf"]["status"]
          and detail["lastSeq"] == view["asOf"]["seq"], (detail["status"], view["asOf"]))


def part(view, uid):
    return next(p for p in view["participants"] if p["userId"] == uid)


ADMIN = login("admin")

if "core" in STAGES:
    A, B, C = login("telecom.user"), login("telecom.customer"), login("furniture.user")
    ida, idb = me(A), me(B)
    a = Stomp(A)
    time.sleep(0.3)
    sid = call("POST", "/sessions", {"title": "복원 확인"}, A)[1]["id"]
    call("POST", f"/sessions/{sid}/join", None, B)
    a.subscribe(f"/topic/sessions/{sid}", "s1")
    time.sleep(0.3)
    for i in range(60):
        msg(sid, A, f"상담사 {i}")
        msg(sid, B, f"고객 {i}")
    last = call("GET", f"/sessions/{sid}", token=A)[1]["lastSeq"]
    snap = wait_snapshot(sid, 100)
    log(f"lastSeq={last}, 자동 스냅샷 seq={snap}")
    check("100건 경계를 넘으면 outbox → Kafka → 작업자가 스냅샷을 만든다", snap is not None and snap >= 100, snap)
    check("outbox가 비워짐", psql("SELECT count(*) FROM event_outbox") == "0")

    # 같은 S에서 두 경로의 stateHash
    for s in (last - 3, 40):
        st, inc = timeline(sid, A, atSeq=s)
        st2, full = timeline(sid, ADMIN, atSeq=s, ignoreSnapshot="true")
        expect = "SNAPSHOT_INCREMENTAL" if s >= snap else "FULL_REPLAY"
        check(f"S={s}: 참여자 timeline 모드 {expect}", st == 200 and inc["restoredFrom"]["mode"] == expect, (st, inc))
        check(f"S={s}: ignoreSnapshot은 FULL_REPLAY, stateHash 같음",
              st2 == 200 and full["restoredFrom"]["mode"] == "FULL_REPLAY"
              and full["restoredFrom"]["stateHash"] == inc["restoredFrom"]["stateHash"]
              and full["restoredFrom"]["replayedEvents"] == s, (inc.get("restoredFrom"), full.get("restoredFrom")))
        if expect == "SNAPSHOT_INCREMENTAL":
            check(f"S={s}: 스냅샷 뒤 이벤트만 fold", inc["restoredFrom"]["snapshotSeq"] == snap
                  and inc["restoredFrom"]["replayedEvents"] == s - snap, inc["restoredFrom"])
    st, _ = timeline(sid, A, atSeq=10, ignoreSnapshot="true")
    check("ignoreSnapshot은 관리자만(403 ADMIN_ONLY)", st == 403 and _["code"] == "ADMIN_ONLY", _)

    # 스냅샷 API: 추가분만
    st, r1 = call("POST", f"/sessions/{sid}/snapshots", None, ADMIN)
    check("POST snapshots → UPDATED, 추가분만 적용", st == 200 and r1["mode"] == "UPDATED" and r1["previousSeq"] == snap
          and r1["appliedEvents"] == last - snap and r1["lastSeq"] == last, r1)
    st, r2 = call("POST", f"/sessions/{sid}/snapshots", None, ADMIN)
    check("다시 POST → UNCHANGED, appliedEvents 0", st == 200 and r2["mode"] == "UNCHANGED" and r2["appliedEvents"] == 0
          and r2["stateHash"] == r1["stateHash"], r2)
    st, cur = timeline(sid, A, atSeq=last)
    check("스냅샷 해시 = 같은 S의 timeline 해시", cur["restoredFrom"]["stateHash"] == r1["stateHash"]
          and cur["restoredFrom"]["replayedEvents"] == 0, cur["restoredFrom"])
    st, _ = call("POST", f"/sessions/{sid}/snapshots", None, A)
    check("POST snapshots는 관리자만(403 ADMIN_ONLY)", st == 403 and _["code"] == "ADMIN_ONLY", _)

    # 실시간 typing = timeline typing
    a.drain(0.3)
    call("POST", f"/sessions/{sid}/events", {"type": "TYPING_STARTED"}, B)
    f = a.expect(lambda f: on_sub("s1")(f) and f.json().get("type") == "TYPING_STARTED", 5)
    ev = f.json() if f else {}
    st, now, hdr = call("GET", f"/sessions/{sid}/timeline", token=A, headers=True)
    tb = part(now, idb)["typing"]
    check("실시간으로 받은 typing = 현재 timeline typing(같은 expiresAt)",
          tb["active"] and tb["expiresAt"] == ev.get("expiresAt"), (tb, ev.get("expiresAt")))
    check("Cache-Control: no-store", "no-store" in hdr.get("Cache-Control", ""), hdr.get("Cache-Control"))
    time.sleep(11)
    st, later = timeline(sid, A)
    check("TTL 10초가 지나면 현재 timeline에서 typing 꺼짐", part(later, idb)["typing"] == {"active": False, "expiresAt": None},
          part(later, idb)["typing"])
    st, at_typing = timeline(sid, A, atSeq=ev.get("seq"))
    check("typing 이벤트 시점(atSeq)으로 보면 켜져 있음", part(at_typing, idb)["typing"]["active"]
          and at_typing["asOf"]["referenceTime"] == ev.get("serverTime"), part(at_typing, idb))
    call("POST", f"/sessions/{sid}/events", {"type": "TYPING_STARTED"}, B)
    msg(sid, B, "보냅니다")
    st, sent = timeline(sid, A)
    check("메시지를 보내면 typing 꺼짐", not part(sent, idb)["typing"]["active"], part(sent, idb))

    # presence·읽음·unread
    check("A presence ONLINE(연결 중)", part(sent, ida)["presence"]["status"] == "ONLINE", part(sent, ida))
    check("A unreadCount = A의 마지막 메시지 뒤 B 메시지 수(2)", part(sent, ida)["unreadCount"] == 2
          and part(sent, idb)["unreadCount"] == 0, (part(sent, ida)["unreadCount"], part(sent, idb)["unreadCount"]))
    head = sent["asOf"]["seq"]
    call("POST", f"/sessions/{sid}/events", {"type": "READ_CURSOR", "payload": {"upToSeq": head}}, A)
    st, read = timeline(sid, A)
    check("읽음 커서 뒤 unreadCount 0, readCursor.seq", part(read, ida)["unreadCount"] == 0
          and part(read, ida)["readCursor"]["seq"] == head, part(read, ida))

    # 시각 지정
    created = read["session"]["createdAt"]
    st, t0 = timeline(sid, A, at=created)
    check("at = createdAt(밀리초) → 세션 생성 시점(S ≥ 1)", st == 200 and t0["asOf"]["seq"] >= 1
          and t0["asOf"]["status"] == "WAITING", (st, t0.get("asOf")))
    early = (datetime.fromisoformat(created.replace("Z", "+00:00")) - timedelta(seconds=1)).isoformat()
    st, e = timeline(sid, A, at=early)
    check("생성 이전 → 422 BEFORE_SESSION_START", st == 422 and e["code"] == "BEFORE_SESSION_START", (st, e))
    soon = (datetime.now(timezone.utc) + timedelta(seconds=30)).isoformat()
    st, e = timeline(sid, A, at=soon)
    check("60초 이내 미래 → 현재로 맞춤", st == 200 and e["asOf"]["seq"] == read["asOf"]["seq"], (st, e.get("asOf")))
    far = (datetime.now(timezone.utc) + timedelta(seconds=120)).isoformat()
    st, e = timeline(sid, A, at=far)
    check("먼 미래 → 400 AT_IN_FUTURE", st == 400 and e["code"] == "AT_IN_FUTURE", (st, e))
    st, e = timeline(sid, A, at="2026-10-21T10:00:00")
    check("오프셋 없는 at → 400 INVALID_TIME_FORMAT", st == 400 and e["code"] == "INVALID_TIME_FORMAT", (st, e))
    st, e = timeline(sid, A, at=created, atSeq=3)
    check("at과 atSeq 함께 → 400", st == 400 and e["code"] == "VALIDATION_FAILED", (st, e))

    # 화면 항목 페이지
    st, p1 = timeline(sid, A, itemLimit=10)
    seqs = [i["seq"] for i in p1["items"]["list"]]
    check("items: 최근 10건, seq 오름차순, nextBeforeSeq = 최소 seq", len(seqs) == 10 and seqs == sorted(seqs)
          and p1["items"]["hasMore"] and p1["items"]["nextBeforeSeq"] == seqs[0], p1["items"].get("nextBeforeSeq"))
    check("items에 typing·읽음 커서 없음", all(i["type"] not in ("TYPING_STARTED", "TYPING_STOPPED", "READ_CURSOR")
                                          for i in p1["items"]["list"]))
    st, p2 = timeline(sid, A, itemLimit=10, beforeSeq=p1["items"]["nextBeforeSeq"])
    s2 = [i["seq"] for i in p2["items"]["list"]]
    check("beforeSeq로 이전 페이지", len(s2) == 10 and max(s2) < seqs[0], s2)

    # 쓰기 측 현재 상태(세션 상세)와 이벤트를 fold한 현재 상태(timeline)가 같아야 한다
    same_as_timeline(sid, A, "진행 중")

    # 권한
    st, e = timeline(sid, C)
    check("참여자가 아니면 403 NOT_PARTICIPANT", st == 403 and e["code"] == "NOT_PARTICIPANT", (st, e))
    call("POST", f"/sessions/{sid}/leave", None, B)
    st, e = timeline(sid, B, atSeq=10)
    check("퇴장 후 timeline 403 PARTICIPANT_LEFT(과거 시점이라도)", st == 403 and e["code"] == "PARTICIPANT_LEFT", (st, e))
    final = call("GET", f"/sessions/{sid}", token=A)[1]
    fs = wait_snapshot(sid, final["lastSeq"])
    check("종단 전이 때 자동 스냅샷이 마지막 seq까지", fs == final["lastSeq"], (fs, final["lastSeq"]))
    st, end = timeline(sid, A)
    check("종료된 세션 현재 복원은 리플레이 없이(replayedEvents 0)", end["restoredFrom"]["mode"] == "SNAPSHOT_INCREMENTAL"
          and end["restoredFrom"]["replayedEvents"] == 0 and end["asOf"]["status"] == "SUSPENDED"
          and part(end, idb)["state"] == "LEFT", end["restoredFrom"])
    same_as_timeline(sid, A, "퇴장 뒤(종단)")
    st, adm = timeline(sid, ADMIN, atSeq=50)
    check("관리자는 참여하지 않아도 timeline 조회", st == 200, st)
    a.close()

    # 모르는 스키마 버전 → 422
    sid2 = call("POST", "/sessions", {"title": "리플레이 불가"}, C)[1]["id"]
    psql(f"UPDATE session_event SET schema_version = 99 WHERE session_id = '{sid2}' AND seq = 2")
    st, e = timeline(sid2, ADMIN, ignoreSnapshot="true")
    check("모르는 스키마 버전 → 422 REPLAY_UNSUPPORTED(건너뛰지 않음)", st == 422 and e["code"] == "REPLAY_UNSUPPORTED"
          and e.get("seq") == 2, (st, e))
    # payload가 깨진 이벤트도 500이 아니라 결정적으로 422
    sid3 = call("POST", "/sessions", {"title": "payload 결함"}, C)[1]["id"]
    psql(f"UPDATE session_event SET payload = '{{}}'::jsonb WHERE session_id = '{sid3}' AND seq = 2")
    st, e = timeline(sid3, ADMIN, ignoreSnapshot="true")
    check("payload 결함(JOIN role 누락) → 422 REPLAY_UNSUPPORTED", st == 422 and e["code"] == "REPLAY_UNSUPPORTED"
          and e.get("seq") == 2, (st, e))

if "dlt" in STAGES:
    bad_id = str(uuid.uuid4())
    kafka("/opt/kafka/bin/kafka-console-producer.sh", "--bootstrap-server", "localhost:9092",
          "--topic", "chat.snapshot.requests", stdin='not-json\n{"sessionId":"' + bad_id + '","upToSeq":1}\n')
    out = kafka("/opt/kafka/bin/kafka-console-consumer.sh", "--bootstrap-server", "localhost:9092",
                "--topic", "chat.snapshot.requests.DLT", "--from-beginning",
                "--timeout-ms", "20000", "--property", "print.headers=true")
    log("DLT: " + " | ".join(l[-160:] for l in out.strip().splitlines()))
    check("형식이 틀린 요청은 재시도 없이 DLT", "not-json" in out, out[-500:])
    check("없는 세션 요청도 DLT(예외 정보 헤더 포함)", bad_id in out and "kafka_dlt-exception" in out, out[-500:])

if "kafka" in STAGES:
    A, B = login("community-center.user"), login("community-center.customer")
    sid = call("POST", "/sessions", {"title": "Kafka 중단"}, A)[1]["id"]
    call("POST", f"/sessions/{sid}/join", None, B)
    subprocess.run(["docker", "stop", PROJECT + "-kafka-1"], check=True, capture_output=True)
    try:
        t = time.time()
        statuses = {msg(sid, A, f"m{i}")[0] for i in range(100)}
        log(f"Kafka 중단 중 메시지 100건 {time.time() - t:.1f}초")
        check("Kafka가 멈춰도 쓰기는 성공", statuses == {201}, statuses)
        time.sleep(3)
        pending = psql(f"SELECT count(*) FROM event_outbox WHERE key = '{sid}'")
        check("outbox에 요청이 쌓임", pending == "1", pending)
    finally:
        subprocess.run(["docker", "start", PROJECT + "-kafka-1"], check=True, capture_output=True)
    last = call("GET", f"/sessions/{sid}", token=A)[1]["lastSeq"]
    snap = wait_snapshot(sid, 100, timeout=90)
    check("Kafka가 돌아오면 쌓인 요청이 발행되어 스냅샷 생성", snap == last, (snap, last))
    check("outbox 비워짐", psql("SELECT count(*) FROM event_outbox") == "0")

passed = sum(1 for _, ok in results if ok)
print(f"\n{passed}/{len(results)} passed")

sys.exit(0 if results and all(ok for _, ok in results) else 1)
