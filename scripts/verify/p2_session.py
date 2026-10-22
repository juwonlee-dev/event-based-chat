import json, os, sys, threading, urllib.error, urllib.request, uuid

BASE = os.environ.get("BASE_URL", "http://127.0.0.1:8080")
results = []


def call(method, path, body=None, token=None, headers=None):
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
    print(("PASS " if cond else "FAIL ") + name + ("" if cond else "  " + json.dumps(detail, ensure_ascii=False)))


def login(u):
    return call("POST", "/auth/login", {"username": u, "password": "chat1234!"})[1]["accessToken"]


def key():
    return str(uuid.uuid4())


A, B, C, ADMIN = login("insurance.user"), login("bank.customer"), login("hospital.user"), login("admin")
me = {t: call("GET", "/users/me", token=t)[1]["id"] for t in (A, B, C)}

# 생성 + 멱등
k1 = key()
s, v = call("POST", "/sessions", {"title": "  보험 상담 테스트  "}, A, {"Idempotency-Key": k1})
check("create 201 WAITING", s == 201 and v["status"] == "WAITING" and v["title"] == "보험 상담 테스트"
      and v["category"] == "INSURANCE" and v["lastSeq"] == 2 and len(v["participants"]) == 1
      and v["participants"][0]["userId"] == me[A] and v["participants"][0]["state"] == "JOINED", (s, v))
sid = v["id"]
s, v2 = call("POST", "/sessions", {"title": "보험 상담 테스트"}, A, {"Idempotency-Key": k1})
check("create same key -> 200 same session", s == 200 and v2["id"] == sid, (s, v2))
s, e = call("POST", "/sessions", {"title": "다른 제목"}, A, {"Idempotency-Key": k1})
check("create same key other body -> 409", s == 409 and e["code"] == "IDEMPOTENCY_KEY_REUSED", (s, e))

# 판정 순서: 관리자 → 404
s, e = call("POST", "/sessions", {"title": "관리자"}, ADMIN)
check("admin create 403 ADMIN_READ_ONLY", s == 403 and e["code"] == "ADMIN_READ_ONLY", (s, e))
s, e = call("POST", f"/sessions/{uuid.uuid4()}/join", None, ADMIN)
check("admin join unknown -> 403 before 404", s == 403 and e["code"] == "ADMIN_READ_ONLY", (s, e))
s, e = call("POST", f"/sessions/{uuid.uuid4()}/join", None, B)
check("join unknown 404", s == 404 and e["code"] == "SESSION_NOT_FOUND", (s, e))

# WAITING 규칙
s, e = call("POST", f"/sessions/{sid}/end", None, A)
check("end in WAITING 409 SESSION_NOT_STARTED", s == 409 and e["code"] == "SESSION_NOT_STARTED"
      and e["sessionStatus"] == "WAITING", (s, e))
s, e = call("POST", f"/sessions/{sid}/join", None, A)
check("creator join 409 ALREADY_JOINED", s == 409 and e["code"] == "ALREADY_JOINED", (s, e))
s, e = call("POST", f"/sessions/{sid}/leave", None, B)
check("non participant leave 403", s == 403 and e["code"] == "NOT_PARTICIPANT", (s, e))
s, v = call("GET", f"/sessions/{sid}", None, C)
check("WAITING detail visible to anyone", s == 200 and v["id"] == sid, (s, v))

# 동시 참여: 한 명만 성공
out = {}
def join_as(t):
    out[t] = call("POST", f"/sessions/{sid}/join", None, t)
ts = [threading.Thread(target=join_as, args=(t,)) for t in (B, C)]
[t.start() for t in ts]; [t.join() for t in ts]
codes = sorted(r[0] for r in out.values())
check("concurrent join: one 200, one 409", codes == [200, 409], {k[-6:]: v for k, v in out.items()})
winner = B if out[B][0] == 200 else C
loser = C if winner == B else B
check("loser gets SESSION_NOT_JOINABLE", out[loser][1]["code"] == "SESSION_NOT_JOINABLE"
      and out[loser][1]["sessionStatus"] == "IN_PROGRESS", out[loser])
v = out[winner][1]
check("joined -> IN_PROGRESS, 2 participants, seq 4", v["status"] == "IN_PROGRESS" and v["startedAt"]
      and len(v["participants"]) == 2 and v["lastSeq"] == 4, v)
s, e = call("GET", f"/sessions/{sid}", None, loser)
check("IN_PROGRESS detail non participant 403", s == 403 and e["code"] == "NOT_PARTICIPANT", (s, e))
s, v = call("GET", f"/sessions/{sid}", None, ADMIN)
check("admin detail 200", s == 200, (s, v))

# 퇴장 + 같은 키 재시도
k2 = key()
s, v = call("POST", f"/sessions/{sid}/leave", None, winner, {"Idempotency-Key": k2})
check("leave -> SUSPENDED", s == 200 and v["status"] == "SUSPENDED" and v["endReason"] == "LEFT"
      and [p["state"] for p in v["participants"]] == ["JOINED", "LEFT"], (s, v))
s, v = call("POST", f"/sessions/{sid}/leave", None, winner, {"Idempotency-Key": k2})
check("leave retry same key -> 200", s == 200 and v["status"] == "SUSPENDED" and v["lastSeq"] == 6, (s, v))
s, e = call("POST", f"/sessions/{sid}/leave", None, winner)
check("leave again without key -> 403 PARTICIPANT_LEFT", s == 403 and e["code"] == "PARTICIPANT_LEFT", (s, e))
s, e = call("GET", f"/sessions/{sid}", None, winner)
check("leaver detail 403 PARTICIPANT_LEFT", s == 403 and e["code"] == "PARTICIPANT_LEFT", (s, e))
s, v = call("GET", f"/sessions/{sid}", None, A)
check("remaining participant detail 200", s == 200 and v["status"] == "SUSPENDED", (s, v))
s, e = call("POST", f"/sessions/{sid}/end", None, A)
check("end SUSPENDED -> 409 SESSION_NOT_ACTIVE", s == 409 and e["code"] == "SESSION_NOT_ACTIVE"
      and e["sessionStatus"] == "SUSPENDED", (s, e))
s, e = call("POST", f"/sessions/{sid}/join", None, loser)
check("join SUSPENDED -> 409 SESSION_NOT_JOINABLE", s == 409 and e["code"] == "SESSION_NOT_JOINABLE", (s, e))

# 종료 + 같은 키 재시도 + 다른 명령에 같은 키
s, v = call("POST", "/sessions", {"title": "은행 대출 문의"}, B)
sid2 = v["id"]
check("customer create category BANK", s == 201 and v["category"] == "BANK", (s, v))
call("POST", f"/sessions/{sid2}/join", None, C)
k3 = key()
s, v = call("POST", f"/sessions/{sid2}/end", None, C, {"Idempotency-Key": k3})
check("end -> COMPLETED", s == 200 and v["status"] == "COMPLETED" and v["endReason"] == "ENDED" and v["endedAt"], (s, v))
s, v = call("POST", f"/sessions/{sid2}/end", None, C, {"Idempotency-Key": k3})
check("end retry same key -> 200", s == 200 and v["status"] == "COMPLETED", (s, v))
s, e = call("POST", f"/sessions/{sid2}/leave", None, C, {"Idempotency-Key": k3})
check("same key on other command -> 409 IDEMPOTENCY_KEY_REUSED", s == 409 and e["code"] == "IDEMPOTENCY_KEY_REUSED", (s, e))
s, e = call("POST", f"/sessions/{sid2}/end", None, B)
check("end COMPLETED without key -> 409", s == 409 and e["code"] == "SESSION_NOT_ACTIVE", (s, e))
s, v = call("GET", f"/sessions/{sid2}", None, B)
check("COMPLETED detail visible to both", s == 200 and call("GET", f"/sessions/{sid2}", None, C)[0] == 200, (s, v))

# WAITING에서 생성자 퇴장
s, v = call("POST", "/sessions", {"title": "혼자 나가기"}, C)
sid3 = v["id"]
s, v = call("POST", f"/sessions/{sid3}/leave", None, C)
check("creator leaves WAITING -> SUSPENDED", s == 200 and v["status"] == "SUSPENDED"
      and v["participants"][0]["state"] == "LEFT", (s, v))
s, e = call("GET", f"/sessions/{sid3}", None, C)
check("creator after leave detail 403", s == 403 and e["code"] == "PARTICIPANT_LEFT", (s, e))
s, v = call("GET", f"/sessions/{sid3}", None, ADMIN)
check("admin sees empty suspended session", s == 200, (s, v))

# 같은 키로 동시 생성
k4 = key()
created = []
def create_once():
    created.append(call("POST", "/sessions", {"title": "동시 생성"}, A, {"Idempotency-Key": k4}))
ts = [threading.Thread(target=create_once) for _ in range(4)]
[t.start() for t in ts]; [t.join() for t in ts]
ids = {r[1].get("id") for r in created}
check("concurrent create same key -> one session", len(ids) == 1 and sorted(r[0] for r in created).count(201) == 1,
      [(r[0], r[1].get("id"), r[1].get("code")) for r in created])

# 검증
s, e = call("POST", "/sessions", {"title": "   "}, A)
check("blank title 400", s == 400 and e["code"] == "VALIDATION_FAILED", (s, e))
s, e = call("POST", "/sessions", {"title": "가" * 101}, A)
check("101 chars title 400", s == 400 and e["code"] == "VALIDATION_FAILED", (s, e))
s, v = call("POST", "/sessions", {"title": "가" * 100}, A)
check("100 chars title 201", s == 201, (s, v))
s, e = call("POST", "/sessions", {"title": "x"}, A, {"Idempotency-Key": "k" * 65})
check("long idempotency key 400", s == 400 and e["code"] == "VALIDATION_FAILED", (s, e))
s, e = call("GET", "/sessions/not-a-uuid", None, A)
check("bad uuid 400", s == 400, (s, e))

print(json.dumps({"sid": sid, "sid2": sid2, "sid3": sid3}))
print(f"\n{sum(ok for _, ok in results)}/{len(results)} passed")

sys.exit(0 if results and all(ok for _, ok in results) else 1)
