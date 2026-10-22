import json, os, sys, time, urllib.error, urllib.parse, urllib.request, uuid

BASE = os.environ.get("BASE_URL", "http://127.0.0.1:8080")
MGMT = os.environ.get("MGMT_URL", "http://127.0.0.1:8081")
results = []


def call(method, path, body=None, token=None, params=None, base=BASE):
    if params:
        path += "?" + urllib.parse.urlencode(params, doseq=True)
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(base + path, data=data, method=method)
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
    print(("PASS " if cond else "FAIL ") + name + ("" if cond else "  " + json.dumps(detail, ensure_ascii=False, default=str)[:700]))


def login(u):
    return call("POST", "/auth/login", {"username": u, "password": "chat1234!"})[1]["accessToken"]


def send(token, sid, type_, payload=None, cid=None):
    body = {"type": type_, "payload": payload}
    if cid is not None:
        body["clientEventId"] = cid
    return call("POST", f"/sessions/{sid}/events", body, token)


def suppressed(type_):
    s, m = call("GET", "/actuator/metrics/chat.events.suppressed", params={"tag": f"type:{type_}"}, base=MGMT)
    return 0 if s != 200 else m["measurements"][0]["value"]


A, B, C, ADMIN = login("hospital.user"), login("hospital.customer"), login("furniture.user"), login("admin")
me = {t: call("GET", "/users/me", token=t)[1]["id"] for t in (A, B)}
sid = call("POST", "/sessions", {"title": "이벤트 수집 검증"}, A)[1]["id"]

s, e = send(A, sid, "MESSAGE", {"content": "먼저 보내기"}, str(uuid.uuid4()))
check("message in WAITING -> 409 SESSION_NOT_ACTIVE", s == 409 and e["code"] == "SESSION_NOT_ACTIVE"
      and e["sessionStatus"] == "WAITING", (s, e))
call("POST", f"/sessions/{sid}/join", None, B)

# 메시지 + 멱등
cid1 = str(uuid.uuid4())
s, m1 = send(A, sid, "MESSAGE", {"content": "  안녕하세요  "}, cid1)
check("message 201", s == 201 and m1["type"] == "MESSAGE" and m1["msgNo"] == 1 and m1["payload"] == {"content": "안녕하세요"}
      and m1["clientEventId"] == cid1 and m1["actorId"] == me[A] and m1["serverTime"].endswith("Z")
      and "ttlMs" not in m1 and "schemaVersion" not in m1, (s, m1))
s, dup = send(A, sid, "MESSAGE", {"content": "안녕하세요"}, cid1)
check("resend same clientEventId -> 200 same seq", s == 200 and dup["seq"] == m1["seq"] and dup["eventId"] == m1["eventId"], (s, dup))
s, e = send(A, sid, "MESSAGE", {"content": "다른 내용"}, cid1)
check("same clientEventId other content -> 409", s == 409 and e["code"] == "IDEMPOTENCY_KEY_REUSED", (s, e))

# 검증
s, e = send(A, sid, "MESSAGE", {"content": "x"})
check("message without clientEventId -> 400", s == 400 and e["code"] == "VALIDATION_FAILED", (s, e))
s, e = send(A, sid, "MESSAGE", {"content": "   "}, str(uuid.uuid4()))
check("blank content -> 400", s == 400 and e["code"] == "VALIDATION_FAILED", (s, e))
s, v = send(A, sid, "MESSAGE", {"content": "가" * 2000}, str(uuid.uuid4()))
check("2000 chars -> 201", s == 201, (s, v))
s, e = send(A, sid, "MESSAGE", {"content": "가" * 2001}, str(uuid.uuid4()))
check("2001 chars -> 400 MESSAGE_TOO_LONG", s == 400 and e["code"] == "MESSAGE_TOO_LONG"
      and e["maxLength"] == 2000 and e["actualLength"] == 2001, (s, e))
s, v = send(A, sid, "MESSAGE", {"content": "😀" * 2000}, str(uuid.uuid4()))
check("2000 emoji (surrogate pairs) -> 201", s == 201, (s, v))
for t in ("JOIN", "DISCONNECT", "RECONNECT", "SESSION_STATUS_CHANGED", "foo", None):
    s, e = send(A, sid, t, {}, str(uuid.uuid4()))
    check(f"type {t} -> 400 EVENT_TYPE_NOT_ALLOWED", s == 400 and e["code"] == "EVENT_TYPE_NOT_ALLOWED", (s, e))

# 권한
s, e = send(ADMIN, sid, "MESSAGE", {"content": "관리자"}, str(uuid.uuid4()))
check("admin message -> 403 ADMIN_READ_ONLY", s == 403 and e["code"] == "ADMIN_READ_ONLY", (s, e))
s, e = send(C, sid, "MESSAGE", {"content": "끼어들기"}, str(uuid.uuid4()))
check("non participant -> 403 NOT_PARTICIPANT", s == 403 and e["code"] == "NOT_PARTICIPANT", (s, e))

# typing
s, t1 = send(B, sid, "TYPING_STARTED")
check("typing started -> 201 with ttl", s == 201 and t1["ttlMs"] == 10000 and t1["subjectId"] == me[B] and t1["expiresAt"], (s, t1))
s, _ = send(B, sid, "TYPING_STARTED")
check("typing started again within 3s -> 204", s == 204, s)
s, t2 = send(B, sid, "TYPING_STOPPED")
check("typing stopped while typing -> 201", s == 201 and "ttlMs" not in t2, (s, t2))
s, _ = send(B, sid, "TYPING_STOPPED")
check("typing stopped again -> 204", s == 204, s)
s, _ = send(B, sid, "TYPING_STARTED")
check("typing started after stop -> 201", s == 201, s)
time.sleep(3.1)
s, _ = send(B, sid, "TYPING_STARTED")
check("typing started after 3s -> 201 (refresh)", s == 201, s)
send(B, sid, "MESSAGE", {"content": "네 말씀하세요"}, str(uuid.uuid4()))
s, _ = send(B, sid, "TYPING_STOPPED")
check("message clears typing (stopped -> 204)", s == 204, s)

# 읽음 커서: 1초 안에 10번 → 1건 저장 + 9건 억제
send(A, sid, "MESSAGE", {"content": "확인했습니다"}, str(uuid.uuid4()))
s, page = call("GET", f"/sessions/{sid}/events", token=A, params={"limit": 200})
last_seq = max(i["seq"] for i in page["items"])
before = suppressed("READ_CURSOR")
codes, stored = [], []
for _ in range(10):
    s, r = send(B, sid, "READ_CURSOR", {"upToSeq": 9999})
    codes.append(s)
    if r:
        stored.append(r)
time.sleep(0.3)
after = suppressed("READ_CURSOR")
check("10 READs -> all 202, one stored", codes == [202] * 10 and len(stored) == 1, (codes, stored))
check("read cursor clamped to last seq with readMsgNo", stored and stored[0]["payload"]["upToSeq"] == last_seq
      and stored[0]["payload"]["readMsgNo"] == 5, stored)
check("suppressed metric +9", after - before == 9, (before, after))
send(A, sid, "MESSAGE", {"content": "새 메시지"}, str(uuid.uuid4()))
s, r = send(B, sid, "READ_CURSOR", {"upToSeq": 9999})
check("advanced but within 1s -> not stored", s == 202 and r is None, (s, r))
time.sleep(1.1)
s, r = send(B, sid, "READ_CURSOR", {"upToSeq": 9999})
check("after 1s -> stored", s == 202 and r and r["payload"]["readMsgNo"] == 6, (s, r))
s, e = send(B, sid, "READ_CURSOR", {"upToSeq": -1})
check("negative upToSeq -> 400", s == 400, (s, e))
# 커서는 마지막 MESSAGE 기준이다. 그 뒤로 비메시지 이벤트(방금 저장한 READ_CURSOR)만 늘었으면 저장하지 않는다.
time.sleep(1.1)
s, r = send(B, sid, "READ_CURSOR", {"upToSeq": 9999})
check("only non-message events after cursor -> not stored (no self feedback loop)", s == 202 and r is None, (s, r))
s, page = call("GET", f"/sessions/{sid}/events", token=A, params={"limit": 200})
msgs = [i for i in page["items"] if i["type"] == "MESSAGE"]
reads = [i for i in page["items"] if i["type"] == "READ_CURSOR"]
check("stored cursor points at a MESSAGE seq with its msgNo",
      reads and reads[-1]["payload"]["upToSeq"] == msgs[-1]["seq"]
      and reads[-1]["payload"]["readMsgNo"] == msgs[-1]["msgNo"], (reads[-1:], msgs[-1:]))

# 이벤트 조회
s, page = call("GET", f"/sessions/{sid}/events", token=A, params={"limit": 200})
seqs = [i["seq"] for i in page["items"]]
check("participant events gap-free from 1", s == 200 and seqs == list(range(1, len(seqs) + 1)) and not page["hasMore"]
      and page["nextAfterSeq"] == seqs[-1] and all("schemaVersion" not in i for i in page["items"]), (s, seqs))
paged, after_seq = [], 0
while True:
    s, p = call("GET", f"/sessions/{sid}/events", token=B, params={"afterSeq": after_seq, "limit": 3})
    paged += [i["seq"] for i in p["items"]]
    after_seq = p["nextAfterSeq"]
    if not p["hasMore"]:
        break
check("afterSeq paging matches", paged == seqs, paged)
s, e = call("GET", f"/sessions/{sid}/events", token=A, params={"from": "2026-01-01T00:00:00Z"})
check("participant from -> 403 ADMIN_ONLY", s == 403 and e["code"] == "ADMIN_ONLY", (s, e))
s, e = call("GET", f"/sessions/{sid}/events", token=A, params={"limit": 201})
check("participant limit 201 -> 400", s == 400, (s, e))
s, e = call("GET", f"/sessions/{sid}/events", token=C)
check("non participant events -> 403", s == 403 and e["code"] == "NOT_PARTICIPANT", (s, e))
items = page["items"]
mid = items[len(items) // 2]
s, w = call("GET", f"/sessions/{sid}/events", token=ADMIN, params={"from": items[2]["serverTime"], "to": mid["serverTime"], "limit": 500})
check("admin from/to window inclusive", s == 200 and w["items"] and w["items"][0]["serverTime"] >= items[2]["serverTime"]
      and w["items"][-1]["serverTime"] <= mid["serverTime"] and mid["seq"] in [i["seq"] for i in w["items"]]
      and all(i["schemaVersion"] == 1 for i in w["items"]), (s, w and [i["seq"] for i in w["items"]]))
s, w = call("GET", f"/sessions/{sid}/events", token=ADMIN, params={"types": ["MESSAGE", "READ_CURSOR"]})
check("admin types filter", s == 200 and {i["type"] for i in w["items"]} == {"MESSAGE", "READ_CURSOR"}, (s, w))
s, e = call("GET", f"/sessions/{sid}/events", token=ADMIN, params={"from": "yesterday"})
check("bad time -> 400 INVALID_TIME_FORMAT", s == 400 and e["code"] == "INVALID_TIME_FORMAT", (s, e))
s, e = call("GET", f"/sessions/{sid}/events", token=ADMIN, params={"from": "2026-10-22T00:00:00Z", "to": "2026-10-21T00:00:00Z"})
check("admin from > to -> 400", s == 400 and e["code"] == "VALIDATION_FAILED", (s, e))
s, w = call("GET", f"/sessions/{sid}/events", token=ADMIN, params={"limit": 500})
check("admin limit 500 ok", s == 200, s)

# 종료 후: 같은 키 재전송은 200, 새 이벤트는 409
call("POST", f"/sessions/{sid}/end", None, A)
s, v = send(A, sid, "MESSAGE", {"content": "안녕하세요"}, cid1)
check("after end, resend same key -> 200 same seq", s == 200 and v["seq"] == m1["seq"], (s, v))
s, e = send(A, sid, "MESSAGE", {"content": "종료 후"}, str(uuid.uuid4()))
check("after end, new message -> 409", s == 409 and e["code"] == "SESSION_NOT_ACTIVE" and e["sessionStatus"] == "COMPLETED", (s, e))
s, e = send(B, sid, "TYPING_STARTED")
check("after end, typing -> 409", s == 409, (s, e))
s, page = call("GET", f"/sessions/{sid}/events", token=B)
check("completed session events readable by participant", s == 200, s)

# 퇴장자는 조회 불가
sid2 = call("POST", "/sessions", {"title": "퇴장 후 조회"}, A)[1]["id"]
call("POST", f"/sessions/{sid2}/join", None, B)
call("POST", f"/sessions/{sid2}/leave", None, B)
s, e = call("GET", f"/sessions/{sid2}/events", token=B)
check("leaver events -> 403 PARTICIPANT_LEFT", s == 403 and e["code"] == "PARTICIPANT_LEFT", (s, e))
s, page = call("GET", f"/sessions/{sid2}/events", token=A)
check("remaining participant events 200", s == 200 and page["items"][-1]["payload"]["to"] == "SUSPENDED", (s, page))

print(json.dumps({"sid": sid}))
print(f"\n{sum(ok for _, ok in results)}/{len(results)} passed")

sys.exit(0 if results and all(ok for _, ok in results) else 1)
